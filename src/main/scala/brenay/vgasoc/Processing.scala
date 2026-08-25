package brenay.vgasoc

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib.StreamArbiter.LowerFirst
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.fabric.Node
import spinal.lib.com.jtag.Jtag
import spinal.lib.com.spi.ddr.{SpiXdrMasterCtrl, SpiXdrParameter}
import spinal.lib.com.spi.xdr.TilelinkSpiXdrMasterFiber
import spinal.lib.com.uart.Uart
import spinal.lib.graphic.vga.Vga
import spinal.lib.misc.plic.TilelinkPlicFiber
import spinal.lib.misc.{Elf, TilelinkClintFiber}
import spinal.lib.system.tag.{MemoryConnection, PMA}

import _root_.vexiiriscv.soc.TilelinkVexiiRiscvFiber
import brenay.lib.gatemate.SioSerializerCc
import vexiiriscv.execute.cfu.{CfuPlugin, CfuTest}
import vexiiriscv.misc.EmbeddedRiscvJtag
import vexiiriscv.soc.TilelinkVexiiRiscvFiber

/** Part of the design doing the processing at a "best effort" speed
  */
class Processing(p: VgaSocParam) extends Component {

  val io = new Bundle {

    val uart = p.withUart generate master(
      Uart(ctsGen = p.uartCtrlGenerics.ctsGen, rtsGen = p.uartCtrlGenerics.rtsGen)
    )
    val psramSioBus = master(SioSerializerCc.Bus())

    val vgaStream = p.withGraphic generate
      (master Stream (Vec(Vga(p.vgaSettings.rgbConfig, withColorEn = false), p.vgaSinkPixelWidth)))

    val jtag = p.withMcuJtag generate slave(Jtag())

    val systemResetFromMcu = p.withMcuJtag generate (out Bool ())
  }

  val mcu = p.withMcu generate new Area {

    val coreBus = tilelink.fabric.Node()

    // lot of masters, so set M2S for arbiter
    coreBus.setUpConnection(a = StreamPipe.M2S, d = StreamPipe.NONE)

    val core = new TilelinkVexiiRiscvFiber(p.vexii.plugins())

    // For a better realtime behaviour and a faster fmax, it's possible to use
    // a lower first arbiter instead of round robin. This works because we don't use withBCE.
    assert(!p.realTimeArbiters, "priority arbiter is currently not working")
    if (p.realTimeArbiters) coreBus.setArbitrationPolicy(LowerFirst)
    coreBus << List(core.iBus, core.dBus) ++ core.lsuL1Bus.nullOption

    core.dBus.setDownConnection(a = StreamPipe.NONE, d = StreamPipe.NONE)
    core.iBus.setDownConnection(a = StreamPipe.NONE, d = StreamPipe.NONE)
    if (p.vexii.lsuL1Enable)
      core.lsuL1Bus.setDownConnection(a = StreamPipe.NONE, d = StreamPipe.NONE)

    // All slow things are connected here
    val slow = new Area {

      val bus = Node()
      bus.forceDataWidth(32)
      bus.setUpConnection(a = StreamPipe.NONE, d = StreamPipe.NONE)
      bus.setDownConnection(a = StreamPipe.NONE, d = StreamPipe.M2S)
      bus << coreBus

      // Internal ram, for bootloading and hard real-time things.
      // It is not cacheable so it is slow but deterministic.
      val internalRam = new tilelink.fabric.RamFiber(p.mcuRamBytes)
      internalRam.up.addTag(PMA.UNCACHABLE)

      internalRam.up at p.vexii.resetVector of bus

      // The clint is a regular RISC-V timer peripheral
      val clint = new TilelinkClintFiber()
      clint.node at 0x10010000 of bus

      // The clint is a regular RISC-V interrupt controller
      val plic = new TilelinkPlicFiber()
      plic.node at 0x10c00000 of bus

      val uart = p.withUart generate new UartFiber(p) {
        up at 0x10001000 of bus
        plic.mapUpInterrupt(1, interrupt)
      }

      assert(!p.withSpiFlash, "external flash not tested")
      val spiFlash = p.withSpiFlash generate new TilelinkSpiXdrMasterFiber(
        SpiXdrMasterCtrl.MemoryMappingParameters(
          SpiXdrMasterCtrl.Parameters(8, 12, SpiXdrParameter(2, 2, 1)).addFullDuplex(0, 1, false),
          xipEnableInit = true,
          xip = SpiXdrMasterCtrl.XipBusParameters(addressWidth = 24, lengthWidth = 6)
        )
      ) {
        plic.mapUpInterrupt(2, interrupt)
        ctrl at 0x10002000 of bus
        xip at 0x20000000 of bus
      }

      // Let's connect a few of the CPU interfaces to their respective peripherals
      val cpuPlic = core.bind(plic) // External interrupts connection
      val cpuClint = core.bind(clint) // Timer interrupt + time reference + stop time connection
    }

    val cfu = p.vexii.withCfu generate (Fiber patch new Area {
      val cpuCfuBus = core.logic.core.host[CfuPlugin].logic.bus
      val cfu =
        CfuTest() // If instead you want to export the CFU bus to the io, replace with : val bus = cpuCfuBus.toIo()
      cfu.io.bus << cpuCfuBus
    })
  }

  val displayController = p.withGraphic generate new DisplayControllerFiber(p)
  val gpu = p.withGraphic generate new GpuFiber()

  if (p.withGraphic) {
    displayController.vgaStream <> io.vgaStream
  }

  val memsBus = tilelink.fabric.Node()
  memsBus.forceDataWidth(32)

  // lot of masters, so set M2S for arbiter and S2M for decoder
  memsBus.setUpConnection(a = StreamPipe.M2S, d = StreamPipe.S2M)

  if (p.withGraphic) {
    // To insure that the display controller is never starved, it has priority
    // because it never use 100 % of the bandwidth, there is no risk of starvation.
    // Also it has the higher real-time priority, a image stream must always be
    // outputted.
    if (p.realTimeArbiters) memsBus.setArbitrationPolicy(LowerFirst)
    memsBus << displayController.down
    displayController.down.setDownConnection(a = StreamPipe.NONE, d = StreamPipe.NONE)
  }

  if (p.withMcu) {
    // the core is second in priority, because lag is sound or responsiveness are
    // more real-time that image freeze.
    memsBus << mcu.coreBus
  }

  if (p.withGraphic) {
    gpu.down.setDownConnection(a = StreamPipe.NONE, d = StreamPipe.NONE)
    memsBus << gpu.down
  }

  val psram = new SioRamFiber()
  psram.up at 0x90000000L of memsBus
  psram.up.setUpConnection(a = StreamPipe.NONE, d = StreamPipe.NONE)

  // At the patch elaboration stage, do thing on the constructed mcu
  val patcher = Fiber patch new Area {

    // ----- map signals to external IOs ---------
    if (p.withUart) {
      io.uart <> mcu.slow.uart.externalIos.uart
    }
    psram.thread.controller.io.sioBus <> io.psramSioBus

    if (p.withMcu) {
      println(MemoryConnection.getMemoryTransfers(mcu.core.dBus).mkString("\n"))

      if (p.withMcuJtag) {
        io.jtag <> mcu.core.plugins.collectFirst { case p: EmbeddedRiscvJtag =>
          p.logic.jtag
        }.get

        io.systemResetFromMcu := mcu.core.plugins.collectFirst { case p: EmbeddedRiscvJtag =>
          p.logic.ndmreset
        }.get
      }
    }

    // --------- set startup value of internal ram code ----------
    p.ramElf.foreach(
      new Elf(_, p.vexii.xlen)
        .init(mcu.slow.internalRam.thread.logic.mem, p.vexii.resetVector)
    )
  }
}
