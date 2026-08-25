package brenay.lib.flows

import spinal.core.internals.MemTopology
import spinal.core.{MemBlackboxingPolicy, SpinalConfig}

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.nio.file.{Files, Path, Paths}
import scala.util.matching.Regex

import brenay.lib.flows.LogLevel.{Filtered, Full, Quiet}
import brenay.lib.gatemate.ReplaceBufferCCPhase

//import spinal.core.Device.COLOGNE_CHIP

/** A configuration of a flow step
  */
abstract class Parameter(value: Any) {
  def name(): String
  def description(): String
  def unit(): String = ""
}

/** A value what describe the performance of execution or of the output of
  * a flow step.
  * `value` should be replaced in the extending class by the exact type and
  * passed to the Metric constructor. The extending class constructor should
  * have only one value.
  */
abstract class Metric(value: Any) {

  /** A short name using snake_case */
  def name(): String

  /** A long description, with details.
    * Should start with a Capital letter. If it is full sentences, they should
    * finish with a dot.
    * Single line returns should only be added for paragraphs.
    */
  def description(): String

  /** The unit, converted to SI base unit without multiplicands.
    */
  def unit(): String = ""

  def value(): Any = this.value

}

trait LineHandler {
  def readLine(line: String): Unit
}

class RegexLineHandler(stream: PrintStream, regex: Regex) extends LineHandler {

  override def readLine(line: String) = {
    regex.findFirstMatchIn(line) match {
      case Some(value) => stream.println(value)
      case None        =>
    }
  }
}

class PrintStreamLineHandler(stream: PrintStream) extends LineHandler {
  def readLine(line: String): Unit = {
    stream.println(line)
    if (line.length() == 1) {
      // to allow progression with single char
      stream.flush()
    }
  }
}

class OneDotPerLineHandler() {
  var dotLineCount = 0
  val symbol = "."
  def readLine(line: String): Unit = {
    if (dotLineCount < 80) {
      print(symbol)
      System.out.flush()
    } else {
      println(symbol)
    }
  }
}

sealed abstract class LogLevel(val id: String)

object LogLevel {

  /** Full stdout+stderr of the processes */
  case object Full extends LogLevel("full")

  /** Each step display that is most relevant, like e.g. final usage, fmax */
  case object Filtered extends LogLevel("filtered")

  /** Do not output anything, only exception will be thrown */
  case object Quiet extends LogLevel("quiet")

}

class StepExecutionException(step: FlowToolStepBase, reason: String) extends RuntimeException {

  override def toString(): String = {
    s"the ${step.name} step failed: $reason, (${step.getClass().getCanonicalName()})"
  }
}

/** Allows to query where to store the result of flow steps, the user messaging and
  * and up to when to stop.
  *
  * For example a normal build for a target pcb could be done in a single
  * directory and overwrite older files and output to stdout.
  * Or a benchmark controller could create a new GUID dir for each run
  * and store the result in a database.
  */
abstract class StepContext {
  protected var printStreamOpt: Option[PrintStream] = None

  var executedStepVal: FlowToolStepBase = null

  /**
    */
  protected def executedStep() = {
    if (this.executedStepVal == null) {
      throw new RuntimeException("executedStep() should only be used during step execution")
    }
    this.executedStepVal
  }

  def execute[I, O](
      step: FlowToolStep[I, O],
      input: I,
      arguments: Seq[Parameter] = Nil
  ): FlowToolStepExecution[I, O] = {
    this.executedStepVal = step
    prepareForStep()
    val (metrics, output) = try {
      val (metrics, output) = step.invoke(this, input, arguments)
      reportExecution(arguments, metrics)
      (metrics, output)
    } finally {
      finalizeAfterStep()
      this.executedStepVal = null
    }

    FlowToolStepExecution(this, step, input, arguments, metrics, output)
  }

  /** called at the beginning of execute */
  def prepareForStep(): Unit

  /** cleanup after step execution and result storing, even in case of exception */
  def finalizeAfterStep(): Unit

  def getWorkingDir(): Path

  def getAbsoluteWorkingDir(): Path =
    getWorkingDir().toAbsolutePath().normalize()

  /** Return a Seq of process line handlers to log to files, or write to stdout
    * They should be created in prepareForStep and closed in finalizeAfterStep.
    */
  def lineHandlers: Seq[LineHandler]

  /** This is called at the end of execute() so the handler can */
  protected def reportExecution(
      arguments: Seq[Parameter],
      metrics: Seq[Metric]
  ): Unit

  /** In a linear chain of steps, return a fresh context for the next step */
  def createNextContext(): StepContext

}

/** A context that use a single dir for every steps.
  *
  * This is the typical case of a non-benchmarking build for a HW target.
  */
class SingleDirFlowContext(workingDir: Path, logLevel: LogLevel = LogLevel.Filtered)
    extends StepContext {

  var logFileStream: Option[PrintStream] = None
  var lineHandlersSeq: Seq[LineHandler] = Nil

  override def prepareForStep() = {
    val file = getAbsoluteWorkingDir().resolve(executedStep().name() + ".out").toFile()
    val stream = new PrintStream(file)

    this.logFileStream = Some(stream)
    val logFileHandler = new PrintStreamLineHandler(stream)

    lineHandlersSeq = logLevel match {
      case Full     => Seq(logFileHandler, new PrintStreamLineHandler(System.out))
      case Filtered => Seq(logFileHandler, executedStep().createFilteredLineHandler(System.out))
      case Quiet    => Seq(logFileHandler)
    }

  }

  override def finalizeAfterStep() = {
    this.logFileStream.foreach(s => s.close())
    this.lineHandlersSeq = Nil
  }

  def lineHandlers: Seq[LineHandler] = lineHandlersSeq

  override protected def reportExecution(
      arguments: Seq[Parameter],
      metrics: Seq[Metric]
  ): Unit = ()

  override def createNextContext(): StepContext = new SingleDirFlowContext(workingDir, logLevel)

  def getWorkingDir(): Path = workingDir

}

/** A base, non [I, O] parametrized of a FlowToolStep.
  *
  * This allows for example StepRunner.run() to take a step what is not [I, O]
  * parametrized.
  */
abstract class FlowToolStepBase(toolLocation: String, gitReposPath: Option[Path] = None) {
  def name(): String

  /** Return a compact string of the version.
    * Should be descriptive enough, but short for graphs and reports, like
    * `git describe --tags --dirty`.
    */
  def version(): String

  /** Return an very descriptive tool version.
    * Could include thing like compile flags, build date, architecture.
    * Should be at least as descriptive as version().
    */
  def extendedVersion(): String
  def toolLocation(): String = toolLocation
  def gitReposPath(): Option[Path] = gitReposPath

  def createFilteredLineHandler(stream: PrintStream): LineHandler
}

/** A step in a flow, than a concrete step should implement
  *
  * @param toolLocation This is a reference to find the tool. It can be a
  *                     executable name in the PATH, a path to the executable,
  *                     a path to the installation directory. See the implementation
  *                     documentation about what is accepted.
  * @param gitReposPath optionally a path to the git repos of the tool to get
  *                     metadata about it
  */
abstract class FlowToolStep[I, O](toolLocation: String, gitReposPath: Option[Path] = None)
    extends FlowToolStepBase(toolLocation, gitReposPath) {

  /** Use the step to generate an output based on an input and arguments */
  def invoke(controller: StepContext, input: I, arguments: Seq[Parameter]): (Seq[Metric], O)

}

/** An completed execution of a step with it input, metrics and output */
case class FlowToolStepExecution[I, O](
    context: StepContext,
    toolStep: FlowToolStep[I, O],
    input: I,
    arguments: Seq[Parameter],
    metrics: Seq[Metric],
    output: O
) {
  def thenExecute[NextO](
      nextStep: FlowToolStep[O, NextO],
      arguments: Seq[Parameter] = Nil
  ): FlowToolStepExecution[O, NextO] = {
    val context = this.context.createNextContext()

    context.execute(nextStep, this.output, arguments)

  }
}

final case class CpuTime(seconds: Float) extends Metric(seconds) {
  override def name(): String = "cpu_time"
  override def description(): String = "Total CPU time used by the tool process (user + system)"
  override def unit(): String = "s"
}

final case class PeakMemoryUsage(byteCount: Float) extends Metric(byteCount) {
  override def name(): String = "peak_memory"
  override def description(): String = "Peak memory usage of the tool process in bytes."
  override def unit(): String = "B"
}

/** A helper class to allow to quickly write argument from class name */
abstract class ExecutableArgumentParameter(
    val argValue: Any,
    /** if space toArgumentSeq()  ("--my-flag", "1.0"), if "=", (--my-flag=1.0) */
    flagValueSeparator: String = " ",
    /** if "_" and inherited class is MyFlag, toArgumentSeq() return ("--my_flag", "1.0") */
    argWordSeparator: String = "-",
    prefix: String = "--"
) extends Parameter(argValue) {
  override def name(): String = {
    this.getClass.getSimpleName().replaceAll("([A-Z])", "_$1").toLowerCase().drop(1)
  }

  /** Can be used to generate different second part in toArgumentSeq() */
  protected def valueToArgumentString() = argValue.toString()

  /** Can be used to generate different first part in toArgumentSeq() */
  protected def flagString() = {
    prefix + this.getClass
      .getSimpleName()
      .replaceAll("([A-Z])", argWordSeparator + "$1")
      .toLowerCase()
      .drop(1)
  }

  def toArgumentSeq(): Seq[String] = {
    if (flagValueSeparator == " ") {
      Seq(flagString(), valueToArgumentString())
    } else {
      Seq(flagString() + flagValueSeparator + valueToArgumentString())
    }
  }

}

/** The input a synthesis, several different files (.v, .vhdl) what the synthesizer will understand. */
case class RtlDesign(topName: String, hdlFiles: Seq[Path], constraintFiles: Seq[Path] = Nil)

/** The output of Place & Route */
case class RoutedDesign(bitstream: Option[Path], routedNetlist: Option[Path] = None)

/** The output of bitstream packing, containing the final .bit file */
case class Bitstream(bitFile: Path)

object RoutedDesign {
  final case class FMax(f: Float) extends Metric(f) {
    override def name(): String = "f_max"
    override def description(): String =
      "Maximum routed frequency, recalculated back from Worst Negative Slack if necessary"
    override def unit(): String = "Hz"
  }
}

/** An netlist with different alternative outputs from synthesis step
  */
case class SynthesizedNetlist(verilog: Option[Path] = None, yosysJson: Option[Path] = None)

object StepProcessRunner {

  def queryVersionInfos(
      command: Seq[String],
      parser: String => (Option[String], Option[String])
  ): (String, String) = {
    val pb = new java.lang.ProcessBuilder(command: _*)
      .redirectErrorStream(true)

    val process = pb.start()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
      throw new RuntimeException(
        s"Command failed with exit code $exitCode: ${command.mkString(" ")}"
      )
    }

    val output = scala.io.Source.fromInputStream(process.getInputStream).mkString
    val (versionOpt, extendedVersionOpt) = parser(output)

    (versionOpt, extendedVersionOpt) match {
      case (Some(version), Some(extendedVersion)) => (version, extendedVersion)
      case _                                      =>
        throw new RuntimeException(
          s"Version and Extended version were not parsed with command '${command.mkString(" ")}'\n" +
            s"  in output '$output'"
        )
    }
  }

  def run(
      step: FlowToolStepBase,
      command: Seq[String],
      workingDir: Path,
      lineHandlers: Seq[LineHandler] = Nil
  ): Int = {
    Files.createDirectories(workingDir)

    lineHandlers.foreach { handler =>
      handler.readLine(
        "--------------------------------------------------------------------------------"
      )
      handler.readLine(
        s"Run executable for step ${step.name()}"
      )
      handler.readLine(
        "    command: " + command.mkString("'", "' '", "'")
      )
    }

    val pb = new java.lang.ProcessBuilder(command: _*)
      .directory(workingDir.toFile)
      .redirectOutput(ProcessBuilder.Redirect.PIPE) // Redirect to pipe for manual reading
      .redirectErrorStream(true)

    val process = pb.start()

    val reader = new BufferedReader(new InputStreamReader(process.getInputStream))
    try {
      var line = reader.readLine()
      while (line != null) {
        lineHandlers.foreach { handler =>
          handler.readLine(line)
        }
        line = reader.readLine()
      }
    } finally {
      reader.close()
    }

    val exitCode = process.waitFor()

    exitCode

  }

}

/** A seed that should be a positive int32_t */
case class Seed(seed: Int)
    extends Parameter(
      {
        if (seed < 0) {
          throw new IllegalArgumentException(s"a negative seed ($seed) is not allowed")
        }
        seed
      }
    ) {

  override def name(): String = "seed"
  override def description(): String = "A integer seed for some kind of algorithm like P&R"
}

case class VerilogFile private (absolutePath: Path) extends Parameter(absolutePath) {
  override def name(): String = "verilog_file"
  override def description(): String = "Verilog HDL file path (relative, use / separator)"
}

object VerilogFile {
  def fromCurrentPath(path: String) = VerilogFile(Paths.get(path).toAbsolutePath())
}

case class VhdlFile private (absolutePath: Path) extends Parameter(absolutePath) {
  override def name(): String = "vhdl_file"
  override def description(): String = "VHDL file path (relative, use / separator)"
}

object VhdlFile {
  def fromCurrentPath(path: String) = VhdlFile(Paths.get(path).toAbsolutePath())
}

/** Blackbox all memories that are not ROMs.
  *
  * This is needed because SpinalHDL can not blackbox ROMs and throw an error.
  */
object blackboxAllWithoutInitialContent extends MemBlackboxingPolicy {
  override def translationInterest(topology: MemTopology): Boolean = {
    topology.mem.initialContent == null
  }

  override def onUnblackboxable(topology: MemTopology, who: Any, message: String): Unit = {
    generateUnblackboxableError(topology, who, message)
  }
}

class SpinalGenVerilogStep(gitReposPath: Option[Path] = None)
    extends FlowToolStep[() => spinal.core.Component, RtlDesign](
      "spinal.core.SpinalConfig.generateVerilog",
      gitReposPath
    ) {

  // TODO spinal log output form inside the jvm in spinalHDL core
  override def createFilteredLineHandler(stream: PrintStream) =
    new RegexLineHandler(stream, """^.*$""".r)

  def name() = "spinalVerilogGen"
  def version() = "TODO get version of the spinal package"
  def extendedVersion() = "1.14.1, openjdk 17.0.18 2026-01-20"
  def invoke(
      context: StepContext,
      input: () => spinal.core.Component,
      arguments: Seq[Parameter]
  ): (Seq[Metric], RtlDesign) = {
    val config = new SpinalConfig

    // TODO add a parameter for this
    // config.device = COLOGNE_CHIP

    config.addTransformationPhase(new ReplaceBufferCCPhase())

    config.addStandardMemBlackboxing(blackboxAllWithoutInitialContent)

    val workingDir = context.getAbsoluteWorkingDir()
    config.targetDirectory = workingDir.toString()
    val report = config.generateVerilog(input())

    val generatedVerilogFile = workingDir.resolve(report.toplevelName + ".v")

    val additionalHdlFiles = arguments.collect {
      case f: VerilogFile => f.absolutePath
      case f: VhdlFile    => f.absolutePath
    }

    val blackboxDir = Paths.get("./src/main/verilog/gatemate/mem_blackbox/")

    val memBlackboxPaths =
      for (memName: String <- Seq("Ram_1w_1rs.v", "Ram_1w_1ra.v", "Ram_1wrs.v")) yield {
        blackboxDir.resolve(memName)
      }

    val allHdlFiles = Seq(generatedVerilogFile) ++ additionalHdlFiles ++ memBlackboxPaths

    (Seq(), RtlDesign(report.toplevelName, allHdlFiles))
  }
}
