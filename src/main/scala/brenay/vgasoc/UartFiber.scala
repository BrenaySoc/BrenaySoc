package brenay.vgasoc

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib.bus
import spinal.lib.com.uart.{TilelinkUartCtrl, UartCtrlMemoryMappedConfig}
import spinal.lib.misc.InterruptNode

case class UartFiber(p: VgaSocParam) extends Area {
  val up = bus.tilelink.fabric.Node.slave()
  val interrupt = InterruptNode.master()

  var config = UartCtrlMemoryMappedConfig(
    uartCtrlConfig = p.uartCtrlGenerics,
    initConfig = p.uartCtrlInitConfig,

    // a BRAM is needed, so use all the data of one
    txFifoDepth = (2 KiB).toInt,
    rxFifoDepth = (2 KiB).toInt
  )

  val logic = Fiber build new Area {
    up.m2s.supported.load(TilelinkUartCtrl.getTilelinkSupport(up.m2s.proposed))
    up.s2m.none()

    val core = TilelinkUartCtrl(config, up.bus.p)
    core.io.bus <> up.bus
    interrupt.flag := core.io.interrupt

    val outTxd = core.io.uart.txd
    val inRxd = core.io.uart.rxd
  }

  val externalIos = Fiber patch new Area {
    val uart = logic.core.io.uart
  }
}
