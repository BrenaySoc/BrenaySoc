package brenay.vgasoc.board.olimex.gatemate_a1_evb

import spinal.core._
import spinal.lib.io.TriState
import spinal.lib.{IMasterSlave, master, slave}

import brenay.lib.blackbox.cologne.gatemate.{
  CcIoBuf,
  CcOBuf,
  CcOddr,
  DriveStrength,
  IoVoltage,
  SlewRate
}

import SioMemPhy.ExternalIos

object SioMemPhy {
  case class ExternalIos(val sioWidth: Int = 4) extends Bundle with IMasterSlave {
    val clk = Bool()

    /** chip select active low */
    val csN = Bool()

    val sio = inout(Analog(Bits(sioWidth bits)))

    override def asMaster(): Unit = {
      out(clk, csN)
      inout(sio)
    }
  }
}

/** Phy(sical) interface for a Serial IO Memory
  *
  * if `invertedCockTimingClosureScheme` is `true`, the clock is inverted
  * by 180° and the router is forced to put the flip-flop of other signals
  * into the IO blocks by using the proper primitive configurations. This way
  * csN and sio have half a clock cycle to propagate cleanly. Because all
  * the flip-flops are located directly in the IO blocks, there is almost no
  * skew between signals. With this scheme it's possible to achieve reproducible
  * timing without constraints which are not supported by nextpnr to date (v0.10).
  *
  * Clock is currently always on.
  */
class SioMemPhy(
    vIo: IoVoltage,
    val sioWidth: Int = 4,
    drive: DriveStrength = DriveStrength.I_12_MA,
    slew: SlewRate = SlewRate.Fast,
    invertedCockTimingClosureScheme: Boolean // = true
) extends Component {

  val io = new Bundle {

    /** chip select active high */
    val cs = in Bool ()
    val sio = slave(TriState(Bits(sioWidth bits)))

    val externalIos = master(ExternalIos(sioWidth))
  }

  val clkOBuf =
    new CcOBuf(vIo = vIo, drive = drive, slew = slew, ffObf = invertedCockTimingClosureScheme)
  io.externalIos.clk := clkOBuf.io.o
  if (invertedCockTimingClosureScheme) new Area {
    // a output DDR block is used to invert the clock
    val clkOddr = new CcOddr()
    clkOddr.io.clk := ClockDomain.readClockWire
    clkOddr.io.ddr := ClockDomain.readClockWire
    clkOddr.io.d0 := False
    clkOddr.io.d1 := True

    clkOBuf.io.a := clkOddr.io.q
  }
  else {

    clkOBuf.io.a := ClockDomain.readClockWire
  }

  val csOBuf =
    new CcOBuf(vIo = vIo, drive = drive, slew = slew, ffObf = invertedCockTimingClosureScheme)
  csOBuf.io.a := ~io.cs
  io.externalIos.csN := csOBuf.io.o

  val ios = for (i <- 0 until io.sio.read.getBitsWidth) yield new Area {
    val ioBuf =
      new CcIoBuf(
        vIo = vIo,
        drive = drive,
        slew = slew,
        ffIbf = invertedCockTimingClosureScheme,
        ffObf = invertedCockTimingClosureScheme
      )

    ioBuf.io.t := ~io.sio.writeEnable
    ioBuf.io.a := io.sio.write(i)
    io.sio.read(i) := ioBuf.io.y

    ioBuf.io.i_o <> io.externalIos.sio(i)
  }
}
