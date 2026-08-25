package brenay.lib.blackbox.cologne.gatemate

import spinal.core._

sealed abstract class PerfMode(val id: String)

object PerfMode {

  /** Low power 0.9 V */
  case object LowPower extends PerfMode("LOWPOWER")

  /** Economy 1.0 V */
  case object Economy extends PerfMode("ECONOMY")

  /** Speed 1.1 V */
  case object Speed extends PerfMode("SPEED")
}

/** GateMate CC_PLL primitive
  *
  * @param refClk         Input reference clock in spinal Hz unit, e.g. 10 MHz.
  * @param outClk         Output core clock in spinal Hz, e.g. 50 MHz.
  * @param perfMode       FPGA operation mode for VDD_PLL
  *                       If not specified, the global setting of Place & Route is used.
  * @param lowJitter      Enable Low Jitter mode
  *
  * @param lockReq        Enable lock status required before PLL output enable
  * @param clk270Doub     Enable frequency doubling of CLK270 PLL output
  * @param clk180Doub     Enable frequency doubling of CLK180 PLL output
  * @param ciFilterConst  Integral coefficient of loop filter, should be greater than zero.
  * @param cpFilterConst  Proportional coefficient of loop filter, should be greater than `ciFilterConst`.
  *                       The higher the CP/CI ratio, the more stable the loop (phase margin).
  *                       Higher CP leads to larger period jitter.
  */
class CcPll(
    refClk: HertzNumber = 10 MHz,
    val outClk: HertzNumber = 50 MHz,
    perfMode: PerfMode = PerfMode.Economy,
    lowJitter: Boolean = true,
    lockReq: Boolean = true,
    clk270Doub: Boolean = false,
    clk180Doub: Boolean = false,
    ciFilterConst: Int = 2,
    cpFilterConst: Int = 4
) extends BlackBox {
  this.setBlackBoxName("CC_PLL")

  addGeneric("REF_CLK", (refClk.toDouble / 1e6).toString())
  addGeneric("OUT_CLK", (outClk.toDouble / 1e6).toString())
  addGeneric("PERF_MD", perfMode.id)
  addGeneric("LOW_JITTER", lowJitter.toInt)
  addGeneric("LOCK_REQ", lockReq.toInt)
  addGeneric("CLK270_DOUB", clk270Doub.toInt)
  addGeneric("CLK180_DOUB", clk180Doub.toInt)
  addGeneric("CI_FILTER_CONST", ciFilterConst)
  addGeneric("CP_FILTER_CONST", cpFilterConst)

  val io = new Bundle {

    /** Reference clock signal from dedicated clock pin (e.g., CLK0, CLK1, CLK2, CLK3) */
    val clkRef = in Bool ()

    /** Alternative reference clock signal from FPGA internal circuitry */
    val usrClkRef = in Bool ()

    /** Feedback clock signal */
    val clkFeedback = in Bool ()

    /** Reset of USR_PLL_LOCKED_STDY, must be set to 1 for a minimum of 2 cycles of CLK_REF */
    val usrLockedStdyRst = in Bool ()

    /** PLL permanent lock status signal */
    val usrPllLockedStdy = out Bool ()

    /** PLL lock status signal */
    val usrPllLocked = out Bool ()

    /** PLL clock output, no phase shift */
    val clk0 = out Bool ()

    /** PLL clock output, 90° phase shift */
    val clk90 = out Bool ()

    /** PLL clock output, 180° phase shift */
    val clk180 = out Bool ()

    /** PLL clock output, 270° phase shift */
    val clk270 = out Bool ()

    /** PLL reference clock output */
    val clkRefOut = out Bool ()
  }

  io.clkRef.setName("CLK_REF")
  io.usrClkRef.setName("USR_CLK_REF")
  io.clkFeedback.setName("CLK_FEEDBACK")
  io.usrLockedStdyRst.setName("USR_LOCKED_STDY_RST")

  io.usrPllLockedStdy.setName("USR_PLL_LOCKED_STDY")
  io.usrPllLocked.setName("USR_PLL_LOCKED")
  io.clk0.setName("CLK0")
  io.clk90.setName("CLK90")
  io.clk180.setName("CLK180")
  io.clk270.setName("CLK270")
  io.clkRefOut.setName("CLK_REF_OUT")

  noIoPrefix()
}
