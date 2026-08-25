package brenay.lib.flows

import java.io.PrintStream
import java.nio.file.{Files, Path, Paths}
import java.security.InvalidParameterException
import scala.collection.mutable.ListBuffer

class YosysSynthGatemateStep(toolLocation: String = "yosys", gitReposPath: Option[Path] = None)
    extends FlowToolStep[RtlDesign, SynthesizedNetlist](toolLocation, gitReposPath) {

  val (cachedVersion, cachedExtendedVersion) = StepProcessRunner.queryVersionInfos(
    Seq(toolLocation, "--version"),
    s => {
      ("""Yosys (\d+.\d+\+\d*) \(git """.r.findFirstMatchIn(s).map(_.group(1)), Some(s))
    }
  )

  def name() = "synthesize"
  def version() = cachedVersion
  def extendedVersion() = cachedExtendedVersion
  def invoke(
      context: StepContext,
      input: RtlDesign,
      arguments: Seq[Parameter]
  ): (Seq[Metric], SynthesizedNetlist) = {
    val workingDir = context.getAbsoluteWorkingDir()

    val readVerilogCommands = input.hdlFiles
      .map { file =>
        s"read_verilog -sv ${file.toAbsolutePath}"
      }
      .mkString(";\n")

    val synthArgs = arguments
      .collect {
        case YosysSynthGatemateStep.UseLutTree(flagPresent) if flagPresent => "-luttree"
        case YosysSynthGatemateStep.NoMx8(flagPresent) if flagPresent      => "-nomx8"
        case YosysSynthGatemateStep.NoClkBuf(flagPresent) if flagPresent   => "-noclkbuf"
      }
      .mkString(" ")

    val verilogNetlist = workingDir.resolve("synthesized_netlist.v")
    val yosysJsonNetlist = workingDir.resolve("synthesized_netlist.json")
    val statJsonPath = workingDir.resolve("synthesis_stat.json")

    // Remove previous generated files
    for (path <- Seq(verilogNetlist, yosysJsonNetlist, statJsonPath)) {
      val file = path.toFile()
      if (file.exists()) {
        file.delete()
      }
    }

    val yosysCmd = Seq(
      toolLocation,
      "-p",
      "\n"
        + s"$readVerilogCommands;\n"
        + s"synth_gatemate -top ${input.topName} $synthArgs;\n"
        + s"write_json ${yosysJsonNetlist};\n"
        + s"write_verilog ${verilogNetlist}\n"
        + s"tee -q -o $statJsonPath stat -json"
    )

    val runtimeMetricHandler = new RuntimeMetricLineHandler()

    val exitStatus = StepProcessRunner.run(
      this,
      yosysCmd,
      workingDir,
      context.lineHandlers :+ runtimeMetricHandler
    )

    if (exitStatus != 0) {
      throw new StepExecutionException(this, s"process exited with $exitStatus")
    }

    val metrics = Seq.newBuilder[Metric]

    metrics ++= runtimeMetricHandler.metrics

    val statData = {
      val in = Files.newInputStream(statJsonPath)
      try ujson.read(in)
      finally in.close()
    }

    var bram20kCount = 0

    for ((cellType, value) <- statData("design")("num_cells_by_type").obj) {
      value.numOpt.foreach { num =>
        cellType match {
          case "CC_DFF"                      => metrics += FlipFlopCount(num.toInt)
          case "CC_LATCH"                    => metrics += LatchCount(num.toInt)
          case "CC_IBUF"                     => metrics += InputBufferCount(num.toInt)
          case "CC_OBUF"                     => metrics += OutputBufferCount(num.toInt)
          case "CC_IOBUF"                    => metrics += BidirectionalBufferCount(num.toInt)
          case "CC_MULT"                     => metrics += MultiplierCount(num.toInt)
          case "CC_BUFG"                     => metrics += ClockBufferCount(num.toInt)
          case "CC_BRAM_20K"                 => bram20kCount += num.toInt
          case "CC_BRAM_40K" | "CC_FIFO_40K" => bram20kCount += num.toInt * 2
          case _                             =>
        }
      }
    }

    metrics += BramCount(bram20kCount)

    // TODO in yosys, why design.num_memories is 0 ?

    (
      metrics.result(),
      SynthesizedNetlist(
        verilog = Some(verilogNetlist),
        yosysJson = Some(yosysJsonNetlist)
      )
    )
  }

  private class RuntimeMetricLineHandler(var metrics: ListBuffer[Metric] = ListBuffer.empty[Metric])
      extends LineHandler {

    // Parse yosys-style output: "End of script. Logfile hash: ..., time: 0.10s, user: 0.06s, system: 0.05s, MEM: 151.36 MB peak"
    val pattern =
      """End of script.*time: (\d+\.\d+)s, user: (\d+\.\d+)s, system: (\d+\.\d+)s, MEM: (\d+\.\d+) MB peak""".r

    override def readLine(line: String): Unit = {

      pattern.findFirstMatchIn(line) match {
        case Some(m) =>
          metrics += CpuTime(m.group(2).toFloat + m.group(3).toFloat)
          metrics += PeakMemoryUsage(m.group(4).toFloat * 1024 * 1024)
        case None =>
      }
    }
  }

  override def createFilteredLineHandler(stream: PrintStream): LineHandler = {
    class Handler(stream: PrintStream) extends LineHandler {
      var step = "begin"

      override def readLine(line: String): Unit = {
        if (line.startsWith("Warning: ") | line.startsWith("ERROR: ")) {
          stream.println(line)
          return
        }

        step match {
          case "begin" =>
            if (line.contains("2.53. Printing statistics.")) {
              stream.println(line)
              step = "statistics"
            }
          case "statistics" => {
            if (line.contains("2.54. Executing CHECK pass")) {
              step = "end"
            } else {
              stream.println(line)
            }
          }
          case "end" =>
        }
      }
    }
    new Handler(stream)
  }

}

object YosysSynthGatemateStep {
  case class UseLutTree(flagPresent: Boolean = true) extends Parameter(flagPresent) {
    override def name(): String = "-luttree"
    override def description(): String = "Presence of the -luttree command line argument"
  }
  case class NoMx8(flagPresent: Boolean = true) extends Parameter(flagPresent) {
    override def name(): String = "-nomx8"
    override def description(): String =
      "Presence of the flag -nomx8, do not use CC_MX8 multiplexer cells in output netlist."
  }

  case class NoClkBuf(flagPresent: Boolean = true) extends Parameter(flagPresent) {
    override def name(): String = "-noclkbuf"
    override def description(): String =
      "Presence of the flag -noclkbuf, to disable automatic clock buffer insertion."
  }
}

class PlaceRouteGatemateStep(
    toolLocation: String = "nextpnr-himbaechel",
    gitReposPath: Option[Path] = None
) extends FlowToolStep[SynthesizedNetlist, RoutedDesign](toolLocation, gitReposPath) {

  val (cachedVersion, cachedExtendedVersion) = StepProcessRunner.queryVersionInfos(
    Seq(toolLocation, "--version"),
    s => {
      val version = """nextpnr-([\w.-]+)\)""".r
        .findFirstMatchIn(s)
        .map(_.group(1))
      (version, version)
    }
  )
  def name() = "place_route"
  def version() = cachedVersion
  def extendedVersion() = cachedExtendedVersion

  def invoke(
      context: StepContext,
      input: SynthesizedNetlist,
      arguments: Seq[Parameter]
  ): (Seq[Metric], RoutedDesign) = {
    val workingDir = context.getAbsoluteWorkingDir()

    val netlistJson = input.yosysJson
      .getOrElse(
        throw new RuntimeException("SynthesizedNetlist must have a yosysJson for nextpnr")
      )
      .toAbsolutePath()

    // Build command arguments
    val cmdArgs = Seq.newBuilder[String]
    cmdArgs += toolLocation

    // Extract parameters
    var targetFreqOpt: Option[Double] = None
    var fpgaModeOpt: Option[String] = None
    var timeModeOpt: Option[String] = None
    None
    var sdcOpt: Option[Path] = None
    var ccfOpt: Option[Path] = None

    for (arg <- arguments) {
      arg match {
        case Seed(seed) =>
          cmdArgs += "--seed"
          cmdArgs += seed.toString()
        case PlaceRouteGatemateStep.TargetFrequency(hz) =>
          targetFreqOpt = Some(hz)
          cmdArgs += "--freq"
          cmdArgs += (hz / 1e6).toString // Convert Hz to MHz
        case PlaceRouteGatemateStep.FpgaMode(mode) =>
          fpgaModeOpt = Some(mode)
          cmdArgs += "-o"
          cmdArgs += s"fpga_mode=$mode"
        case PlaceRouteGatemateStep.TimeMode(mode) =>
          timeModeOpt = Some(mode)
          cmdArgs += "-o"
          cmdArgs += s"time_mode=$mode"
        case PlaceRouteGatemateStep.ConstraintFileSdc(path) =>
          sdcOpt = Some(path)
          cmdArgs += "--sdc"
          cmdArgs += path.toAbsolutePath.toString
        case PlaceRouteGatemateStep.ConstraintFileCcf(path) =>
          ccfOpt = Some(path)
          cmdArgs += "-o"
          cmdArgs += s"ccf=${path.toAbsolutePath}"
        case argParam: ExecutableArgumentParameter =>
          cmdArgs ++= argParam.toArgumentSeq()
        case p: Parameter =>
          throw new InvalidParameterException(
            "Parameter " + p
              .name() + " of class " + p.getClass().getCanonicalName() + "not supported"
          )
      }
    }

    // Add netlist input
    cmdArgs += "--json"
    cmdArgs += netlistJson.toString

    // Add report output (constant)
    val reportPath = workingDir.resolve(PlaceRouteGatemateStep.ReportFileName).toAbsolutePath()
    cmdArgs += "--report"
    cmdArgs += reportPath.toString
    cmdArgs += "--detailed-timing-report"

    // Allow unconstrained I/Os only if no constraint files are provided
    if (sdcOpt.isEmpty && ccfOpt.isEmpty) {
      cmdArgs += "-o"
      cmdArgs += "allow-unconstrained"
    }

    // Output routed netlist
    val bitStreamConfPath = workingDir.resolve("bitstream_conf.txt").toAbsolutePath()
    cmdArgs += "-o"
    cmdArgs += s"out=${bitStreamConfPath}"

    val routedNetlistPath = workingDir.resolve("routed_netlist.json").toAbsolutePath()
    cmdArgs += "--write"
    cmdArgs += routedNetlistPath.toString()

    // Remove previous generated files
    for (path <- Seq(reportPath, bitStreamConfPath, routedNetlistPath)) {
      val file = path.toFile()
      if (file.exists()) {
        file.delete()
      }
    }

    val command = cmdArgs.result()

    val exitStatus = StepProcessRunner.run(this, command, workingDir, context.lineHandlers)

    if (exitStatus != 0) {
      throw new StepExecutionException(this, s"process exited with $exitStatus")
    }

    (
      Nil,
      RoutedDesign(Some(bitStreamConfPath), Some(routedNetlistPath))
    )
  }

  override def createFilteredLineHandler(stream: PrintStream): LineHandler = {
    class Handler(stream: PrintStream) extends LineHandler {
      var step = "begin"

      var charCounter = 0

      override def readLine(line: String): Unit = {
        if (line.startsWith("Warning: ") | line.startsWith("ERROR: ")) {
          stream.println(line)
          return
        }

        step match {
          case "begin" =>
            if (line.startsWith("Info: Device utilisation:")) {
              stream.println(line)
              step = "utilisation"
            }
          case "utilisation" => {
            if ("""^\s*$""".r.findFirstMatchIn(line).nonEmpty) {
              step = "main_analytical_placer"
            } else {
              stream.println(line)
            }
          }
          case "main_analytical_placer" => {
            if (
              line.startsWith(
                "Info: Running main analytical placer, max placement attempts per cell"
              )
            ) {
              stream.println(line)
              charCounter = 0
              step = "placer_iterations"
            }
          }
          case "placer_iterations" => {
            if (line.contains("at iteration #")) {
              if (charCounter < 100) {
                stream.print(".")
                stream.flush()
              } else {
                stream.println(".")
                charCounter = 0
              }
            } else if (
              line.startsWith("Info: Running simulated annealing placer for refinement.")
            ) {
              stream.println("\n" + line)
              charCounter = 0
            } else if (line.startsWith("Info: Running main router loop...")) {
              stream.println("\n" + line)
              step = "router_iterations"
              charCounter = 0
            }
          }
          case "router_iterations" => {
            if (line.startsWith("Info:     iter=")) {
              if (charCounter < 100) {
                stream.print(".")
                stream.flush()
              } else {
                stream.println(".")
              }
            } else if (line.startsWith("Info: Critical path report for ")) {
              stream.println("\n" + line)
              step = "critical_path_report"

            }
          }
          case "critical_path_report" => {
            if (line.startsWith("Info: Slack histogram")) {
              step = "end"
            } else {
              stream.println(line)
            }
          }
          case "end" =>
        }
      }
    }

    new Handler(stream)

  }
}
object PlaceRouteGatemateStep {
  val ReportFileName = "place_and_route_report.json"

  case class Device(device: String) extends ExecutableArgumentParameter(device) {
    override def description(): String = "FPGA --device flag (e.g., CCGM1A1)"
  }

  case class TargetFrequency(freqHz: Double) extends Parameter(freqHz) {
    override def name(): String = "target_frequency"
    override def description(): String = "Target frequency in Hz"
    override def unit(): String = "Hz"
  }

  case class FpgaMode(mode: String) extends Parameter(mode) {
    override def name(): String = "fpga_mode"
    override def description(): String = "-o fpga_mode= flag: lowpower, economy, or speed"
  }

  case class TimeMode(mode: String) extends Parameter(mode) {
    override def name(): String = "time_mode"
    override def description(): String = "-o time_mode= flag: best, typical, or worst"
  }

  case class Router(algorithm: String) extends ExecutableArgumentParameter(algorithm) {
    override def description(): String = "--router algorithm: default, router1, router2"
  }

  case class PlacerHeapAlpha(alpha: Double = 0.1) extends ExecutableArgumentParameter(alpha) {
    override def description(): String =
      "Coefficient for the placer heap alpha value between 0 and 1" // TODO better
  }

  case class PlacerHeapBeta(beta: Double = 0.9) extends ExecutableArgumentParameter(beta) {
    override def description(): String =
      "Coefficient for the placer heap maximum placement density between 0 and 1" // TODO better
  }

  case class PlacerHeapCritexp(exponent: Long = 2) extends ExecutableArgumentParameter(exponent) {
    override def description(): String =
      "Coefficient for the placer placer heap criticality exponent" // TODO better
  }

  case class PlacerHeapTimingweight(weight: Long = 10) extends ExecutableArgumentParameter(weight) {
    override def description(): String =
      "Coefficient for the placer heap timing weight" // TODO better
  }

  case class ConstraintFileSdc(path: Path) extends Parameter(path) {
    override def name(): String = "sdc_file"
    override def description(): String = "--sdc SDC timing constraints file"
  }

  case class ConstraintFileCcf private (absolutePath: Path)
      extends Parameter(absolutePath.toString()) {
    override def name(): String = "ccf_file"
    override def description(): String = "-o ccf= Constraints file (CCF format)"
  }

  object ConstraintFileCcf {

    def fromCurrentPath(path: String) = ConstraintFileCcf(Paths.get(path).toAbsolutePath())

    // Constructor to create a ConstraintFileCcf from the class directory
    def fromPackageDir(
        prefix: String,
        obj: Object,
        suffix: String = "constraints.ccf"
    ): ConstraintFileCcf = {
      ConstraintFileCcf(
        Paths
          .get(
            prefix,
            obj
              .getClass()
              .getPackage()
              .getName()
              .replace(".", "/"),
            suffix
          )
          .toAbsolutePath()
      )

    }
  }
}

/** Step to transform an GateMate implementation file (.txt) into a bitstream (.bit) */
class GmpackStep(
    toolLocation: String = "gmpack",
    gitReposPath: Option[Path] = None
) extends FlowToolStep[RoutedDesign, Bitstream](toolLocation, gitReposPath) {

  val (cachedVersion, cachedExtendedVersion) = StepProcessRunner.queryVersionInfos(
    Seq(toolLocation, "--help"),
    s => {
      val version = """Version v([^\s]+)""".r
        .findFirstMatchIn(s)
        .map(_.group(1))
      (version, Some(s))
    }
  )

  def name() = "pack_bitstream"
  def version() = cachedVersion
  def extendedVersion() = cachedExtendedVersion

  def invoke(
      context: StepContext,
      input: RoutedDesign,
      arguments: Seq[Parameter]
  ): (Seq[Metric], Bitstream) = {
    val workingDir = context.getAbsoluteWorkingDir()

    val configPath = input.bitstream
      .getOrElse(
        throw new RuntimeException("RoutedDesign must have a bitstream config file for gmpack")
      )
      .toAbsolutePath()

    // Build output bitstream path
    val outputBitPath = workingDir.resolve("output.bit").toAbsolutePath()

    // Build command arguments
    val cmdArgs = Seq.newBuilder[String]
    cmdArgs += toolLocation
    cmdArgs += configPath.toString
    cmdArgs += outputBitPath.toString

    // Extract optional parameters
    for (arg <- arguments) {
      arg match {
        case GmpackStep.Verbose(flagPresent) if flagPresent =>
          cmdArgs += "--verbose"
        case GmpackStep.Reset(flagPresent) if flagPresent =>
          cmdArgs += "--reset"
        case GmpackStep.CrcMode(mode) =>
          cmdArgs += "--crcmode"
          cmdArgs += mode
        case GmpackStep.SpiMode(mode) =>
          cmdArgs += "--spimode"
          cmdArgs += mode
        case GmpackStep.Reconfig(flagPresent) if flagPresent =>
          cmdArgs += "--reconfig"
        case GmpackStep.Background(flagPresent) if flagPresent =>
          cmdArgs += "--background"
        case GmpackStep.BootAddr(addr) =>
          cmdArgs += "--bootaddr"
          cmdArgs += addr
        case argParam: ExecutableArgumentParameter =>
          cmdArgs ++= argParam.toArgumentSeq()
        case p: Parameter =>
          throw new InvalidParameterException(
            "Parameter " + p
              .name() + " of class " + p.getClass().getCanonicalName() + " not supported"
          )
      }
    }

    val command = cmdArgs.result()

    // Remove previous generated file
    val file = outputBitPath.toFile()
    if (file.exists()) {
      file.delete()
    }

    val exitStatus = StepProcessRunner.run(this, command, workingDir, context.lineHandlers)

    if (exitStatus != 0) {
      throw new StepExecutionException(this, s"process exited with $exitStatus")
    }

    // Verify the output file exists
    if (!outputBitPath.toFile().exists()) {
      throw new StepExecutionException(
        this,
        s"output bitstream file not generated at $outputBitPath"
      )
    }

    (Nil, Bitstream(outputBitPath))
  }

  override def createFilteredLineHandler(stream: PrintStream): LineHandler = {
    class Handler(stream: PrintStream) extends LineHandler {
      override def readLine(line: String): Unit = {
        // For gmpack, we typically want to see warnings and errors
        if (
          line.startsWith("Warning: ") || line.startsWith("ERROR: ") || line.startsWith("Error: ")
        ) {
          stream.println(line)
        }
        // Also show info messages
        else if (line.startsWith("Info: ")) {
          stream.println(line)
        }
      }
    }
    new Handler(stream)
  }
}

object GmpackStep {
  case class Verbose(flagPresent: Boolean = true) extends Parameter(flagPresent) {
    override def name(): String = "verbose"
    override def description(): String = "Enable verbose output"
  }

  case class Reset(flagPresent: Boolean = true) extends Parameter(flagPresent) {
    override def name(): String = "reset"
    override def description(): String = "Reset all configuration latches with CMD_CFGRST"
  }

  case class CrcMode(mode: String) extends Parameter(mode) {
    override def name(): String = "crcmode"
    override def description(): String = "CRC error behaviour (check, ignore, unused)"
  }

  case class SpiMode(mode: String) extends Parameter(mode) {
    override def name(): String = "spimode"
    override def description(): String = "SPI Mode to use (single, dual, quad)"
  }

  case class Reconfig(flagPresent: Boolean = true) extends Parameter(flagPresent) {
    override def name(): String = "reconfig"
    override def description(): String = "Enable reconfiguration in bitstream"
  }

  case class Background(flagPresent: Boolean = true) extends Parameter(flagPresent) {
    override def name(): String = "background"
    override def description(): String = "Enable background reconfiguration in bitstream"
  }

  case class BootAddr(addr: String) extends Parameter(addr) {
    override def name(): String = "bootaddr"
    override def description(): String = "Boot address for secondary bitstream"
  }
}

/** Step to load a bitstream with openFPGALoader
  *
  * See `openFPGALoader --help`
  */
class OpenFpgaLoaderStep(
    toolLocation: String = "openFPGALoader",
    gitReposPath: Option[Path] = None
) extends FlowToolStep[Bitstream, Unit](toolLocation, gitReposPath) {

  val (cachedVersion, cachedExtendedVersion) = StepProcessRunner.queryVersionInfos(
    Seq(toolLocation, "--Version"),
    s => {
      val version = """v(\d+\.\d+\.\d+)""".r
        .findFirstMatchIn(s)
        .map(_.group(1))
      (version, Some(s))
    }
  )

  def name() = "load_bitstream"
  def version() = cachedVersion
  def extendedVersion() = cachedExtendedVersion

  def invoke(
      context: StepContext,
      input: Bitstream,
      arguments: Seq[Parameter]
  ): (Seq[Metric], Unit) = {
    val workingDir = context.getAbsoluteWorkingDir()

    val bitstreamPath = input.bitFile.toAbsolutePath()

    // Build command arguments
    val cmdArgs = Seq.newBuilder[String]
    cmdArgs += toolLocation

    // Process all arguments
    for (arg <- arguments) {
      arg match {
        case argParam: ExecutableArgumentParameter =>
          cmdArgs ++= argParam.toArgumentSeq()
        case p: Parameter =>
          throw new InvalidParameterException(
            "Parameter " + p
              .name() + " of class " + p.getClass().getCanonicalName() + " not supported"
          )
      }
    }

    // Add bitstream file
    cmdArgs += bitstreamPath.toString

    val command = cmdArgs.result()

    val exitStatus = StepProcessRunner.run(this, command, workingDir, context.lineHandlers)

    if (exitStatus != 0) {
      throw new StepExecutionException(this, s"process exited with $exitStatus")
    }

    (Nil, ())
  }

  override def createFilteredLineHandler(stream: PrintStream): LineHandler = {
    class Handler(stream: PrintStream) extends LineHandler {
      override def readLine(line: String): Unit = {
        // Show warnings, errors, and progress info
        val pattern = ".*(not found|failed)|^Load|Writing:|Erasing:".r
        if (pattern.findFirstIn(line).isDefined) {
          stream.println(line)
        }

        if (line.contains("not found") || line.contains("failed") || line.startsWith("Load")) {
          stream.println(line)
        }
      }
    }
    new Handler(stream)
  }
}

object OpenFpgaLoaderStep {
  case class Cable(cable: String) extends ExecutableArgumentParameter(cable) {
    override def description(): String = "JTAG cable name"
  }

  case class Board(board: String) extends ExecutableArgumentParameter(board) {
    override def description(): String = "Board name, may be used instead of cable"
  }

  case class Frequency(hz: Double) extends ExecutableArgumentParameter(hz) {
    override def description(): String = "JTAG clock frequency"
    override def flagString(): String = "--freq"
    override def unit(): String = "Hz"
    override protected def valueToArgumentString() = hz.toString
  }

  case class IndexChain(index: Int) extends ExecutableArgumentParameter(index) {
    assert(index >= 0)
    override def description(): String = "Index of the device in the JTAG chain"
  }

  case class VerboseLevel(level: VerboseLevel.Level = VerboseLevel.Normal)
      extends ExecutableArgumentParameter(level) {

    override def description(): String = "Verbose level"
  }

  object VerboseLevel {
    sealed trait Level {
      def value: Int
      override def toString(): String = value.toString
    }
    case object Quiet extends Level { val value = -1 }
    case object Normal extends Level { val value = 0 }
    case object Verbose extends Level { val value = 1 }
    case object Debug extends Level { val value = 2 }
  }

  case class WriteFlash(flagPresent: Boolean = true)
      extends ExecutableArgumentParameter(
        flagPresent
      ) {
    override def description(): String = "Write bitstream in flash (not present do not write)"
    override def toArgumentSeq(): Seq[String] = {
      if (flagPresent) Seq("--write-flash") else Seq()
    }
  }
}
