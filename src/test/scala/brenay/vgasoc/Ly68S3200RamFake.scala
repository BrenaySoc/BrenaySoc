package brenay.vgasoc

import spinal.core.ClockDomain
import spinal.core.sim._
import spinal.lib.sim.StreamMonitor

import scala.Array
import scala.collection.mutable.Queue

import brenay.lib.gatemate.SioSerializerCc
import org.scalatest.Assertions.assert

object Ly68S3200RamFake {
  sealed trait DontCareDataPolicy {
    def generateDataValue(): Long
  }

  case object DontCareDataAsSimTime extends DontCareDataPolicy {
    override def generateDataValue(): Long = simTime & ((1L << 32) - 1)
  }
  case object DontCareDataAsRandom extends DontCareDataPolicy {
    override def generateDataValue(): Long = simRandom.nextLong & ((1L << 32) - 1)
  }
  case object DontCareDataAsZero extends DontCareDataPolicy {
    override def generateDataValue(): Long = 0
  }

}

/** Simulate a LY68S3200 PSRAM  by responding on the sioBus */
class Ly68S3200RamFake(
    clockDomain: ClockDomain,
    sioBus: SioSerializerCc.Bus,
    val memory: Array[Int] = Array.fill((1 << 24) - 1)(0),
    dontCareDataPolicy: Ly68S3200RamFake.DontCareDataPolicy = Ly68S3200RamFake.DontCareDataAsRandom
) {
  var state = "idleSpi"
  var stateCounter = 0
  var currentAddress: Int = 0

  sioBus.upStream.valid #= false
  sioBus.downStream.ready #= true

  case class UpStreamResponse(
      val isWrite: Boolean,
      val data: Long,
      val last: Boolean,
      val missed: Boolean,
      var cycleDelay: Int
  )

  var upQueue = Queue[UpStreamResponse]()

  def getDataAsSpiValue(): Long = {
    var out = 0
    val shifted = sioBus.downStream.payload.data.toInt

    assert(sioBus.downStream.payload.data.getBitsWidth == 32)
    for (i <- 0 until sioBus.downStream.payload.data.getBitsWidth / 4) {
      out |= ((shifted >> i * 4) & 0x1) << i
    }
    out
  }

  StreamMonitor(sioBus.downStream, clockDomain) { downPayload =>
    state match {
      case "idleSpi" => {
        assert(
          downPayload.cmd.toEnum == SioSerializerCc.Cmd.WRITE,
          "in idle only start with WRITE allowed"
        )
        assert(
          downPayload.last.toBoolean,
          "currently RamFake support only ENTER_QPI_MODE"
        )
        assert(
          getDataAsSpiValue() == SioRam.Ly68S3200Cmd.EnterQpiMode.value,
          "currently RamFake support only EnterQpiMode"
        )
        assert(
          downPayload.byteSizeMinusOne.toInt == 3,
          "currently RamFake support only ENTER_QPI_MODE"
        )
        assert(sioBus.upStream.ready.toBoolean, "upStream should always be ready")

        upQueue.enqueue(
          UpStreamResponse(
            isWrite = true,
            data = dontCareDataPolicy.generateDataValue(),
            last = true,
            missed = false,
            cycleDelay = 0
          )
        )
        state = "idleQpi"
        stateCounter = 0
      }

      case "idleQpi" => {
        assert(
          downPayload.cmd.toEnum == SioSerializerCc.Cmd.WRITE,
          "in idle only start with WRITE allowed"
        )
        ((downPayload.data.toLong >> 24) & 0xff).toInt match {
          case SioRam.Ly68S3200Cmd.QuadWrite.value => {
            currentAddress = (downPayload.data.toLong & 0xffffffL).toInt
            assert(
              !downPayload.last.toBoolean,
              "a write without data is not allowed"
            )

            upQueue.enqueue(
              UpStreamResponse(
                isWrite = true,
                data = dontCareDataPolicy.generateDataValue(),
                last = false,
                missed = false,
                cycleDelay = 2 // fifo roundtrip
              )
            )
            state = "quadWrite"
            stateCounter = 0
          }
          case SioRam.Ly68S3200Cmd.QuadRead.value => {
            currentAddress = (downPayload.data.toLong & 0xffffffL).toInt
            assert(
              !downPayload.last.toBoolean,
              "a read without data is not allowed"
            )
            upQueue.enqueue(
              UpStreamResponse(
                isWrite = true,
                data = dontCareDataPolicy.generateDataValue(),
                last = false,
                missed = false,
                cycleDelay = 0 // fifo roundtrip TODO set to a real value
              )
            )
            state = "quadReadWaitState"
            stateCounter = 0
          }

          case cmd: Int =>
            assert(false, s"cmd=$cmd not supported, data=0x${downPayload.data.toLong.toHexString}")
        }
      }
      case "quadWrite" => {
        assert(downPayload.cmd.toEnum == SioSerializerCc.Cmd.WRITE)
        upQueue.enqueue(
          UpStreamResponse(
            isWrite = true,
            data = dontCareDataPolicy.generateDataValue(),
            last = downPayload.last.toBoolean,
            missed = false,
            cycleDelay = 0
          )
        )
        for (i <- 0 to downPayload.byteSizeMinusOne.toInt) {
          memory(currentAddress.toInt + i) = ((downPayload.data.toLong >> (3 - i) * 8) & 0xff).toInt
        }
        currentAddress += downPayload.byteSizeMinusOne.toInt + 1

        if (downPayload.last.toBoolean) {
          state = "idleQpi"
          stateCounter = 0
        }
      }

      case "quadReadWaitState" => {

        assert(downPayload.cmd.toEnum == SioSerializerCc.Cmd.READ)
        assert(!downPayload.last.toBoolean, "read wait state should not be last")

        assert(downPayload.byteSizeMinusOne.toInt == 3 - 1)

        upQueue.enqueue(
          UpStreamResponse(
            isWrite = false,
            data = dontCareDataPolicy.generateDataValue(),
            last = downPayload.last.toBoolean,
            missed = false,
            cycleDelay = 0
          )
        )
        state = "quadRead"
        stateCounter = 0

      }

      case "quadRead" => {
        assert(downPayload.cmd.toEnum == SioSerializerCc.Cmd.READ)

        var data: Long = 0
        for (i <- 0 to downPayload.byteSizeMinusOne.toInt) {
          val memByte = memory(currentAddress + i).toLong
          assert(memByte >= 0)
          assert(memByte < 0x100)
          data = data << 8 | memByte
        }
        currentAddress += downPayload.byteSizeMinusOne.toInt + 1

        upQueue.enqueue(
          UpStreamResponse(
            isWrite = false,
            data = data,
            last = downPayload.last.toBoolean,
            missed = false,
            cycleDelay = 0
          )
        )
        if (downPayload.last.toBoolean) {
          state = "idleQpi"
          stateCounter = 0
        }
      }

    }
    stateCounter += 1
  }

  var nextUpMessage: Option[UpStreamResponse] = None

  var lastFire = false

  clockDomain.onActiveEdges {

    if (sioBus.upStream.ready.toBoolean || !sioBus.upStream.valid.toBoolean) {
      nextUpMessage = if (upQueue.isEmpty) None else Some(upQueue.dequeue())
    }

    nextUpMessage match {
      case Some(message) =>
        sioBus.upStream.payload.cmd #=
          (if (message.isWrite) SioSerializerCc.Cmd.WRITE else SioSerializerCc.Cmd.READ)
        sioBus.upStream.payload.last #= message.last
        sioBus.upStream.payload.data #= message.data
        sioBus.upStream.payload.missed #= message.missed
        sioBus.upStream.valid #= true

      case None =>
        sioBus.upStream.valid #= false
    }
  }
}
