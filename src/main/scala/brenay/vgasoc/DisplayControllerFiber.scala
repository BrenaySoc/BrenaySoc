package brenay.vgasoc

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.Opcode
import spinal.lib.bus.tilelink.fabric.Node
import spinal.lib.graphic.vga.Vga
import spinal.lib.{Counter, DownCounter, Flow, Stream}

/** Full duplex display controller
  *
  * Currently only a draft to see if fmax is doable. It is not really functional
  * it only drive tilelink and the vga fifos so p&r can not optimize all out.
  *
  * To use it you have to wire `vgaStream` to the sink of pixels.
  */
class DisplayControllerFiber(p: VgaSocParam) extends Area {
  val down = Node.down()
  val vgaStream =
    Stream(Vec(Vga(p.vgaSettings.rgbConfig, withColorEn = false), p.vgaSinkPixelWidth))

  val thread = Fiber build new Area {

    val burstSize = 64 // in bytes

    down.m2s.proposed load tilelink.M2sSupport(
      addressWidth = 32,
      dataWidth = 32,
      transfers = tilelink.M2sTransfers(
        get = tilelink.SizeRange(burstSize)
      )
    )

    down.m2s.parameters load tilelink.M2sParameters(
      support = down.m2s.proposed,
      sourceCount = 1
    )

    down.s2m.supported load tilelink.S2mSupport.none()

    val vgas = vgaStream.payload.vec

    val bytesPerVga = 2

    require(p.vgaSettings.horizontalResolution % vgas.length == 0)
    val horizontalCounter = Counter(p.vgaSettings.horizontalResolution / vgas.length)

    val verticalCounter = Counter(p.vgaSettings.verticalResolution)

    require(isPow2(burstSize))

    val vgaBeatPerBurst = burstSize / (bytesPerVga * vgas.length)
    require((horizontalCounter.maxValue + 1) % vgaBeatPerBurst == 0)

    val aBus = down.bus.a
    val dBus = down.bus.d

    aBus.valid.setAsReg()
    aBus.payload.address.setAsReg()
    aBus.opcode.setAsReg()
    dBus.ready.setAsReg()

    aBus.payload.assignDontCare()
    aBus.valid := False

    val busParam = down.bus.p

    val dataBurst =
      Reg(Flow(Vec.fill(burstSize / busParam.dataBytes)(Bits(busParam.dataWidth bits))))

    val busBeatCounter = DownCounter(burstSize / busParam.dataBytes)

    val burstPending = Reg(Bool()) init (False)

    /** when possible, load data */

    when(!dataBurst.valid && down.bus.a.ready && !burstPending) {
      aBus.address := aBus.address + burstSize
      aBus.payload.size := log2Up(burstSize) - 1
      aBus.payload.param.assignDontCare()
      aBus.payload.opcode := Opcode.A.GET
      aBus.valid := True
      busBeatCounter := busBeatCounter.maxValue
    }

    when(aBus.fire) {
      aBus.valid := False
      burstPending := True
    }

    dBus.ready := burstPending

    when(dBus.fire) {
      dataBurst.payload(busBeatCounter) := dBus.payload.data
      busBeatCounter.decrement()
      when(busBeatCounter === 0) {
        dataBurst.valid := True
        burstPending := False
      }
    }

    val pixelFlow =
      Reg(Flow(Vec.fill(vgaBeatPerBurst)(Vec.fill(vgas.length)(UInt(bytesPerVga * 8 bits)))))

    val pixelVecBeatCounter = DownCounter(pixelFlow.payload.length)

    when(!pixelFlow.valid && dataBurst.valid) {
      pixelFlow.payload.assignFromBits(dataBurst.payload.asBits)
      pixelFlow.valid := False
      dataBurst.valid := True
      pixelVecBeatCounter := vgaBeatPerBurst - 1
    }

    vgaStream.payload.assignDontCare()
    when(pixelFlow.valid && vgaStream.ready) {
      for (i <- 0 until vgas.length) {
        vgas(i).color
          .assignFromBits(pixelFlow.payload(pixelVecBeatCounter).asBits.resized)
        vgas(i).hSync.assignFromBits(horizontalCounter.willOverflowIfInc.asBits)
        vgas(i).vSync.assignFromBits(verticalCounter.willOverflowIfInc.asBits)

      }
      horizontalCounter.value := horizontalCounter.value + vgas.length

      when(horizontalCounter.willOverflowIfInc) {
        verticalCounter.increment()
      }

      vgaStream.valid := True
      pixelVecBeatCounter.decrement()
      when(pixelVecBeatCounter === 0) {
        pixelFlow.valid := False
      }
    } otherwise (vgaStream.valid := False)

  }

}
