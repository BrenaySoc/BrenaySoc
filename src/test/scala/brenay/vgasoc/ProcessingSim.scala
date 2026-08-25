package brenay.vgasoc

import spinal.core.sim.{SimMemPimper, _}
import spinal.core.{SpinalConfig, _}
import spinal.lib.com.uart.Uart
import spinal.lib.master

import java.io.File

import brenay.lib.SimWorkspaceNaming
import brenay.lib.gatemate.SioSerializerCc
import org.scalatest.funsuite.AnyFunSuite
import vexiiriscv.regfile.RegFilePlugin

class ProcessingSim extends AnyFunSuite with SimWorkspaceNaming {

  // Run small code in internalRam to write to the UART TX, check what we send something.
  ignore("asm uart_tx") {
    // TODO consolidate with mem test and use ram model

    println(surferCommandString)

    val p = new VgaSocParam()

    p.withMcuJtag = false
    p.withGraphic = true
    p.withSpiFlash = false

    // TODO make build
    p.ramElf = Some(new File("src/test/asm/uart_tx/build/uart_tx.elf"))
    p.legalize()

    val fClock = 25 MHz
    val conf = SpinalConfig(defaultClockDomainFrequency = FixedFrequency(fClock))
    val sim = SimConfig
      .withConfig(conf)
      .withFstWave
      .workspaceName(classAndTestName)
      .addRtl("src/main/verilog/gatemate/cells_sim.v")
    sim
      .compile({
        new Processing(p)
      })
      .doSim { dut =>
        new Ly68S3200RamFake(dut.clockDomain, dut.io.psramSioBus)
        dut.io.uart.rxd #= true
        if (p.withGraphic) dut.io.vgaStream.ready #= true

        // Fork a process to generate the reset and the clock on the dut
        dut.clockDomain.forkStimulus(fClock)
        SimTimeout(10 * 1000 * 1000 * 1000)

        var cycleCount = 0

        while (dut.io.uart.txd.toBoolean) {
          dut.clockDomain.waitActiveEdge()
          cycleCount += 1
        }

        // we expect the uart to send something
        assert(cycleCount > 150)
        assert(cycleCount < 250)
      }
  }

  // Run small code in internalRam to write to the UART TX, check what we send something.
  test("asm_psram_access") {

    println(surferCommandString)

    val p = new VgaSocParam()

    // Cache still do not work because write is not a continuous burst
    // and SioRam doesn't support this.
    p.vexii.fetchL1Enable = true
    p.vexii.lsuL1Enable = true

    p.withMcuJtag = false

    p.vexii.withGShare = false
    p.vexii.withBtb = false

    p.withGraphic = false
    p.withSpiFlash = false

    // TODO make build
    p.ramElf = Some(new File("src/test/asm/mem_access/build/mem_access.elf"))
    p.legalize()

    val useSioRamModel = true
    val registerIos = false

    val useRamFake = !useSioRamModel

    val fClock = 25 MHz
    val conf = SpinalConfig(defaultClockDomainFrequency = FixedFrequency(fClock))
    val sim = SimConfig
      .withConfig(conf)
      .withFstWave
      .workspaceName(classAndTestName)
      .normalOptimisation
      .addRtl("src/main/verilog/gatemate/cells_sim.v")
    sim
      .compile(
        new Component {
          val io = new Bundle {
            val uart = master(
              Uart(ctsGen = p.uartCtrlGenerics.ctsGen, rtsGen = p.uartCtrlGenerics.rtsGen)
            )
            val sioBus = useRamFake generate master(SioSerializerCc.Bus())
            val ramModelClk = useSioRamModel generate (in Bool ())
            val ramModelRst = useSioRamModel generate (in Bool ())
          }

          val processing = new Processing(p)
          processing.io.simPublic

          val sioRamModelClockDomain = useSioRamModel generate
            new ClockDomain(io.ramModelClk, io.ramModelRst)

          val serializer = useSioRamModel generate new SioSerializerCc(
            sioRamModelClockDomain,
            phyCycleLatency = registerIos.toInt * 2
          )

          if (useSioRamModel) {
            processing.io.psramSioBus <> serializer.io.sioBus
          }
          if (useRamFake) {
            processing.io.psramSioBus <> io.sioBus
          }

          io.uart <> processing.io.uart

          val sioRamClockingArea =
            useSioRamModel generate new ClockingArea(sioRamModelClockDomain) {

              val model = new Ly68S3200Model(
                meaningfulOperationAsserted = true,
                dangerousOperationAsserted = true
              )
              model.io.simPublic

              if (registerIos) {
                model.io.cs := RegNext(serializer.io.cs) init (False)
                model.io.sio <> serializer.io.sio.stage()
              } else {
                model.io.cs := serializer.io.cs
                model.io.sio <> serializer.io.sio
              }
            }
        }
      )
      .doSim { dut =>
        useRamFake generate new Ly68S3200RamFake(dut.clockDomain, dut.io.sioBus)

        dut.io.uart.rxd #= true

        // Fork a process to generate the reset and the clock on the dut
        dut.clockDomain.forkStimulus(fClock)
        if (useSioRamModel) {
          ClockDomain(dut.io.ramModelClk, dut.io.ramModelRst)
            .forkStimulus(fClock * 4)
        }
        SimTimeout((fClock.toTime.toDouble * 1e12).toLong * 1000 * 1000)

        dut.clockDomain.waitActiveEdge(10)

        val regFile = dut.processing.mcu.core.plugins.collectFirst { case p: RegFilePlugin =>
          p.logic.regfile.fpga.asMem.ram
        }.get

        val t2Index = 7

        // Test an number of time
        for (_ <- 0 until 2) {
          var t2Char = ' '
          do {
            dut.clockDomain.waitActiveEdge()
            t2Char = regFile.getBigInt(t2Index).toChar
          } while (t2Char != 'P')

          do {
            dut.clockDomain.waitActiveEdge()
            t2Char = regFile.getBigInt(t2Index).toChar
          } while (t2Char != 'P' && t2Char != 'F')

          assert(t2Char == 'P')
        }

        dut.clockDomain.waitActiveEdge(100)
      }
  }

}
