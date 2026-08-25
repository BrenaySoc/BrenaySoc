package brenay.lib.gatemate

import spinal.core._
import spinal.lib._
import spinal.lib.bus.tilelink._

/** Same as  spinal.lib.bus.tilelink.FifoCC but using GmStreamFifoCC
  */
case class TileLinkFullFifoCc(
    busParameter: BusParameter,
    inputCd: ClockDomain,
    outputCd: ClockDomain,
    aDepth: Int,
    bDepth: Int,
    cDepth: Int,
    dDepth: Int,
    eDepth: Int
) extends Component {
  val io = new Bundle {
    val input = slave(Bus(busParameter))
    val output = master(Bus(busParameter))
  }
  val a = GmStreamFifoCC(io.input.a, io.output.a, aDepth, inputCd, outputCd)
  val b =
    busParameter.withBCE generate GmStreamFifoCC(io.output.b, io.input.b, bDepth, outputCd, inputCd)
  val c =
    busParameter.withBCE generate GmStreamFifoCC(io.input.c, io.output.c, cDepth, inputCd, outputCd)
  val d = GmStreamFifoCC(io.output.d, io.input.d, dDepth, outputCd, inputCd)
  val e =
    busParameter.withBCE generate GmStreamFifoCC(io.input.e, io.output.e, eDepth, inputCd, outputCd)
}
