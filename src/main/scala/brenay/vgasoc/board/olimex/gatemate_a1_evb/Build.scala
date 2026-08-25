package brenay.vgasoc.board.olimex.gatemate_a1_evb

import java.nio.file.{Files, Paths}

import brenay.lib.flows.{
  LogLevel,
  PlaceRouteGatemateStep,
  Seed,
  SingleDirFlowContext,
  SpinalGenVerilogStep,
  StepExecutionException,
  YosysSynthGatemateStep
}

object Build extends App {

  val PRINT_METRICS = true

  val ossCadSuitePath = if (false) { "/home/marc/electrotec/spinalhdl/oss-cad-suite-20260524/" }
  else {
    "/opt/oss-cad-suite/"
  }

  val genStep = new SpinalGenVerilogStep()
  val synthStep = new YosysSynthGatemateStep(ossCadSuitePath + "bin/yosys")
  val placeRouteStep =
    new PlaceRouteGatemateStep(ossCadSuitePath + "bin/nextpnr-himbaechel")

  val flowPath = Files.createDirectories(
    Paths.get("./flow_build/brenay/vgasoc/board/olimex/gatemate_a1_evb/Build")
  )

  val context = new SingleDirFlowContext(flowPath, LogLevel.Filtered)

  val genExecution = context.execute(
    genStep,
    () =>
      new VgaSoc(
        if (false) "src/test/asm/uart_tx/build/uart_tx.elf"
        else "src/test/asm/mem_access/build/mem_access.elf",
        debugWiring = VgaSoc.DebugWiring.BankEb1
      )
  )

  val synthExecution = genExecution.thenExecute(
    synthStep,
    Seq(
      YosysSynthGatemateStep.UseLutTree(),
      YosysSynthGatemateStep.NoMx8(),
      YosysSynthGatemateStep.NoClkBuf(true)
    )
  )

  if (PRINT_METRICS)
    for (metric <- synthExecution.metrics) {
      println(s"${metric.name()}: ${metric.value} ${metric.unit()}")
    }

  import brenay.lib.flows.PlaceRouteGatemateStep._

  Seq(11, 12, 13, 14).find { seed =>
    try {
      println(s"seed=$seed")
      val pnrExecution = synthExecution.thenExecute(
        placeRouteStep,
        Seq(
          Seed(seed),
          Device("CCGM1A1"),
          FpgaMode("speed"),
          TimeMode("worst"),
          Router("router2"),
          PlacerHeapBeta(0.5),
          PlacerHeapAlpha(),
          PlacerHeapCritexp(),
          PlacerHeapTimingweight(),

          // this is for domains not derivate without CC_PLL, TODO to be done by python script
          TargetFrequency(10e6),

          ConstraintFileCcf.fromPackageDir("src/main/scala", this)
        )
      )

      if (PRINT_METRICS)
        for (metric <- pnrExecution.metrics) {
          println(s"${metric.name()}: ${metric.value} ${metric.unit()}")
        }
      println(s"place and route suceess with seed=$seed")
      true
    } catch {
      case e: StepExecutionException =>
        println(e.toString())
        false
      case e: Throwable =>
        throw e
    }
  }

}
