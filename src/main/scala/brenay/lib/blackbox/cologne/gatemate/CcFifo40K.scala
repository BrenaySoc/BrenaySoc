package brenay.lib.blackbox.cologne.gatemate

import spinal.core._

sealed abstract class FifoMode(val id: String)

object FifoMode {
  case object Sync extends FifoMode("SYNC")
  case object Async extends FifoMode("ASYNC")
}

sealed abstract class RamMode(val id: String)

object RamMode {
  case object SimpleDualPort extends RamMode("SDP")
  case object TrueDualPort extends RamMode("TDP")

  val Sdp = SimpleDualPort
  val Tdp = TrueDualPort
}

sealed abstract class FifoDynStatMode(val id: Int)

object FifoDynStatMode {
  case object ViaInputs extends FifoDynStatMode(0)
  case object ViaParams extends FifoDynStatMode(1)
}

/** All valid BRAM/FIFO data widths */
sealed abstract class BramDataWidth(val bits: Int) {
  def toInt: Int = bits

  /** Can this width support ECC in some configuration? */
  def canBeEccCapable: Boolean = bits == 40 || bits == 80

  /** User data width when ECC is enabled (32 or 64 bits) */
  def eccUserWidth: Option[Int] = bits match {
    case 40 => Some(32)
    case 80 => Some(64)
    case _  => None
  }

  /** Checks if this width is valid for a context with the given maximum width.
    * BIT0 always returns false (not a valid operational width).
    * Otherwise, checks if this.bits <= maxWidth.bits.
    *
    * Example: For 20K mode (max=20), use isValidUpTo(BIT20)
    *          For 40K mode (max=80), use isValidUpTo(BIT80)
    */
  def isValidUpTo(maxWidth: BramDataWidth): Boolean = {
    this != BramDataWidth.BIT0 && this.bits <= maxWidth.bits
  }

  /** Asserts this width is valid up to the given maximum width.
    * Throws IllegalArgumentException if invalid.
    *
    * @param maxWidth  The maximum allowed width (e.g., BIT20 for 20K, BIT80 for 40K)
    * @param prefix   Optional message prefix for error reporting
    */
  def assertValidUpTo(maxWidth: BramDataWidth, prefix: String = ""): Unit = {
    if (!isValidUpTo(maxWidth)) {
      val msg = if (prefix.nonEmpty) s"$prefix: " else ""
      throw new IllegalArgumentException(
        s"${msg}Width ${this.bits} is not valid (max allowed: ${maxWidth.bits})"
      )
    }
  }
}

object BramDataWidth {
  // BIT0 is the default value used in CcFifo40K - not valid for operational use
  case object BIT0 extends BramDataWidth(0)

  // Common to 20K and 40K/FIFO
  case object BIT1 extends BramDataWidth(1)
  case object BIT2 extends BramDataWidth(2)
  case object BIT5 extends BramDataWidth(5)
  case object BIT10 extends BramDataWidth(10)
  case object BIT20 extends BramDataWidth(20)

  // 40K/FIFO only
  case object BIT40 extends BramDataWidth(40)
  case object BIT80 extends BramDataWidth(80)

  /** All defined widths (including BIT0) */
  val all: List[BramDataWidth] = List(BIT0, BIT1, BIT2, BIT5, BIT10, BIT20, BIT40, BIT80)

  /** Operational widths only (excluding BIT0) */
  val operational: List[BramDataWidth] = all.filter(_.bits > 0)

  /** Returns the exact width matching the bit count */
  def exact(bits: Int): BramDataWidth = {
    all
      .find(_.bits == bits)
      .getOrElse(
        throw new IllegalArgumentException(
          s"$bits is not a valid BRAM width. Valid: ${all.map(_.bits).mkString(", ")}"
        )
      )
  }

  /** Returns minimum valid width >= requested for the given max width.
    * Fails if requested > maxWidth.bits.
    */
  def minimumFor(maxWidth: BramDataWidth, requestedBits: Int): BramDataWidth = {
    operational
      .find(w => w.bits >= requestedBits && w.bits <= maxWidth.bits)
      .getOrElse(
        throw new IllegalArgumentException(
          s"$requestedBits bits exceeds maximum of ${maxWidth.bits} bits"
        )
      )
  }
}

/** GateMate CC_FIFO_40K primitive
  *
  * @param loc                Location in FPGA array: D(0..N-1)X(0..3)Y(0..7)
  * @param almostFullOffset   Static early FULL limit warning (0 to 16383)
  * @param almostEmptyOffset  Static early EMPTY limit warning (0 to 16383)
  * @param dynStatSelect      Select between dynamic or static almost full/empty offset:
  *                           ViaInputs: dynamic offset via port inputs (default)
  *                           ViaParams: static offset via parameters
  * @param aWidth             Fifo output width
  * @param bWidth             Fifo input width
  * @param ramMode            RAM dual-port mode
  * @param fifoMode           FIFO mode
  * @param aClkInv            Invert A clock
  * @param bClkInv            Invert B clock
  * @param aEnInv             Invert A enable
  * @param bEnInv             Invert B enable
  * @param aWeInv             Invert A write enable
  * @param bWeInv             Invert B write enable
  * @param aDoReg             Output A register enable
  * @param bDoReg             Output B register enable
  * @param aEccEn             Port A 1-bit ECC enable
  * @param bEccEn             Port B 1-bit ECC enable
  */
class CcFifo40K(
    loc: String = "UNPLACED",
    almostFullOffset: Int = 0,
    almostEmptyOffset: Int = 0,
    dynStatSelect: FifoDynStatMode = FifoDynStatMode.ViaInputs,
    aWidth: BramDataWidth = BramDataWidth.BIT0,
    bWidth: BramDataWidth = BramDataWidth.BIT0,
    ramMode: RamMode = RamMode.SimpleDualPort,
    fifoMode: FifoMode = FifoMode.Sync,
    aClkInv: Boolean = false,
    bClkInv: Boolean = false,
    aEnInv: Boolean = false,
    bEnInv: Boolean = false,
    aWeInv: Boolean = false,
    bWeInv: Boolean = false,
    aDoReg: Boolean = false,
    bDoReg: Boolean = false,
    aEccEn: Int = 0,
    bEccEn: Int = 0
) extends BlackBox {
  this.setBlackBoxName("CC_FIFO_40K")

  addGeneric("LOC", loc)
  addGeneric("ALMOST_FULL_OFFSET", almostFullOffset)
  addGeneric("ALMOST_EMPTY_OFFSET", almostEmptyOffset)
  addGeneric("DYN_STAT_SELECT", dynStatSelect.id)
  addGeneric("A_WIDTH", aWidth.toInt)
  addGeneric("B_WIDTH", bWidth.toInt)
  addGeneric("RAM_MODE", ramMode.id)
  addGeneric("FIFO_MODE", fifoMode.id)
  addGeneric("A_CLK_INV", aClkInv.toInt)
  addGeneric("B_CLK_INV", bClkInv.toInt)
  addGeneric("A_EN_INV", aEnInv.toInt)
  addGeneric("B_EN_INV", bEnInv.toInt)
  addGeneric("A_WE_INV", aWeInv.toInt)
  addGeneric("B_WE_INV", bWeInv.toInt)
  addGeneric("A_DO_REG", aDoReg.toInt)
  addGeneric("B_DO_REG", bDoReg.toInt)
  addGeneric("A_ECC_EN", aEccEn)
  addGeneric("B_ECC_EN", bEccEn)

  val io = new Bundle {
    // Port A (read/pop port)
    val aDi = in Bits (40 bits)
    val aBm = in Bits (40 bits)
    val aClk = in Bool ()
    val aEn = in Bool ()
    val aDo = out Bits (40 bits)
    val aEcc1bErr = out Bool ()
    val aEcc2bErr = out Bool ()

    // Port B (write/push port)
    val bDi = in Bits (40 bits)
    val bBm = in Bits (40 bits)
    val bClk = in Bool ()
    val bEn = in Bool ()
    val bWe = in Bool ()
    val bDo = out Bits (40 bits)
    val bEcc1bErr = out Bool ()
    val bEcc2bErr = out Bool ()

    // FIFO control and status
    val fRdPtr = out Bits (16 bits)
    val fWrPtr = out Bits (16 bits)
    val fAlmostFull = out Bool ()
    val fAlmostEmpty = out Bool ()
    val fFull = out Bool ()
    val fEmpty = out Bool ()
    val fAlmostFullOffset = in Bits (15 bits)
    val fAlmostEmptyOffset = in Bits (15 bits)
    val fRdError = out Bool ()
    val fRstN = in Bool ()
    val fWrError = out Bool ()
  }

  // Set port names to match the primitive
  io.aDi.setName("A_DI")
  io.aBm.setName("A_BM")
  io.aClk.setName("A_CLK")
  io.aEn.setName("A_EN")
  io.aDo.setName("A_DO")
  io.aEcc1bErr.setName("A_ECC_1B_ERR")
  io.aEcc2bErr.setName("A_ECC_2B_ERR")

  io.bDi.setName("B_DI")
  io.bBm.setName("B_BM")
  io.bClk.setName("B_CLK")
  io.bEn.setName("B_EN")
  io.bWe.setName("B_WE")
  io.bDo.setName("B_DO")
  io.bEcc1bErr.setName("B_ECC_1B_ERR")
  io.bEcc2bErr.setName("B_ECC_2B_ERR")

  io.fRdPtr.setName("F_RD_PTR")
  io.fWrPtr.setName("F_WR_PTR")
  io.fAlmostFull.setName("F_ALMOST_FULL")
  io.fAlmostEmpty.setName("F_ALMOST_EMPTY")
  io.fFull.setName("F_FULL")
  io.fEmpty.setName("F_EMPTY")
  io.fAlmostFullOffset.setName("F_ALMOST_FULL_OFFSET")
  io.fAlmostEmptyOffset.setName("F_ALMOST_EMPTY_OFFSET")
  io.fRdError.setName("F_RD_ERROR")
  io.fRstN.setName("F_RST_N")
  io.fWrError.setName("F_WR_ERROR")

  noIoPrefix()
}
