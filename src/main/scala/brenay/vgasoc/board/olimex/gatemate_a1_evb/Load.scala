package brenay.vgasoc.board.olimex.gatemate_a1_evb

import java.nio.file.Paths

import brenay.lib.flows.{
  GmpackStep,
  LogLevel,
  OpenFpgaLoaderStep,
  RoutedDesign,
  SingleDirFlowContext
}

object Load extends App {

  val packBitstreamStep = new GmpackStep()
  val loadStep = new OpenFpgaLoaderStep()

  val flowPath = Paths.get("./flow_build/brenay/vgasoc/board/olimex/gatemate_a1_evb/Build")

  val context = new SingleDirFlowContext(flowPath, LogLevel.Filtered)

  val packExecution = context
    .execute(
      packBitstreamStep,
      RoutedDesign(Some(flowPath.resolve("bitstream_conf.txt")))
    )
    .thenExecute(
      loadStep, {
        import OpenFpgaLoaderStep._
        Seq(
          Board("olimex_gatemateevb"),
          VerboseLevel(VerboseLevel.Normal),
          Frequency(5e6),
          WriteFlash(true)
        )
      }
    )
}
