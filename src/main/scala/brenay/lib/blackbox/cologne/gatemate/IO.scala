package brenay.lib.blackbox.cologne.gatemate

import spinal.core._

/** GateMate global routing buffer primitive
  *
  * The CC_BUFG primitive is a buffer that connects the input signal to the global routing
  * resources. Typically, it is automatically inferred by the synthesis tool to feed a clock signal
  * into the clock net. The low skew distribution of the signal makes the primitive particularly
  * efficient when dealing with high-fanout signals.
  */
class CcBufG() extends BlackBox {
  this.setBlackBoxName("CC_BUFG")

  val io = new Bundle {

    /** Input from CPE array or input buffer */
    val i = in Bool ()

    /** Output to global routing resource */
    val o = out Bool ()
  }

  io.i.setName("I")
  io.o.setName("O")
  noIoPrefix()
}

sealed abstract class IoVoltage(val id: String)

/** IO Bank spec voltage in Volt */
object IoVoltage {
  case object V1_2 extends IoVoltage("1.2")
  case object V1_8 extends IoVoltage("1.8")
  case object V2_5 extends IoVoltage("2.5")
}

sealed abstract class DriveStrength(val id: String)

/** Output single ended drive strength in mA */
object DriveStrength {
  case object I_3_MA extends DriveStrength("3")
  case object I_6_MA extends DriveStrength("6")
  case object I_9_MA extends DriveStrength("9")
  case object I_12_MA extends DriveStrength("12")
}

sealed abstract class SlewRate(val id: String)

object SlewRate {
  case object Slow extends SlewRate("SLOW")
  case object Fast extends SlewRate("FAST")
}

sealed abstract class LvdsBoost(val id: Int)

/** LVDS output current boost configuration */
object LvdsBoost {
  case object Normal extends LvdsBoost(0) // 3.2 mA nominal current
  case object Boost extends LvdsBoost(1) // 6.4 mA increased current
}

/** GateMate CC_IOBUF primitive - Bidirectional single-ended I/O buffer
  *
  * The CC_IOBUF primitive is a bidirectional single-ended I/O buffer with an active-low
  * output enable signal. I/O buffers must be inserted on all bidirectional signals that are
  * directly connected to the top-level of a design.
  *
  * When the output enable signal T is low, data from FPGA-internal circuitry on the input A
  * is passed to the bidirectional port IO that is further connected to the device pins.
  * When the output enable signal T is high, data from the bidirectional port IO is passed
  * to the output Y that is further FPGA-internal circuitry.
  *
  * @param pinName        Pin name: IO_<Dir><Bank>_<Pin><Pin#>
  * @param vIo           I/O voltage
  * @param drive         Output drive strength
  * @param slew          Slew rate control
  * @param pullUp        Enable pull-up resistor
  * @param pullDown      Enable pull-down resistor
  * @param keeper        Enable bus keeper
  * @param schmittTrigger Enable Schmitt trigger
  * @param delayIbf      Input delay parameter: 0..15
  * @param delayObf      Output delay parameter: 0..15
  * @param ffIbf         Merge input flip-flop into cell
  * @param ffObf         Merge output flip-flop into cell
  */
class CcIoBuf(
    pinName: String = "UNPLACED",
    vIo: IoVoltage = IoVoltage.V1_8,
    drive: DriveStrength = DriveStrength.I_3_MA,
    slew: SlewRate = SlewRate.Slow,
    pullUp: Boolean = false,
    pullDown: Boolean = false,
    keeper: Boolean = false,
    schmittTrigger: Boolean = false,
    delayIbf: Int = 0,
    delayObf: Int = 0,
    ffIbf: Boolean = false,
    ffObf: Boolean = false
) extends BlackBox {
  this.setBlackBoxName("CC_IOBUF")

  addGeneric("PIN_NAME", pinName)
  addGeneric("V_IO", vIo.id)
  addGeneric("DRIVE", drive.id)
  addGeneric("SLEW", slew.id)
  addGeneric("PULLUP", (pullUp.toInt))
  addGeneric("PULLDOWN", (pullDown.toInt))
  addGeneric("KEEPER", (keeper.toInt))
  addGeneric("SCHMITT_TRIGGER", (schmittTrigger.toInt))
  addGeneric("DELAY_IBF", delayIbf)
  addGeneric("DELAY_OBF", delayObf)
  addGeneric("FF_IBF", (ffIbf.toInt))
  addGeneric("FF_OBF", (ffObf.toInt))

  val io = new Bundle {

    /** Input from FPGA-internal circuitry */
    val a = in Bool ()

    /** Active-low output enable signal from FPGA-internal circuitry */
    val t = in Bool ()

    /** Output to FPGA-internal circuitry */
    val y = out Bool ()

    /** Bidirectional inout to device pin.
      * Named i_o instead of io (IO) to avoid confusion with SpinalHDL Bundle.
      */
    val i_o = inout(Analog(Bool()))
  }

  io.a.setName("A")
  io.t.setName("T")
  io.y.setName("Y")
  io.i_o.setName("IO")

  noIoPrefix()
}

/** GateMate CC_IBUF primitive - Unidirectional single-ended input buffer
  *
  * The CC_IBUF primitive is an unidirectional single-ended input buffer. Input buffers must
  * be inserted on all input signals that are directly connected to the top-level of a design.
  * Additional I/O functionality for pull-up and pull-down resistors, input flip-flop, bus keeper
  * functionality and Schmitt trigger can be configured using the primitive's parameters.
  *
  * The I/O pads support low voltage CMOS (LVCMOS) standards up to 2.5 V nominal supply voltage.
  *
  * @param pinName   Pin name: IO_<Dir><Bank>_<Pin><Pin#>
  * @param vIo       I/O voltage
  * @param pullUp    Enable pull-up resistor
  * @param pullDown  Enable pull-down resistor
  * @param keeper    Enable bus keeper
  * @param schmittTrigger Enable Schmitt trigger
  * @param delayIbf  Input delay parameter: 0..15
  * @param ffIbf     Merge input flip-flop into cell
  */
class CcIBuf(
    pinName: String = "UNPLACED",
    vIo: IoVoltage = IoVoltage.V1_8,
    pullUp: Boolean = false,
    pullDown: Boolean = false,
    keeper: Boolean = false,
    schmittTrigger: Boolean = false,
    delayIbf: Int = 0,
    ffIbf: Boolean = false
) extends BlackBox {
  this.setBlackBoxName("CC_IBUF")

  addGeneric("PIN_NAME", pinName)
  addGeneric("V_IO", vIo.id)
  addGeneric("PULLUP", pullUp.toInt)
  addGeneric("PULLDOWN", pullDown.toInt)
  addGeneric("KEEPER", keeper.toInt)
  addGeneric("SCHMITT_TRIGGER", schmittTrigger.toInt)
  addGeneric("DELAY_IBF", delayIbf)
  addGeneric("FF_IBF", ffIbf.toInt)

  val io = new Bundle {

    /** Input from device pin */
    val i = in Bool ()

    /** Output to FPGA-internal circuitry */
    val y = out Bool ()
  }

  io.i.setName("I")
  io.y.setName("Y")

  noIoPrefix()
}

/** GateMate CC_OBUF primitive - Unidirectional single-ended output buffer
  *
  * The CC_OBUF primitive is an unidirectional single-ended output buffer. Output buffers
  * must be inserted on all output signals that are directly connected to the top-level of a
  * design. Additional I/O functionality for drive strength, output flip-flop and slew rate
  * control can be configured using the primitive's parameters.
  *
  * The I/O pads support LVCMOS standards up to 2.5 V nominal supply voltage.
  *
  * @param pinName   Pin name: IO_<Dir><Bank>_<Pin><Pin#>
  * @param vIo      I/O voltage
  * @param drive    Output drive strength
  * @param slew     Slew rate control
  * @param delayObf Output delay parameter: 0..15
  * @param ffObf    Merge output flip-flop into cell
  */
class CcOBuf(
    pinName: String = "UNPLACED",
    vIo: IoVoltage = IoVoltage.V1_8,
    drive: DriveStrength = DriveStrength.I_3_MA,
    slew: SlewRate = SlewRate.Slow,
    delayObf: Int = 0,
    ffObf: Boolean = false
) extends BlackBox {
  this.setBlackBoxName("CC_OBUF")

  addGeneric("PIN_NAME", pinName)
  addGeneric("V_IO", vIo.id)
  addGeneric("DRIVE", drive.id)
  addGeneric("SLEW", slew.id)
  addGeneric("DELAY_OBF", delayObf)
  addGeneric("FF_OBF", ffObf.toInt)

  val io = new Bundle {

    /** Input from FPGA-internal circuitry */
    val a = in Bool ()

    /** Output to device pin */
    val o = out Bool ()
  }

  io.a.setName("A")
  io.o.setName("O")

  noIoPrefix()
}

/** GateMate CC_IDDR primitive - Input DDR register
  *
  * The CC_IDDR primitive is a dedicated input register for double data rate (DDR) support,
  * i.e. for capturing external data on both positive and negative clock edges.
  *
  * Input DDR registers sample incoming data from the input buffer to the FPGA-internal
  * circuitry on both the rising and falling edges of the clock signal. The input data is
  * fed into the FPGA-internal circuitry via the two signals Q0 and Q1.
  *
  * @param clkInv  Clock polarity
  */
/*
 TODO synthesised but not place and route passed with:
    val writing = Reg(Bool)

    writing := ~writing

    val bufs = for (i <- 0 until 8) yield new Area {
      val buf = new CcIoBuf()
      val iddr = new CcIddr()
      val oddr = new CcOddr()
      buf.io.i_o := io.PSRAM_DATA(i)
      buf.io.t := writing

      iddr.io.clk := ClockDomain.current.readClockWire
      iddr.io.d := buf.io.y
      

      oddr.io.clk := ClockDomain.current.readClockWire
      oddr.io.ddr := ClockDomain.current.readClockWire
      oddr.io.d0 := RegNext(~iddr.io.q1)
      oddr.io.d1 := RegNext(~iddr.io.q0)
      buf.io.a := oddr.io.q
    }
 */
class CcIddr(
    clkInv: Boolean = false
) extends BlackBox {
  this.setBlackBoxName("CC_IDDR")

  addGeneric("CLK_INV", (clkInv.toInt))

  val io = new Bundle {

    /** Data input from device pin */
    val d = in Bool ()

    /** Clock signal input */
    val clk = in Bool ()

    /** Data output to FPGA-internal circuitry (rising edge) */
    val q0 = out Bool ()

    /** Data output to FPGA-internal circuitry (falling edge) */
    val q1 = out Bool ()
  }

  io.d.setName("D")
  io.clk.setName("CLK")
  io.q0.setName("Q0")
  io.q1.setName("Q1")

  noIoPrefix()
}

/** GateMate CC_ODDR primitive - Output DDR register
  *
  * The CC_ODDR primitive is a dedicated output register for DDR support,
  * i.e. for transferring data on both positive and negative clock edges.
  *
  * Output DDR registers sample outgoing data from the FPGA-internal circuitry to the
  * output buffer on both the rising and falling edges of the clock signal.
  *
  * @param clkInv  Clock polarity
  */
// TODO synthesized and p&r done, but not tested on hardware
class CcOddr(
    clkInv: Boolean = false
) extends BlackBox {
  this.setBlackBoxName("CC_ODDR")

  addGeneric("CLK_INV", (clkInv.toInt))

  val io = new Bundle {

    /** Data input from FPGA-internal circuitry (rising edge) */
    val d0 = in Bool ()

    /** Data input from FPGA-internal circuitry (falling edge) */
    val d1 = in Bool ()

    /** Clock signal input to flip-flops */
    val clk = in Bool ()

    /** Clock signal input to flip-flop switch */
    val ddr = in Bool ()

    /** Data output to device pin */
    val q = out Bool ()
  }

  io.d0.setName("D0")
  io.d1.setName("D1")
  io.clk.setName("CLK")
  io.ddr.setName("DDR")
  io.q.setName("Q")

  noIoPrefix()
}

/** GateMate CC_TOBUF primitive - Controllable single-ended output buffer
  *
  * The CC_TOBUF primitive is a controllable single-ended output buffer with an active-low
  * output enable signal. Output buffers must be inserted on all output signals that are
  * connected to the top-level of a design. When the output enable signal T is low, data from
  * FPGA-internal circuitry on the input A is passed to the output O that is further
  * connected to the device pins. When the output enable signal T is high, the output is high
  * impedance.
  *
  * @param pinName   Pin name: IO_<Dir><Bank>_<Pin><Pin#>
  * @param vIo       I/O voltage
  * @param drive     Output drive strength
  * @param slew      Slew rate control
  * @param pullUp    Enable pull-up resistor
  * @param pullDown  Enable pull-down resistor
  * @param keeper    Enable bus keeper
  * @param delayObf  Output delay parameter: 0..15
  * @param ffObf     Merge output flip-flop into cell
  */
class CcTOBuf(
    pinName: String = "UNPLACED",
    vIo: IoVoltage = IoVoltage.V1_8,
    drive: DriveStrength = DriveStrength.I_3_MA,
    slew: SlewRate = SlewRate.Slow,
    pullUp: Boolean = false,
    pullDown: Boolean = false,
    keeper: Boolean = false,
    delayObf: Int = 0,
    ffObf: Boolean = false
) extends BlackBox {
  this.setBlackBoxName("CC_TOBUF")

  addGeneric("PIN_NAME", pinName)
  addGeneric("V_IO", vIo.id)
  addGeneric("DRIVE", drive.id)
  addGeneric("SLEW", slew.id)
  addGeneric("PULLUP", pullUp.toInt)
  addGeneric("PULLDOWN", pullDown.toInt)
  addGeneric("KEEPER", keeper.toInt)
  addGeneric("DELAY_OBF", delayObf)
  addGeneric("FF_OBF", ffObf.toInt)

  val io = new Bundle {

    /** Input from FPGA-internal circuitry */
    val a = in Bool ()

    /** Active-low output enable signal from FPGA-internal circuitry */
    val t = in Bool ()

    /** Output to device pin, tri-state if T = 1 */
    val o = out Bool ()
  }

  io.a.setName("A")
  io.t.setName("T")
  io.o.setName("O")

  noIoPrefix()
}

/** GateMate CC_LVDS_IBUF primitive - Unidirectional differential input buffer
  *
  * The CC_LVDS_IBUF primitive is an unidirectional differential input buffer. All low-voltage
  * differential signaling (LVDS) pads are compliant to the LVDS 2.5 V standard. It can further
  * operate down to 1.8 V nominal supply voltage. A LVDS on-chip termination resistor of 100 Ω
  * can be enabled using the corresponding parameter.
  *
  * @param pinNameP  Pin name for positive differential input: IO_<Dir><Bank>_<Pin><Pin#>
  * @param pinNameN  Pin name for negative differential input: IO_<Dir><Bank>_<Pin><Pin#>
  * @param vIo       I/O voltage (1.8 or 2.5 V only)
  * @param lvdsRTerm Enable on-chip termination resistor
  * @param delayIbf  Input delay parameter: 0..15
  * @param ffIbf     Merge input flip-flop into cell
  */
class CcLvdsIBuf(
    pinNameP: String = "UNPLACED",
    pinNameN: String = "UNPLACED",
    vIo: IoVoltage = IoVoltage.V1_8,
    lvdsRTerm: Boolean = false,
    delayIbf: Int = 0,
    ffIbf: Boolean = false
) extends BlackBox {
  this.setBlackBoxName("CC_LVDS_IBUF")

  addGeneric("PIN_NAME_P", pinNameP)
  addGeneric("PIN_NAME_N", pinNameN)
  addGeneric("V_IO", vIo.id)
  addGeneric("LVDS_RTERM", lvdsRTerm.toInt)
  addGeneric("DELAY_IBF", delayIbf)
  addGeneric("FF_IBF", ffIbf.toInt)

  val io = new Bundle {

    /** Positive differential input from device pin */
    val i_p = in Bool ()

    /** Negative differential input from device pin */
    val i_n = in Bool ()

    /** Output to FPGA-internal circuitry */
    val y = out Bool ()
  }

  io.i_p.setName("I_P")
  io.i_n.setName("I_N")
  io.y.setName("Y")

  noIoPrefix()
}

/** GateMate CC_LVDS_OBUF primitive - Unidirectional differential output buffer
  *
  * The CC_LVDS_OBUF primitive is an unidirectional differential output buffer. All LVDS pads
  * are compliant to the LVDS 2.5 V standard. It can further operate down to 1.8 V nominal
  * supply voltage. The LVDS output current can be configured using the corresponding parameter.
  *
  * @param pinNameP  Pin name for positive differential output: IO_<Dir><Bank>_<Pin><Pin#>
  * @param pinNameN  Pin name for negative differential output: IO_<Dir><Bank>_<Pin><Pin#>
  * @param vIo       I/O voltage (1.8 or 2.5 V only)
  * @param lvdsBoost Configure LVDS output current boost
  * @param delayObf  Output delay parameter: 0..15
  * @param ffObf     Merge output flip-flop into cell
  */
class CcLvdsOBuf(
    pinNameP: String = "UNPLACED",
    pinNameN: String = "UNPLACED",
    vIo: IoVoltage = IoVoltage.V1_8,
    lvdsBoost: LvdsBoost = LvdsBoost.Normal,
    delayObf: Int = 0,
    ffObf: Boolean = false
) extends BlackBox {
  this.setBlackBoxName("CC_LVDS_OBUF")

  addGeneric("PIN_NAME_P", pinNameP)
  addGeneric("PIN_NAME_N", pinNameN)
  addGeneric("V_IO", vIo.id)
  addGeneric("LVDS_BOOST", lvdsBoost.id)
  addGeneric("DELAY_OBF", delayObf)
  addGeneric("FF_OBF", ffObf.toInt)

  val io = new Bundle {

    /** Input from FPGA-internal circuitry */
    val a = in Bool ()

    /** Positive differential output to device pin */
    val o_p = out Bool ()

    /** Negative differential output to device pin */
    val o_n = out Bool ()
  }

  io.a.setName("A")
  io.o_p.setName("O_P")
  io.o_n.setName("O_N")

  noIoPrefix()
}

/** GateMate CC_LVDS_TOBUF primitive - Controllable differential output buffer
  *
  * The CC_LVDS_TOBUF primitive is a controllable differential output buffer with an active-low
  * output enable signal. When the output enable signal T is low, data from FPGA-internal
  * circuitry on the input A is passed to the differential outputs O_P and O_N. When the output
  * enable signal T is high, the output is high impedance.
  *
  * @param pinNameP  Pin name for positive differential output: IO_<Dir><Bank>_<Pin><Pin#>
  * @param pinNameN  Pin name for negative differential output: IO_<Dir><Bank>_<Pin><Pin#>
  * @param vIo       I/O voltage (1.8 or 2.5 V only)
  * @param lvdsRTerm Enable on-chip termination resistor
  * @param lvdsBoost Configure LVDS output current boost
  * @param delayObf  Output delay parameter: 0..15
  * @param ffObf     Merge output flip-flop into cell
  */
class CcLvdsTOBuf(
    pinNameP: String = "UNPLACED",
    pinNameN: String = "UNPLACED",
    vIo: IoVoltage = IoVoltage.V1_8,
    lvdsRTerm: Boolean = false,
    lvdsBoost: LvdsBoost = LvdsBoost.Normal,
    delayObf: Int = 0,
    ffObf: Boolean = false
) extends BlackBox {
  this.setBlackBoxName("CC_LVDS_TOBUF")

  addGeneric("PIN_NAME_P", pinNameP)
  addGeneric("PIN_NAME_N", pinNameN)
  addGeneric("V_IO", vIo.id)
  addGeneric("LVDS_RTERM", lvdsRTerm.toInt)
  addGeneric("LVDS_BOOST", lvdsBoost.id)
  addGeneric("DELAY_OBF", delayObf)
  addGeneric("FF_OBF", ffObf.toInt)

  val io = new Bundle {

    /** Input from FPGA-internal circuitry */
    val a = in Bool ()

    /** Active-low output enable signal from FPGA-internal circuitry */
    val t = in Bool ()

    /** Positive differential output to device pin */
    val o_p = out Bool ()

    /** Negative differential output to device pin */
    val o_n = out Bool ()
  }

  io.a.setName("A")
  io.t.setName("T")
  io.o_p.setName("O_P")
  io.o_n.setName("O_N")

  noIoPrefix()
}

/** GateMate CC_LVDS_IOBUF primitive - Bidirectional differential I/O buffer
  *
  * The CC_LVDS_IOBUF primitive is a bidirectional differential I/O buffer. When the output
  * enable signal T is low, data from FPGA-internal circuitry on the input A is passed to the
  * bidirectional differential ports IO_P and IO_N. When the output enable signal T is high,
  * data from the bidirectional differential ports IO_P and IO_N is passed to the output Y.
  *
  * @param pinNameP  Pin name for positive differential: IO_<Dir><Bank>_<Pin><Pin#>
  * @param pinNameN  Pin name for negative differential: IO_<Dir><Bank>_<Pin><Pin#>
  * @param vIo       I/O voltage (1.8 or 2.5 V only)
  * @param lvdsRTerm Enable on-chip termination resistor
  * @param lvdsBoost Configure LVDS output current boost
  * @param delayIbf  Input delay parameter: 0..15
  * @param delayObf  Output delay parameter: 0..15
  * @param ffIbf     Merge input flip-flop into cell
  * @param ffObf     Merge output flip-flop into cell
  */
class CcLvdsIoBuf(
    pinNameP: String = "UNPLACED",
    pinNameN: String = "UNPLACED",
    vIo: IoVoltage = IoVoltage.V1_8,
    lvdsRTerm: Boolean = false,
    lvdsBoost: LvdsBoost = LvdsBoost.Normal,
    delayIbf: Int = 0,
    delayObf: Int = 0,
    ffIbf: Boolean = false,
    ffObf: Boolean = false
) extends BlackBox {
  this.setBlackBoxName("CC_LVDS_IOBUF")

  addGeneric("PIN_NAME_P", pinNameP)
  addGeneric("PIN_NAME_N", pinNameN)
  addGeneric("V_IO", vIo.id)
  addGeneric("LVDS_RTERM", lvdsRTerm.toInt)
  addGeneric("LVDS_BOOST", lvdsBoost.id)
  addGeneric("DELAY_IBF", delayIbf)
  addGeneric("DELAY_OBF", delayObf)
  addGeneric("FF_IBF", ffIbf.toInt)
  addGeneric("FF_OBF", ffObf.toInt)

  val io = new Bundle {

    /** Input from FPGA-internal circuitry */
    val a = in Bool ()

    /** Active-low output enable signal from FPGA-internal circuitry */
    val t = in Bool ()

    /** Output to FPGA-internal circuitry */
    val y = out Bool ()

    /** Positive differential bidirectional signal to device pin */
    val io_p = inout(Analog(Bool()))

    /** Negative differential bidirectional signal to device pin */
    val io_n = inout(Analog(Bool()))
  }

  io.a.setName("A")
  io.t.setName("T")
  io.y.setName("Y")
  io.io_p.setName("IO_P")
  io.io_n.setName("IO_N")

  noIoPrefix()
}
