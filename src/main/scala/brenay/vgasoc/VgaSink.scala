package brenay.vgasoc

import spinal.core._
import spinal.lib.graphic.RgbConfig
import spinal.lib.graphic.vga.Vga
import spinal.lib.{Counter, slave}

import brenay.lib.gatemate.GmStreamFifoCC

class VgaSink(
    processingClockDomain: ClockDomain,
    graphicClockDomain: ClockDomain,
    rgbConfig: RgbConfig = RgbConfig(4, 4, 4),
    pixelWidth: Int = 2
) extends Component {
  val io = new Bundle {
    val input = slave Stream (Vec(Vga(rgbConfig, withColorEn = false), pixelWidth))
    val output = out(Vga(RgbConfig(4, 4, 4), withColorEn = false))
  }

  val fifo =
    new GmStreamFifoCC(
      io.input.payload,
      depth = 10,
      pushClock = processingClockDomain,
      popClock = graphicClockDomain
    )

  io.input <> fifo.io.push

  val graphic = new ClockingArea(graphicClockDomain) {

    fifo.io.pop.ready := True
    // if not valid, output garbage
    val fromFifo = RegNext(fifo.io.pop.payload)

    val pixelCounter = Counter(pixelWidth)

    pixelCounter.increment()

    val pixel = fromFifo(RegNext(pixelCounter.value))

    val nextPixel = Reg(Vga(rgbConfig, withColorEn = false))
    nextPixel := pixel

    def adjust(from: UInt, width: Int) = width - widthOf(from) match {
      case 0          => from
      case v if v > 0 => from << v
      case v if v < 0 => from >> v
    }

    val outputColor = io.output.color

    outputColor.r := adjust(nextPixel.color.r, widthOf(outputColor.r))
    outputColor.g := adjust(nextPixel.color.g, widthOf(outputColor.g))
    outputColor.b := adjust(nextPixel.color.b, widthOf(outputColor.b))
    io.output.hSync := nextPixel.hSync
    io.output.vSync := nextPixel.vSync

  }

}
