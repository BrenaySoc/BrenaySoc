package brenay.vgasoc.board.olimex.gatemate_a1_evb

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib.Counter

import java.io.File

import brenay.lib.blackbox.cologne.gatemate.{IoVoltage, CcUsrRstn}
import brenay.lib.gatemate.SioSerializerCc
import brenay.vgasoc.{Processing, VgaSink, VgaSocParam}
import vexiiriscv.fetch.PcPlugin

import VgaSoc.{DebugBusConfig, DebugWiring}

// TODO rename
object VgaSoc {

  /** Style a debug wiring for the jtag of the mcu */
  sealed abstract class DebugWiring {}

  object DebugWiring {

    /** Use an external jtag probe like J-Link on Bank EB1 connector */
    case object BankEb1 extends DebugWiring {}

    /** Use the fpga config jtag pin turned as io after config
      * When using this extra pins are added. They should be removed
      * from constraints.ccf because having them deactivate the jtag configuration
      * of the bitstream.
      */
    case object FpgaJtag extends DebugWiring {}

  }

  /** Type of configuration for the debug IOs on bank NA1 */
  sealed abstract class DebugBusConfig() {}

  object DebugBusConfig {

    /** All bits to zeros */
    case object Zeros extends DebugBusConfig

    /** 16 bit free running counter at processing frequency, useful to test analyser connection */
    case object FreeRunningCounter extends DebugBusConfig

    /** Resets, clocks and jtga signals, useful for reset sequence debug */
    case object JtagAndResets extends DebugBusConfig

    /** PSRAM signal mirror and fifo control signals, with UART tx */
    case object PsramDebug extends DebugBusConfig
  }
}

class VgaSoc(
    ramElfPathName: String,
    additionalIsa: Seq[String] = Nil,
    debugWiring: DebugWiring = DebugWiring.FpgaJtag
) extends Component {

  var p = new VgaSocParam()
  p.vexii.extension.add(additionalIsa: _*)

  p.ramElf = Some(new File(ramElfPathName))

  // TODO for some reason, when P&R with graphic the core stop at h3C, understand why
  p.withGraphic = false

  /* change the default config here
  p.withMcu = true
  p.withMcuJtag = true

  p.vexii.fetchL1Enable = true
  p.vexii.lsuL1Enable = true
  p.vexii.lsuHardwarePrefetch = "none" // "rpt" // use 2 RAM_HALF

  p.vexii.withGShare = false
  p.vexii.withBtb = false
   */

  // Change this to select one of the different debug signal wiring to the 16 bit debug bus.
  val debugBusConfig: DebugBusConfig = DebugBusConfig.PsramDebug

  p.legalize()

  val useFpgaJtagIo = (debugWiring == DebugWiring.FpgaJtag && p.withMcuJtag)

  val io = new Bundle {

    val CLK0 = in Bool ()
    val FPGA_BUT = in Bool ()

    // -------- mcu ios --------
    val GPIO12_DBG_UART_TX = in Bool ()
    val GPIO13_DBG_UART_RX = out Bool ()

    // for using FPGA config jtag on RP2040
    val JTAG_TCK = useFpgaJtagIo generate (in Bool ())
    val JTAG_TMS = useFpgaJtagIo generate (in Bool ())
    val JTAG_TDI = useFpgaJtagIo generate (in Bool ())
    val JTAG_TDO = useFpgaJtagIo generate (out Bool ())

    // for external jtag probe
    val EB_A = inout(Analog(Bits(9 bits)))

    // ------- ext mems -------

    val PSRAM_CSN = out Bool ()
    val PSRAM_SCLK = out Bool ()
    val PSRAM_DATA = inout(Analog(Bits(8 bits)))

    // ------- graphic --------
    val VGA_HSync = out Bool ()
    val VGA_VSync = out Bool ()

    val VGA_Red = out UInt (4 bits)
    val VGA_Green = out UInt (4 bits)
    val VGA_Blue = out UInt (4 bits)

    val NA_A = inout(Analog(Bits(9 bits)))
    val NA_B = inout(Analog(Bits(9 bits)))
  }
  noIoPrefix()

  // socCtrl will provide clocking, reset controllers and debugModule (through JTAG) to our SoC
  val socCtrl = new SocCtrl(
    processingFrequency = 25 MHz,
    extMemsFrequency = 80 MHz,
    p.withGraphic,
    p.vgaSettings,
    p.withMcuJtag
  )

  // Provide clock domains to the vexii for debug module. The
  // jtag TAP clock domain is created internally and doesn't need a reset.
  // embeddedJtagNoTapCd is used only when no TAP is used (attaching to a simulation
  // for example).
  p.vexii.embeddedJtagCd = socCtrl.debugClockDomain
  p.legalize()

  socCtrl.io.externalClock := io.CLK0
  socCtrl.io.externalResetN := io.FPGA_BUT

  // Use Analog to allow mirroring of PSRAM phy behavior.
  val debugBus = Analog(Bits(16 bits))

  io.NA_B.assignFromBits(debugBus(7 downto 0), 0, 8 bits)
  io.NA_B(8) := False
  io.NA_A.assignFromBits(debugBus(15 downto 8), 0, 8 bits)
  io.NA_A(8) := False

  val vgaSink =
    p.withGraphic generate new VgaSink(
      socCtrl.processingClockDomain,
      socCtrl.graphicClockDomain
    )

  val processing = new ClockingArea(socCtrl.processingClockDomain) {

    val processing = new Processing(p)

    val serializer = new SioSerializerCc(
      socCtrl.extMemsClockDomain,
      // TODO use 2 when it works in unit tests to not be dependent on P&R.
      phyCycleLatency = 0,
      outPipelineLevelBitWidths = Seq(2, 1, 0),
      inPipelineLevelBitWidths = Seq(2, 1, 0)
    )
    processing.io.psramSioBus <> serializer.io.sioBus

    if (p.withGraphic) {
      processing.io.vgaStream <> vgaSink.io.input
    }

    if (p.withUart) {
      // rx and tx name are viewed from the point of view of the host
      io.GPIO13_DBG_UART_RX := processing.io.uart.txd
      processing.io.uart.rxd := io.GPIO12_DBG_UART_TX
    } else {
      io.GPIO13_DBG_UART_RX := True
    }

    // ---------- jtag pins -------------
    if (p.withMcuJtag) {
      debugWiring match {
        case DebugWiring.FpgaJtag =>
          processing.io.jtag.tck := io.JTAG_TCK
          processing.io.jtag.tms := io.JTAG_TMS
          processing.io.jtag.tdi := io.JTAG_TDI
          io.JTAG_TDO := processing.io.jtag.tdo
          io.EB_A(8 downto 0).setAll()
        case DebugWiring.BankEb1 =>
          // Names and comments are for j-link JTAG 2x10 pin, 2.54 mm pitch ARM connector
          // see https://www.segger.com/products/debug-probes/j-link/technology/interface-description/
          // RESET pin 15 should be connected to BANK_MISC1 pin 29 (FPGA_RESET_IN).
          // VDD_EB_SEL1 should be  2V5 (JUMP_CAP3), because FPGA_RESET_IN is
          // connected to a 2.5 V. It is an open drain per this source:
          // https://forum.segger.com/thread/7972-solved-nreset-as-input-output/
          // but the read-back of the pin need the proper voltage level.
          //
          // Note that pin the BANK_EB is are wired is reversed order compared to
          // BANK_NA and BANK_NB.
          val nTrst = True // no jtag tap reset, use only jtag TMS sequence
          io.EB_A(8) := nTrst
          processing.io.jtag.tdi := io.EB_A(7)
          processing.io.jtag.tms := io.EB_A(6)
          processing.io.jtag.tck := io.EB_A(5)
          val rtck = False // no need return clock, use own clock domain.
          io.EB_A(4) := rtck
          io.EB_A(3) := processing.io.jtag.tdo
          io.EB_A(2 downto 0) := B("111")
      }

      // This allows reset of the soc with the jtag.
      socCtrl.io.systemResetFromMcu := processing.io.systemResetFromMcu
    } else {
      io.EB_A(8 downto 0) := B(0, 9 bits)
    }

    // ------------ debugBus --------------------------

    val debugBusArea = debugBusConfig match {
      case DebugBusConfig.Zeros =>
        new Area {
          for (i <- 0 until 16) {
            debugBus(i) := False
          }
        }

      case DebugBusConfig.FreeRunningCounter =>
        new Area {
          val counter = Counter(16 bits) init (0)

          counter.increment()
          debugBus.assignFromBits(counter.asBits)
        }
      case DebugBusConfig.PsramDebug =>
        new Area {

          // Deactivate these allows to reduces the noise at high f_clk on
          // the signal analyser.
          val withProgramCounter = true
          val withSioRamInternalSignals = true

          Fiber patch new Area {
            if (p.withMcu && withProgramCounter) {
              val pcFull = processing.mcu.core.plugins
                .collectFirst { case p: PcPlugin => p.logic.harts(0).self.state }
                .get
                .pull()
              debugBus(6 downto 2) := RegNext(pcFull(6 downto 2).asBits)
            } else {
              debugBus(6 downto 2) := B(0, 5 bits)
            }

            if (p.withUart) {
              debugBus(0) := RegNext(processing.io.uart.txd)
              // debugBus(1) := RegNext(processing.io.uart.rxd)
            }
          }

          new ClockingArea(socCtrl.extMemsClockDomain) {
            val phy = new SioMemPhy(IoVoltage.V1_8, invertedCockTimingClosureScheme = true)

            phy.io.cs := RegNext(serializer.io.cs)
            phy.io.sio.writeEnable := True
            phy.io.sio.write := RegNext(
              serializer.io.sio.writeEnable ? serializer.io.sio.write | serializer.io.sio.read
            )

            debugBus(8) := phy.io.externalIos.clk
            debugBus(9) := phy.io.externalIos.csN
            debugBus(10) <> phy.io.externalIos.sio(0)
            debugBus(11) <> phy.io.externalIos.sio(1)
            debugBus(12) <> phy.io.externalIos.sio(2)
            debugBus(13) <> phy.io.externalIos.sio(3)
            debugBus(14) := RegNext(serializer.io.sio.writeEnable)
          }

          if (withSioRamInternalSignals) {
            debugBus(1) := RegNext(serializer.io.sioBus.downStream.fire)
            debugBus(7) := RegNext(serializer.io.sioBus.upStream.fire)
            debugBus(15) := RegNext(serializer.io.sioBus.upStream.fire)
          } else {
            debugBus(1) := False
            debugBus(7) := False
            debugBus(15) := False
          }
        }

      case DebugBusConfig.JtagAndResets =>
        new Area {
          val ccUsrRstn = new CcUsrRstn()
          debugBus(0) := ccUsrRstn.io.usrRstn
          debugBus(1) := (if (p.withMcuJtag) socCtrl.debugClockDomain.readResetWire else False)
          debugBus(2) := socCtrl.processingClockDomain.readResetWire
          debugBus(3) := socCtrl.extMemsClockDomain.readResetWire
          debugBus(4) := (if (p.withGraphic) socCtrl.graphicClockDomain.readResetWire else False)
          debugBus(5) := socCtrl.processingClockDomain.readClockWire
          debugBus(6) := socCtrl.extMemsClockDomain.readClockWire
          debugBus(7) := (if (p.withGraphic) socCtrl.graphicClockDomain.readClockWire else False)

          if (p.withMcuJtag) {
            debugBus(8) := False
            debugBus(9) := processing.io.jtag.tdi
            debugBus(10) := processing.io.jtag.tms
            debugBus(11) := processing.io.jtag.tck
            debugBus(12) := False
            debugBus(13) := processing.io.jtag.tdo
            debugBus(14) := False
            debugBus(15) := False
          } else {
            debugBus(15 downto 8) := B(0, 8 bits)
          }
        }
    }
  }

  val mems = new ClockingArea(socCtrl.extMemsClockDomain) {

    /*
    val phyCycleLatency = processing.serializer.phyCycleLatency
    assert(
      phyCycleLatency == 0 || phyCycleLatency == 2,
      s"latency of $phyCycleLatency not supported by PsramPhy"
    )
     */
    val phy = new SioMemPhy(IoVoltage.V1_8, invertedCockTimingClosureScheme = true)

    phy.io.cs := processing.serializer.io.cs
    phy.io.sio <> processing.serializer.io.sio

    io.PSRAM_SCLK := phy.io.externalIos.clk

    io.PSRAM_CSN := phy.io.externalIos.csN

    io.PSRAM_DATA(
      phy.io.externalIos.sio.getBitsWidth - 1 downto 0
    ) <> phy.io.externalIos.sio
  }

  val vga = if (p.withGraphic) {
    new ClockingArea(socCtrl.graphicClockDomain) {
      val vgaOutput = vgaSink.io.output

      io.VGA_HSync := vgaOutput.hSync
      io.VGA_VSync := vgaOutput.vSync
      io.VGA_Red := vgaOutput.color.r
      io.VGA_Green := vgaOutput.color.g
      io.VGA_Blue := vgaOutput.color.b
    }
  } else {
    io.VGA_HSync := True
    io.VGA_VSync := True
    io.VGA_Red.clearAll()
    io.VGA_Green.clearAll()
    io.VGA_Blue.clearAll()
  }
}
