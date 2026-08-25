package brenay.lib.gatemate

import spinal.core._
import spinal.core.sim._
import spinal.lib.io.TriState
import spinal.lib.sim.StreamMonitor
import spinal.lib.{master, slave}

import scala.collection.mutable.Queue

import brenay.lib.SimWorkspaceNaming
import org.scalatest.ParallelTestExecution
import org.scalatest.funsuite.AnyFunSuite

// TODO fix with (0, 2, 1, 0) pipeline

class SioSerializerCcSim extends AnyFunSuite with SimWorkspaceNaming with ParallelTestExecution {

  class SioSerializerCcTestBench(val beatBitCount: Int) extends Component {
    val io = new Bundle {
      val sioClk = in Bool ()
      val sioRst = in Bool ()

      val sioBus = slave(SioSerializerCc.Bus())

      val cs = out Bool ()
      val sio = master(TriState(Bits(beatBitCount bits)))
    }

    val sioClockDomain = new ClockDomain(io.sioClk, io.sioRst)

    val serializer = new SioSerializerCc(sioClockDomain)

    io.cs := serializer.io.cs
    io.sio <> serializer.io.sio

    serializer.io.sioBus <> io.sioBus

    // ------------ case classes for transactions made of phases -------------------

    case class TransactionMessage(
        val cmd: SioSerializerCc.Cmd.E,
        val byteSizeMinusOne: Int,
        val data: Long = -1, // if negative, randomize for READ
        val last: Boolean = false,
        val missed: Boolean = false
    ) {

      assert(byteSizeMinusOne < 4)
      if (cmd == SioSerializerCc.Cmd.WRITE) {
        assert(data >= 0)
      }
    }

    case class SioPhase(val cmd: SioSerializerCc.Cmd.E) {}

    case class Transaction(val messages: Seq[TransactionMessage]) {

      def assertEqualTo(expectedTransaction: Transaction): Unit = {
        assert(
          expectedTransaction.messages.length == this.messages.length,
          ", not the same message length in transaction"
        )
        for (
          ((expected, actual), i) <- (expectedTransaction.messages zip this.messages).zipWithIndex
        ) {

          assert(actual.cmd == expected.cmd, s", cmd different at i=$i")
          assert(
            actual.byteSizeMinusOne == expected.byteSizeMinusOne,
            s", byteSizeMinusOne different at i=$i"
          )
          assert(
            actual.last == expected.last,
            s", last different at i=$i"
          )
          val mask = (1L << (actual.byteSizeMinusOne + 1)) - 1

          // only the bytes of the size cares
          val validActualData = actual.data & mask
          val validExpectedData = expected.data & mask
          assert(
            validActualData == validExpectedData,
            s", data 0x${validActualData} != 0x${validExpectedData} at i=$i"
          )
        }
      }
    }

    class TransactionDriver(val dut: SioSerializerCcTestBench, clockDomain: ClockDomain) {

      var receivedMessages = Queue[TransactionMessage]()
      dut.io.sioBus.upStream.ready #= true
      val upDriver = StreamMonitor(dut.io.sioBus.upStream, dut.clockDomain) { payload =>
        receivedMessages.enqueue(
          TransactionMessage(
            payload.cmd.toEnum,
            byteSizeMinusOne = -1,
            payload.data.toLong,
            payload.last.toBoolean,
            payload.missed.toBoolean
          )
        )
      }

      def driveTransaction(transaction: Transaction): Unit = {
        dut.io.sioBus.downStream.valid #= false
        for ((phase, i) <- transaction.messages.zipWithIndex) {
          dut.io.sioBus.downStream.payload.cmd #= phase.cmd
          if (phase.cmd == SioSerializerCc.Cmd.READ && phase.data < 0) {
            dut.io.sioBus.downStream.payload.data.randomize
          } else {
            dut.io.sioBus.downStream.payload.data #= phase.data
          }
          dut.io.sioBus.downStream.payload.last #= phase.last
          dut.io.sioBus.downStream.payload.byteSizeMinusOne #= phase.byteSizeMinusOne
          dut.io.sioBus.downStream.payload.minFifoSize.randomize
          dut.io.sioBus.downStream.valid #= true
          do {
            clockDomain.waitActiveEdge()
          } while (!dut.io.sioBus.downStream.ready.toBoolean)
        }
        dut.io.sioBus.downStream.valid #= false
      }

      def extractReceivedTransaction(downTransaction: Transaction): Transaction = {
        while (receivedMessages.length < downTransaction.messages.length) {
          dut.clockDomain.waitActiveEdge()
        }
        Transaction(for ((downMessage, i) <- downTransaction.messages.zipWithIndex) yield {
          val upMessage = receivedMessages.dequeue()
          assert(upMessage.cmd == downMessage.cmd, s", cmd invalid at i=$i")
          assert(upMessage.last == downMessage.last, s", last invalid at i=$i")

          TransactionMessage(
            upMessage.cmd,
            downMessage.byteSizeMinusOne,
            downMessage.cmd match {
              case SioSerializerCc.Cmd.WRITE => downMessage.data
              case SioSerializerCc.Cmd.READ  => upMessage.data
            },
            upMessage.last,
            upMessage.missed
          )
        })
      }
    }

    class SioSlaveFake(dut: SioSerializerCcTestBench) {
      private var messageIndex: Int = 0
      private var sioBeatCounter: Int = 0

      var expectedTransaction: Option[Transaction] = None
      val receivedMessages = Queue[TransactionMessage]()

      /** Wait what we received the the data on the wire for maxSioClockCount.
        * Return if we have received all the data or not.
        */
      def waitTransactionTerminated(maxSioClockCount: Int): Boolean = {
        var clockCount = 0
        while (receivedMessages.length < expectedTransaction.get.messages.length) {
          dut.sioClockDomain.waitActiveEdge()
          clockCount += 1
          if (clockCount > maxSioClockCount) return false
        }
        return true
      }

      def extractReceivedTransaction(): Transaction = {
        val t = Transaction(receivedMessages.toVector)
        receivedMessages.clear()
        t
      }

      private var data: Long = 0

      dut.sioClockDomain.onActiveEdges({
        dut.io.sio.read.randomize()

        var contextString =
          s", at state=state, messageIndex=$messageIndex, sioBeatCounter=$sioBeatCounter"

        if (!dut.io.cs.toBoolean) {
          messageIndex = 0
          sioBeatCounter = 0
          data = 0
        } else {

          assert(
            messageIndex < expectedTransaction.get.messages.length,
            "unexpected message" + contextString
          )
          val expectedMessage = expectedTransaction.get.messages(messageIndex)
          contextString += s", expectedMessage=$expectedMessage"

          if (messageIndex == 0) {
            assert(
              expectedMessage.cmd == SioSerializerCc.Cmd.WRITE,
              ", transaction should start with a write" + contextString
            )
          }

          assert(
            dut.io.sio.writeEnable.toBoolean == (expectedMessage.cmd match {
              case SioSerializerCc.Cmd.WRITE => true
              case SioSerializerCc.Cmd.READ  => false
            }),
            "writeEnable invalid" + contextString
          )

          val beatData = expectedMessage.cmd match {
            case SioSerializerCc.Cmd.WRITE => dut.io.sio.write.toLong
            case SioSerializerCc.Cmd.READ  => dut.io.sio.read.toLong
          }
          data = data << 4 | beatData

          if (sioBeatCounter < (expectedMessage.byteSizeMinusOne + 1) * 2 - 1) {
            sioBeatCounter += 1
          } else {

            receivedMessages.enqueue(
              TransactionMessage(
                expectedMessage.cmd,
                expectedMessage.byteSizeMinusOne,
                data,
                expectedMessage.last,
                missed = false
              )
            )
            sioBeatCounter = 0
            data = 0
            messageIndex += 1

            if (messageIndex < expectedTransaction.get.messages.length) {
              val nextMessage = expectedTransaction.get.messages(messageIndex)

              assert(
                !(expectedMessage.cmd == SioSerializerCc.Cmd.READ && nextMessage.cmd == SioSerializerCc.Cmd.WRITE),
                ", write after read not allowed" + contextString
              )
            }
          }
        }

      })
    }
  }

  for (busToSioClockRatio <- Seq(0.31416, 1, 2.678, 4)) {
    test(s"Test with clockRation=$busToSioClockRatio") {

      println(surferCommandString)

      val conf = SpinalConfig()
      val sim = SimConfig
        .withConfig(conf)
        .withFstWave
        .workspaceName(classAndTestName)
        .addRtl("src/main/verilog/gatemate/cells_sim.v")

      sim
        .compile({ new SioSerializerCcTestBench(4) })
        .doSim { dut =>
          // Deterministic seeding of simRandom with `busToSioClockRatio`.
          simRandom.setSeed(simRandom.nextInt + (busToSioClockRatio * 1000).toInt)

          SimTimeout(1000L * 1000 * 1000)
          val push = dut.io.sioBus.downStream
          val pop = dut.io.sioBus.upStream

          push.valid #= false
          push.payload.randomize()
          pop.ready #= false

          // synchronize reset between clocks
          val sioClockDomain = ClockDomain(dut.io.sioClk, dut.io.sioRst)
          val sioClockPeriod = 1000
          val busClockPeriod = (sioClockPeriod * busToSioClockRatio).toLong
          sioClockDomain.forkStimulus(
            sioClockPeriod,
            sleepDuration = (100 * busToSioClockRatio).toInt,
            resetCycles = (16 * 10 * busToSioClockRatio).toInt
          )
          dut.clockDomain.forkStimulus(
            busClockPeriod,
            sleepDuration = 100,
            resetCycles = (16 * 10).toInt
          )

          val slave = new dut.SioSlaveFake(dut)
          val driver = new dut.TransactionDriver(dut, dut.clockDomain)

          sioClockDomain.waitActiveEdge()
          dut.clockDomain.waitActiveEdge()

          // Do a number of transactions
          for (_ <- 0 until 100) {

            val readCount =
              if (simRandom.nextBoolean) 0 else simRandom.nextInt(64) // 50 % with read
            val readMessages = for (i <- 0 until readCount) yield {
              dut.TransactionMessage(
                SioSerializerCc.Cmd.READ,
                3,
                simRandom.nextLong() & 0xffffffffL,
                last = i == readCount - 1
              )
            }

            val writeCount = simRandom.nextInt(64).max(1)
            val inTransaction = dut.Transaction(
              (for (i <- 0 until writeCount) yield {
                dut.TransactionMessage(
                  SioSerializerCc.Cmd.WRITE,
                  3,
                  simRandom.nextLong() & 0xffffffffL,
                  last = readCount == 0 && i == writeCount - 1
                )
              }) ++ readMessages
            )

            withClue(inTransaction.toString()) {
              slave.expectedTransaction = Some(inTransaction)
              driver.driveTransaction(inTransaction)

              val outTransaction = driver.extractReceivedTransaction(inTransaction)

              assert(
                slave
                  .waitTransactionTerminated(maxSioClockCount =
                    dut.serializer.sioReadCycleLatency()
                      + (1 / busToSioClockRatio).toInt * 3 // when sio slower that bus this is necessary
                  )
              )
              val actualTransaction = slave.extractReceivedTransaction()

              actualTransaction.assertEqualTo(outTransaction)
              for (message <- outTransaction.messages) {
                assert(!message.missed)
              }
            }

            dut.clockDomain.waitActiveEdge(simRandom.nextInt(3))
          }
        }
    }
  }
}
