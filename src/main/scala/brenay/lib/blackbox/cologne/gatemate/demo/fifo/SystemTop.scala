package brenay.lib.blackbox.cologne.gatemate.demo.fifo

import spinal.core._

import brenay.lib.blackbox.cologne.gatemate.{BramDataWidth, CcFifo40K, CcPll, PerfMode, RamMode}

/** Top of the FPGA For the this platform pcb board */
case class SystemTop() extends Component {
  val io = new Bundle {
    val clk = in Bool ()
    val arst_n = in Bool ()
    val PSRAM_CSN = in Bool ()
    val PSRAM_SCLK = out Bool ()
    val PSRAM_DATA_IN = in(UInt(4 bits))
    val PSRAM_DATA_OUT = out(UInt(4 bits))
  }

  noIoPrefix()

  val aReset = ~io.arst_n

  val fClock = 300 MHz

  val pll = new CcPll(refClk = 10 MHz, fClock, PerfMode.Speed)
  pll.io.clkRef <> io.clk
  pll.io.usrClkRef := False
  pll.io.clkFeedback := False
  pll.io.usrLockedStdyRst := aReset

  io.PSRAM_SCLK := pll.io.clk0

  // Define a clean, synchronous reset suitable for Artix-7 using
  // the PLL lock and the external reset.
  val resetGenerationArea = new ClockingArea(
    ClockDomain(
      pll.io.clk0,
      config = ClockDomainConfig(
        resetKind = BOOT,
        clockEdge = RISING,
        resetActiveLevel = HIGH
      )
    )
  ) {
    // Use double flip-flop to avoid metastability issues.
    val externalReset = RegNext(RegNext(~io.arst_n, True), True)
    val pllLocked = RegNext(RegNext(pll.io.usrPllLocked, False), False)

    val reset = RegNext(externalReset || !pllLocked, True)
  }

  val coreConfig = ClockDomainConfig(
    resetKind = SYNC,
    resetActiveLevel = HIGH
  )
  val coreClockDomain = ClockDomain(pll.io.clk0, resetGenerationArea.reset, config = coreConfig)
  
  val coreArea = new ClockingArea(coreClockDomain) {

    // FIFO instantiation with default values and inputs set to 0
    val fifo =
      new CcFifo40K(
        ramMode = RamMode.TrueDualPort,
        aWidth = BramDataWidth.BIT40,
        bWidth = BramDataWidth.BIT40,
        aDoReg = false
      )
    fifo.io.aDi := 0
    fifo.io.aBm := 0
    fifo.io.aClk := coreClockDomain.clock

    fifo.io.bDi := RegNext(RegNext(io.PSRAM_DATA_IN.asBits)).resized
    fifo.io.bBm.setAll()
    fifo.io.bClk := coreClockDomain.clock
    fifo.io.bEn := False
    fifo.io.bWe := RegNext(RegNext(io.PSRAM_CSN))
    fifo.io.fAlmostFullOffset := 0
    fifo.io.fAlmostEmptyOffset := 0
    fifo.io.fRstN := True

    fifo.io.aEn := RegNext(RegNext(RegNext(io.PSRAM_CSN)))
    io.PSRAM_DATA_OUT := RegNext(RegNext(RegNext(fifo.io.aDo.asUInt))).resized
  }

}
