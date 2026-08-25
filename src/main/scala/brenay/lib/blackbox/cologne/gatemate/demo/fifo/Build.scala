package brenay.lib.blackbox.cologne.gatemate.demo.fifo

import java.nio.file.{Files, Paths}

import brenay.lib.flows.{
  LogLevel,
  PlaceRouteGatemateStep,
  SingleDirFlowContext,
  SpinalGenVerilogStep,
  StepExecutionException,
  YosysSynthGatemateStep
}

object Build extends App {

  val PRINT_METRICS = false

  val genStep = new SpinalGenVerilogStep()
  val synthStep = new YosysSynthGatemateStep("/opt/oss-cad-suite/bin/yosys")
  val placeRouteStep =
    new PlaceRouteGatemateStep("/opt/oss-cad-suite/bin/nextpnr-himbaechel")

  val flowPath = Files.createDirectories(
    Paths.get("./flow_build/brenay/lib/blackbox/cologne/gatemate/demo/fifo/Build")
  )

  val context = new SingleDirFlowContext(flowPath, LogLevel.Filtered)

  try {
    val genExecution = context.execute(genStep, () => new SystemTop)

    val synthExecution = genExecution.thenExecute(
      synthStep,
      Seq(
        YosysSynthGatemateStep.UseLutTree(),
        YosysSynthGatemateStep.NoMx8(),
        YosysSynthGatemateStep.NoClkBuf(false)
      )
    )

    System.out.println(s"synthStep version: ${synthStep.version()}")
    System.out.flush()

    if (PRINT_METRICS)
      for (metric <- synthExecution.metrics) {
        println(s"${metric.name()}: ${metric.value} ${metric.unit()}")
      }

    import brenay.lib.flows.PlaceRouteGatemateStep._

    val pnrExecution = synthExecution.thenExecute(
      placeRouteStep,
      Seq(
        Device("CCGM1A1"),
        TargetFrequency(100e6),
        FpgaMode("speed"),
        TimeMode("worst"),
        Router("router2"),
        ConstraintFileCcf.fromPackageDir("src/main/scala", this)
      )
    )

    println(s"placeRouteStep version: ${placeRouteStep.version()}")
    System.out.flush()

    if (PRINT_METRICS)
      for (metric <- pnrExecution.metrics) {
        println(s"${metric.name()}: ${metric.value} ${metric.unit()}")
      }

  } catch {
    case e: StepExecutionException => println(e.toString())
  }
}
