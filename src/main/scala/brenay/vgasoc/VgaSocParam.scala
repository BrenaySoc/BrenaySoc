package brenay.vgasoc

import spinal.core._
import spinal.lib.com.uart.{UartCtrlGenerics, UartCtrlInitConfig, UartParityType, UartStopType}
import spinal.lib.graphic.RgbConfig

import java.io.File

import vexiiriscv.ParamSimple

case class VgaSettings(
    horizontalResolution: Int = 640,
    verticalResolution: Int = 480,
    frameRate: HertzNumber = 60 Hz,
    rgbConfig: RgbConfig = RgbConfig(4, 4, 4)
) {
  def verticalFrequency: HertzNumber = frameRate * horizontalResolution * verticalResolution
}

/** This class will carry all the parameter of the SoC
  */
class VgaSocParam {
  // ------ system wide configuration ---------------

  var withMcu = true // include the RISC-V and its peripherals
  var withGraphic = true // include GPU, graphic controller and VGA clock domain

  var realTimeArbiters = false // use real-time LowerFirst tilelink bus arbiter

  // ------- mcu configuration ---------------

  // Currently, it's not possible to blackbox Ram with initialContent. Because
  // SpinalHDL split the memory blackboxing into 4 "mem_symbol" of 8 bit
  // (probably because of the mask) and yosys can not merge this into a single
  // CC_BRAM_20K, anything bellow 4 CC_BRAM_20K is wasted ram.
  var mcuRamBytes = 4 * 2 KiB // fit into 4 CC_BRAM_20K

  var ramElf = Option.empty[File]

  var withUart = true
  var withSpiFlash = false

  var withMcuJtag = true

  // ------- RISC-V core configuration ---------
  var vexii = new ParamSimple()

  vexii.extension.add("i")
  vexii.extension.add("m")

  // Provide some sane default
  vexii.fetchForkAt = 1
  vexii.lsuPmaAt = 1
  vexii.lsuForkAt = 1
  vexii.relaxedBranch = true
  vexii.withIterativeShift = false
  vexii.regFileSync = true
  vexii.fetchL1Enable = true
  vexii.fetchL1Sets = 32 * 1024 / (64 * 8) // this is so the I cache fit into one CC_BRAM_40K
  vexii.fetchL1Ways = 1
  vexii.lsuL1Enable = true
  vexii.lsuL1Ways = 1
  vexii.lsuL1Sets = 32 * 1024 / (64 * 8) // this is so the D cache fit into one CC_BRAM_40K
  vexii.withLsuBypass = true
  vexii.lsuHardwarePrefetch = "none"
  vexii.withBtb = false
  vexii.withGShare = false
  vexii.gshareBytes = 2 * 1024 // fit in one CC_BRAM_20K
  legalize()

  // After modifying the attributes of this class, you need to call the legalize function to check / fix it is fine.
  def legalize(): Unit = {
    vexii.fixIsaParams()
    vexii.embeddedJtagTap = withMcuJtag
    vexii.embeddedJtagInstruction = withMcuJtag
    vexii.privParam.withDebug = withMcuJtag

    if (!withMcu) {
      ramElf = None
      withMcuJtag = false
      withUart = false
    }
  }

  // ----------- peripheral configuration -----------

  // TODO set fifo depth for 20K BRAM
  val uartCtrlGenerics = UartCtrlGenerics()
  val uartCtrlInitConfig = UartCtrlInitConfig(
    // use config from here https://github.com/phdussud/pico-dirtyJtag/blob/59b7f34fb031e164dafae6b3a56875687d1acf77/cdc_uart.h#L36
    baudrate = 115200,
    dataLength = 8 - 1,
    // use config from here https://github.com/phdussud/pico-dirtyJtag/blob/59b7f34fb031e164dafae6b3a56875687d1acf77/cdc_uart.c#L134
    parity = UartParityType.NONE,
    stop = UartStopType.ONE
  )

  // ---------- graphic configuration ----------
  val vgaSettings =
    VgaSettings(horizontalResolution = 640, verticalResolution = 480, frameRate = 60 Hz)

  val vgaSinkPixelWidth = 2 // how many pixel does the vgaSink stream take on one cycle
}
