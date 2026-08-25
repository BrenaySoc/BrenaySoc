package brenay.vgasoc

import spinal.core.fiber.Fiber
import spinal.core.sim._
import spinal.core.{Component, _}
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink._
import spinal.lib.bus.tilelink.fabric.MasterBus
import spinal.lib.bus.tilelink.sim.MasterTester
import spinal.lib.sim.SparseMemory
import spinal.lib.{StreamPipe, master}

import brenay.lib.SimWorkspaceNaming
import brenay.lib.gatemate.SioSerializerCc
import org.scalatest.ParallelTestExecution
import org.scalatest.funsuite.AnyFunSuite

import tilelink._
import tilelink.fabric.sim._

class SioRamTester extends AnyFunSuite with SimWorkspaceNaming with ParallelTestExecution {
  def doTest(
      withBurst: Boolean,
      masterCount: Int,
      useSioRamModel: Boolean,
      registerIos: Boolean = false // io registering is not needed with current implementation
  ): Unit = {

    val useRamFake = !useSioRamModel

    println(surferCommandString)

    val tester = new TilelinkTester(
      simConfig = SimConfig
        .workspaceName(classAndTestName)
        .withFstWave
        .addRtl("src/main/verilog/gatemate/cells_sim.v"),
      cGen = {
        val dut = new Component {
          val io = new Bundle {
            val sioBus = useRamFake generate master(SioSerializerCc.Bus())
            val ramModelClk = useSioRamModel generate (in Bool ())
            val ramModelRst = useSioRamModel generate (in Bool ())
          }

          val maxByteCount = if (withBurst) 64 else 4

          val m0 = new MasterBus(
            M2sParameters(
              addressWidth = 32,
              dataWidth = 32,
              masters = List.tabulate(masterCount)(mid =>
                M2sAgent(
                  name = null,
                  mapping = List(
                    M2sSource(
                      id = SizeMapping(mid * 1, 1),
                      emits = M2sTransfers(
                        get = SizeRange(4, maxByteCount),
                        putFull = SizeRange(4, maxByteCount)
                        // TODO other tilelink-UH mandatory Opcode are not implemented
                        // putPartial = SizeRange(1, maxByteCount)
                      )
                    )
                  )
                )
              )
            )
          )

          // Note that the testbench automatically use val ordering = Flow(OrderingCmd(p.sizeBytes))
          // from the SioRam component to figure out the global memory ordering
          val ram = new SioRamFiber()
          ram.up at 0x0 of m0.node
          ram.up.setUpConnection(a = StreamPipe.FULL)

          val sioRamModelClockDomain = useSioRamModel generate
            new ClockDomain(io.ramModelClk, io.ramModelRst)

          val serializer = useSioRamModel generate new SioSerializerCc(
            sioRamModelClockDomain,
            phyCycleLatency = registerIos.toInt * 2,
            outPipelineLevelBitWidths = Seq(2, 1, 0),
            inPipelineLevelBitWidths = Seq(2, 1, 0)
          )

          val sioRamClockingArea =
            useSioRamModel generate new ClockingArea(sioRamModelClockDomain) {

              val model = new Ly68S3200Model(
                meaningfulOperationAsserted = true,
                dangerousOperationAsserted = true
              )

              if (registerIos) {
                model.io.cs := RegNext(serializer.io.cs) init (False)
                model.io.sio <> serializer.io.sio.stage()
              } else {
                model.io.cs := serializer.io.cs
                model.io.sio <> serializer.io.sio
              }
            }

          val patcher = Fiber patch new Area {
            if (useRamFake) {
              io.sioBus <> ram.thread.controller.io.sioBus
            }
            if (useSioRamModel) {
              serializer.io.sioBus <> ram.thread.controller.io.sioBus
            }
          }
        }
        dut
      }
    )

    val clockRatio = 4.0

    if (useSioRamModel) {
      tester.forkNodeStimuli = { (nodes, dut) =>
        {
          val fastPeriod = 10

          ClockDomain(dut.io.ramModelClk, dut.io.ramModelRst)
            .forkStimulus(fastPeriod)

          val cds = nodes.map(_.clockDomain).distinct
          cds.foreach(cd => {
            cd.forkStimulus((fastPeriod * clockRatio).round.toLong)
          })
        }
      }
    }

    tester.doSim("test") { tb =>
      // ---------- initialize the ram to the same value as the tester ram model -----------------
      val initialRandomMem = SparseMemory(seed = 42)
      if (useSioRamModel) {
        for (address <- 0 until 1 << 24) {
          tb.dut.sioRamClockingArea.model.mem
            .setBigInt(address, initialRandomMem.read(address).toInt & 0xff)
        }
        if (false) { // use this to visualize a memory address value
          tb.dut.clockDomain.waitSampling(1)
          var address = 0x03b894
          for (i <- 0 until 4) {

            println(s"ref mem=${initialRandomMem.read(address).toHexString}")
            println(
              s"model mem=${tb.dut.sioRamClockingArea.model.mem.getBigInt(address).toInt.toHexString}"
            )
            address += i
          }
        }

      } else {
        new Ly68S3200RamFake(
          tb.dut.clockDomain,
          tb.dut.io.sioBus,
          memory = {
            Array.tabulate(1 << 24)(address => initialRandomMem.read(address).toInt & 0xff)
          }
        )
      }

      if (withBurst) {
        // TODO burst mode still not support stall
        tb.mastersStuff.foreach(_.agent.driver.driver.noStall())
        tb.slavesStuff.foreach(_.model.driver.driver.noStall())
      } else {
        periodically(1000) {
          tb.mastersStuff.foreach(_.agent.driver.driver.randomizeStallRate())
          tb.slavesStuff.foreach(_.model.driver.driver.randomizeStallRate())
        }
      }

      val testers =
        (tb.masterSpecs, tb.mastersStuff).zipped.map((s, t) => new MasterTester(s, t.agent))
      //      val globalLock = Some(SimMutex()) //for test only
      val globalLock = Option.empty[SimMutex]
      testers.foreach(_.startPerSource(10000, globalLock))
      testers.foreach(_.join())
      tb.waitCheckers()
      tb.assertCoverage()
    }

    tester.checkErrors()
  }

  test("single master with burst and ram fake") {
    doTest(
      withBurst = true,
      masterCount = 1,
      useSioRamModel = false
    )
  }

  test("4 masters without burst and ram fake") {
    // We only test multi masters with burst because the tester interleave
    // bursts what is not allowed by tilelink UH.
    doTest(
      withBurst = false,
      masterCount = 4,
      useSioRamModel = false
    )
  }

  test("single master without burst and ram model") {
    doTest(
      withBurst = false,
      masterCount = 1,
      useSioRamModel = true
    )
  }

  test("single master with burst and ram model") {
    doTest(
      withBurst = true,
      masterCount = 1,
      useSioRamModel = true
    )
  }
}
