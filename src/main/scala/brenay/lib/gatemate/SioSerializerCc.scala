package brenay.lib.gatemate

import spinal.core._
import spinal.lib.fsm.{State, StateMachine}
import spinal.lib.io.TriState
// to define IMasterSlave
import spinal.lib.{DownCounter, History, IMasterSlave, master, slave, _} // to define IMasterSlave

object SioSerializerCc {
  object Cmd extends SpinalEnum {
    val WRITE, READ = newElement()

    defaultEncoding = SpinalEnumEncoding("enc")(
      WRITE -> 0x0,
      READ -> 0x1
    )
  }

  val dataBitWidth = 32

  case class Bus() extends Bundle with IMasterSlave {
    val downStream = Stream(DownMessage())
    val upStream = Stream(UpMessage())

    override def asMaster(): Unit = {
      master(this.downStream)
      slave(this.upStream)
    }
  }

  case class DownMessage() extends Bundle {

    /** data, only the bytes of the byteSizeMinusOne care and if cmd is WRITE */
    val data = Bits(dataBitWidth bits)
    val cmd = Cmd()
    val last = Bool()
    val byteSizeMinusOne = UInt(2 bits) // number of bytes to read minus one
    val minFifoSize = UInt(3 bits) // TODO min fifo
  }

  case class UpMessage() extends Bundle {

    /** data, only the bytes of the byteSizeMinusOne in DownMessage care and if cmd is READ */
    val data = Bits(dataBitWidth bits)
    val cmd = Cmd()
    val last = Bool()
    val missed = Bool() // The transaction was starved of fifo data before last=True
  }
}

/** A cross domain serial IO serializer.
  *
  * Beat on the wire are in big endian.
  *
  *  limitations:
  *  - up has to have double the bandwidth that down (because of nextMessage implementation)
  *  - first message can not be of byteMessage = 0
  *  - READ should not be followed by WRITE (risk of out of order response)
  *  - idle state should be maintained after reset for 16 cycles at least (pipeline reset)
  *  - minFifo, to delay cs start, is not implemented
  * @param sioClockDomain the clock domain on the serialized, fast, side
  * @param memPIWidth memory parallel access width
  * @param phyCycleLatency how many clock the phy is pipelined (same in in and out)
  * @param outPipelineLevelBitWidths Levels of the out pipeline with bit mux of each level
  * @param inPipelineLevelBitWidths Levels of the in pipeline with bit mux of each level
  */
class SioSerializerCc(
    val sioClockDomain: ClockDomain,
    val memPIWidth: BitCount = 4 bits,
    val readWordCounterWidth: BitCount = 4 bits,
    val phyCycleLatency: Int = 0,
    val outPipelineLevelBitWidths: Seq[Int] = Seq(3),
    val inPipelineLevelBitWidths: Seq[Int] = Seq(3)
) extends Component {

  import SioSerializerCc._

  assert(isPow2(memPIWidth.value))

  /** The latency in cycle on sio side between read message received to read response */
  def sioReadCycleLatency(): Int = {
    outPipelineLevelBitWidths.length + phyCycleLatency + inPipelineLevelBitWidths.length
  }

  val io = new Bundle {
    val sioBus = slave(Bus())

    /** chip select active high */
    val cs = out Bool ()
    val sio = master(TriState(Bits(memPIWidth)))
  }

  val downFifo = GmStreamFifoCC(DownMessage(), 10, ClockDomain.current, sioClockDomain)
  val upFifo = GmStreamFifoCC(UpMessage(), 10, sioClockDomain, ClockDomain.current)

  io.sioBus.downStream <> downFifo.io.push
  io.sioBus.upStream <> upFifo.io.pop

  val sioDomain = new ClockingArea(sioClockDomain) {

    val outPipeline = new Area {
      val levelBitWidths = outPipelineLevelBitWidths

      assert(levelBitWidths.sum + phyCycleLatency < 16 + 2, "insure fifo reset")
      val levelCount = levelBitWidths.length

      val muxBitWith = levelBitWidths.sum

      val levels = for (i <- 0 until levelCount) yield new Area {

        val cs = Reg(Bool()) init (False)
        val writeEnable =
          Reg(Bool())
        val beatIndex =
          Reg(UInt(muxBitWith bits))
        val data = Reg(Bits((dataBitWidth >> levelBitWidths.take(i + 1).sum) bits))
        val triggerWordRead = RegInit(False)
        val hasLastReadMessage = Reg(Bool()) // initialized by up staying at idleState
      }

      val inputCs = Bool()
      val inputWriteEnable = Reg(Bool())
      val inputBeatIndex = UInt(muxBitWith bits)
      val inputData = Reg(Bits(dataBitWidth bits))
      val inputTriggerWordRead = Bool()
      val inputHasLastReadMessage = Bool()

      val output = levels.last

      if (levelBitWidths(0) == 0) {
        inputData.assignDontCare()
      }

      for ((level, i) <- levels.zipWithIndex) {

        val (data, beatIndex) = if (i == 0) {
          level.cs := inputCs
          level.writeEnable := inputWriteEnable
          level.beatIndex := inputBeatIndex
          level.triggerWordRead := inputTriggerWordRead
          level.hasLastReadMessage := inputHasLastReadMessage
          (inputData, inputBeatIndex)
        } else {
          level.cs := levels(i - 1).cs
          level.writeEnable := levels(i - 1).writeEnable
          level.beatIndex := levels(i - 1).beatIndex
          level.triggerWordRead := levels(i - 1).triggerWordRead
          level.hasLastReadMessage := levels(i - 1).hasLastReadMessage
          (levels(i - 1).data, levels(i - 1).beatIndex)
        }

        val index = UInt(levelBitWidths(i) bits)
        index := beatIndex(
          levelBitWidths.drop(i + 1).sum,
          levelBitWidths(i) bits
        )

        level.data := data(
          index * level.data.getBitsWidth,
          level.data.getBitsWidth bits
        )
      }
    }

    val inPipeline = new Area {
      val levelBitWidths = inPipelineLevelBitWidths

      assert(levelBitWidths.sum + phyCycleLatency < 16 + 2, "insure fifo reset")
      val levelCount = levelBitWidths.length

      val demuxBitWith = levelBitWidths.sum

      val inputBeatIndex = UInt(demuxBitWith bits)
      val inputData = Bits(memPIWidth)
      val inputTriggerWordRead = Bool()
      val inputHasLastReadMessage = Bool()

      assert(demuxBitWith == log2Up(dataBitWidth / memPIWidth.value))

      var demuxedBitWith = 0
      val levels = for (i <- 0 until levelCount) yield new Area {
        val beatIndex =
          Reg(UInt(demuxBitWith - demuxedBitWith bits))
        val data =
          Reg(Vec.fill(1 << levelBitWidths(i))(Bits(((memPIWidth.value << demuxedBitWith) bits))))
        demuxedBitWith += levelBitWidths(i)
        val triggerWordRead = RegInit(False)
        val hasLastReadMessage = Reg(Bool()) // initialized by up staying at idleState
      }

      for ((level, i) <- levels.zipWithIndex) {
        val demuxedData = if (i == 0) {
          inputData
        } else { levels(i - 1).data.asBits }

        val beatIndex = if (i == 0) {
          inputBeatIndex
        } else {
          levels(i - 1).beatIndex >> levelBitWidths(i - 1)
        }

        level.beatIndex := beatIndex

        val index = beatIndex(0, levelBitWidths(i) bits)
        level.data(index) := demuxedData

        if (i == 0) {
          level.triggerWordRead := inputTriggerWordRead
          level.hasLastReadMessage := inputHasLastReadMessage
        } else {
          level.triggerWordRead := levels(i - 1).triggerWordRead
          level.hasLastReadMessage := levels(i - 1).hasLastReadMessage
        }

      }

      val outputData = levels.last.data.asBits
      val outputTriggerWordRead = levels.last.triggerWordRead
      val outputHasLastReadMessage = levels.last.hasLastReadMessage

    }

    // ------ compensation for phy latency --------
    inPipeline.inputTriggerWordRead := History(
      outPipeline.output.triggerWordRead,
      phyCycleLatency + 1,
      when = True,
      init = False
    ).last
    inPipeline.inputHasLastReadMessage := History(
      outPipeline.output.hasLastReadMessage,
      phyCycleLatency + 1
    ).last
    inPipeline.inputBeatIndex := History(
      outPipeline.output.beatIndex,
      phyCycleLatency + 1
    ).last

    val fsm = new StateMachine {
      // seems in average better with only 4 states without boot, but yosys infer a one hot signal so perhaps not very different
      setEncoding(
        binarySequential
      )
      // setEncoding(binaryOneHot) // worst with 1 outlier at 110 MHz

      // ---------- beat counter ---------------

      val beatCounter = DownCounter(dataBitWidth / memPIWidth.value)
      beatCounter.decrement()
      outPipeline.inputBeatIndex := beatCounter.value

      val beatCounterIsZero = Reg(Bool())
      beatCounterIsZero := beatCounter === 1

      assert(memPIWidth.value > 1)

      // ----------- connect out and in pipelines to ios  -------------
      io.cs := outPipeline.output.cs
      io.sio.writeEnable := outPipeline.output.writeEnable
      io.sio.write := outPipeline.output.data

      inPipeline.inputData := io.sio.read

      // ------- next down message management -------------
      val nextDownMessageValid = Reg(Bool()) init (False)
      val nextDownMessage = Reg(DownMessage())
      val nextDownMessageBeatIndex = Reg(UInt(beatCounter.getBitsWidth bits))
      val downPopFifoReady = Reg(Bool()) init (False)

      downFifo.io.pop.ready := downPopFifoReady
      when(nextDownMessageValid) {
        downPopFifoReady := False
      } otherwise {
        nextDownMessageBeatIndex := (downFifo.io.pop.payload.byteSizeMinusOne << log2Up(
          8 / memPIWidth.value
        )) + 1

        when(downFifo.io.pop.fire) {
          nextDownMessage := downFifo.io.pop.payload
          nextDownMessageValid := True

          downPopFifoReady := False
        } otherwise {
          // We are ok having only half the bandwidth because the the serializer
          // is designed to have up having at least twice the bandwidth of sio
          downPopFifoReady := True
        }
      }

      // ------- next up message management -------------
      val nextUpMessage = Reg(UpMessage())
      val nextUpMessageValid = Reg(Bool()) init (False)

      // the in message fifo should always be ready

      upFifo.io.push.payload := nextUpMessage
      upFifo.io.push.valid := nextUpMessageValid
      when(nextUpMessageValid) {
        // no problem of half bandwidth because the clock domain is never as fast
        nextUpMessageValid := False
      }

      val lastReadMessageSent = Reg(Bool()) init (True)
      when(inPipeline.outputTriggerWordRead) {
        nextUpMessage.data := inPipeline.outputData
        nextUpMessageValid := True
        nextUpMessage.last := inPipeline.outputHasLastReadMessage

        when(inPipeline.outputHasLastReadMessage) {
          lastReadMessageSent := True
        }
      }

      // --------- default values ----------------
      outPipeline.inputCs := False
      outPipeline.inputTriggerWordRead := False
      outPipeline.inputHasLastReadMessage := False

      // -------------- state machine --------------
      val idleState: State = makeInstantEntry()

      val hasLastMessage = Reg(Bool())
      val previousMessageWasRead = Reg(Bool()) init (False)

      idleState.whenIsActive {

        beatCounter := (nextDownMessage.byteSizeMinusOne << log2Up(8 / memPIWidth.value)) + 1
        beatCounterIsZero := False
        outPipeline.inputData := nextDownMessage.data
        hasLastMessage := False
        outPipeline.inputTriggerWordRead := False
        outPipeline.inputHasLastReadMessage := False
        previousMessageWasRead := False

        when(lastReadMessageSent) {
          when(nextDownMessageValid) {
            nextDownMessageValid := False

            nextUpMessage.cmd := nextDownMessage.cmd
            nextUpMessage.last := nextDownMessage.last

            outPipeline.inputWriteEnable := True
            hasLastMessage := nextDownMessage.last
            nextUpMessageValid := True

            assert(nextDownMessage.cmd === Cmd.WRITE, "first message should always be a WRITE")
            nextUpMessage.missed := nextDownMessage.cmd =/= Cmd.WRITE
            goto(transferState)
          }
        }
      }

      val transferState: State = new State {
        whenIsActive {
          outPipeline.inputCs := True

          when(beatCounterIsZero) {
            beatCounter := nextDownMessageBeatIndex

            outPipeline.inputData := nextDownMessage.data
            nextUpMessage.cmd := nextDownMessage.cmd
            hasLastMessage := nextDownMessage.last
            outPipeline.inputTriggerWordRead := previousMessageWasRead
            outPipeline.inputHasLastReadMessage := hasLastMessage
            previousMessageWasRead := False
            when(hasLastMessage) {
              hasLastMessage := False
              goto(idleState)
            } elsewhen (!nextDownMessageValid) {
              outPipeline.inputWriteEnable := True
              goto(flushMissedState)
            } elsewhen (nextDownMessage.cmd === Cmd.WRITE) {
              nextUpMessageValid := True
              nextUpMessage.last := nextDownMessage.last
              nextDownMessageValid := False
              outPipeline.inputWriteEnable := True
            } otherwise {
              assert(nextDownMessage.cmd === Cmd.READ)
              nextUpMessageValid := False
              nextDownMessageValid := False
              outPipeline.inputWriteEnable := False
              previousMessageWasRead := True
            }
          }
        }
      }

      val flushMissedState: State = new State {
        whenIsActive {
          when(lastReadMessageSent) {

            assert(upFifo.io.push.ready === True, "by design the up buffer can not be full")
            nextUpMessage.cmd := nextDownMessage.cmd
            nextUpMessage.data := nextDownMessage.data
            nextUpMessage.last := nextDownMessage.last
            nextUpMessage.missed := True

            nextUpMessageValid := nextDownMessageValid
            when(nextDownMessageValid) {

              // up fifo can always store all missed messages

              nextDownMessageValid := False

              when(nextDownMessage.last) {
                goto(idleState)
              }
            }

          }
        }
      }
    }
  }
}
