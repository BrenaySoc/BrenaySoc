package brenay.lib.blackbox.cologne.gatemate

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.{ScoreboardInOrder, StreamDriver, StreamMonitor, StreamReadyRandomizer}
import spinal.lib.{StreamFifoCC, master, slave}

import brenay.lib.SimWorkspaceNaming
import org.scalatest.ParallelTestExecution
import org.scalatest.funsuite.AnyFunSuite

class GmStreamFifoCCTestBench(width: Int, depth: Int, useSpinalFifo: Boolean) extends Component {
  val io = new Bundle {
    val push = slave Stream (UInt(width bits))
    val pop = master Stream (UInt(width bits))
  }

  val pushClockDomain = ClockDomain.current
  val popClockDomain = ClockDomain.current

  if (useSpinalFifo) {
    val fifo = StreamFifoCC(UInt(width bits), depth, pushClockDomain, popClockDomain)
    io.push <> fifo.io.push
    io.pop <> fifo.io.pop

  } else {
    val fifo =
      brenay.lib.gatemate.GmStreamFifoCC(UInt(width bits), depth, pushClockDomain, popClockDomain)
    io.push <> fifo.io.push
    io.pop <> fifo.io.pop
  }

}

class GmStreamFifoCCSim extends AnyFunSuite with SimWorkspaceNaming with ParallelTestExecution {

  // Allows to use spinal lib FIFO to test the test.
  val useSpinalFifo = false

  val rand = new scala.util.Random(System.getenv("SPINAL_SIM_SEED").toInt)

  for (width <- Set(1, 2, 8, 10, 16, 20, 24, 40, 41, 79, 81, 256)) {
    val depth = if (useSpinalFifo) {
      // spinalFifo only support power of 2
      1 << rand.nextInt(10).max(1)
    } else { rand.nextInt(1024).max(1) }
    test(s"Simple same clock with=$width, depth=$depth") {

      val conf = SpinalConfig()
      val sim = SimConfig
        .withConfig(conf)
        .withFstWave
        .workspaceName(classAndTestName)
        .addRtl("src/main/verilog/gatemate/cells_sim.v")
      sim
        .compile(new GmStreamFifoCCTestBench(width, depth, useSpinalFifo))
        .doSim { dut =>
          // Deterministic seeding of simRandom with `width`.
          simRandom.setSeed(simRandom.nextInt + width)

          val push = dut.io.push
          val pop = dut.io.pop
          push.valid #= false
          push.payload.randomize
          pop.ready #= false

          dut.clockDomain.forkStimulus(period = 10)

          for (_ <- 0 until 20) {

            val scoreboard = ScoreboardInOrder[BigInt]()

            var continueToDrive = true

            // drive random data and add pushed data to scoreboard
            StreamDriver(dut.io.push, dut.pushClockDomain) { payload =>
              payload.randomize()
              continueToDrive
            }
            StreamMonitor(dut.io.push, dut.pushClockDomain) { payload =>
              scoreboard.pushRef(payload.toBigInt)
            }

            // randomize ready on the output and add popped data to scoreboard
            StreamReadyRandomizer(pop, dut.popClockDomain)
            StreamMonitor(pop, dut.popClockDomain) { payload =>
              scoreboard.pushDut(payload.toBigInt)
            }

            val matchCount = rand.nextInt(1000)
            dut.clockDomain.waitActiveEdgeWhere(scoreboard.matches == matchCount)
            continueToDrive = false
            while (pop.valid.toBoolean) {
              dut.popClockDomain.waitActiveEdge()
            }
            dut.popClockDomain.waitActiveEdge(100)
          }
        }
    }
  }
}
