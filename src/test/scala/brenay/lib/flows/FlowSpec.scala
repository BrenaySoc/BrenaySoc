package brenay.lib.flows

import spinal.core._

import java.nio.file.{Files, Paths}

import org.scalatest.wordspec.AnyWordSpec

class FlowSpec extends AnyWordSpec {

  "Toolchain flow" should {

    "execute a trivial example" in {

      class TestComponent extends spinal.core.Component {
        val io = new Bundle {
          val input = in(SInt(8 bits))
          val output = out(SInt(8 bits))
        }

        io.output := RegNext(io.input * io.input).resized
      }

      class GateMateFlow() {

        val genStep = new SpinalGenVerilogStep()

        val synthStep = new YosysSynthGatemateStep()
        val placeRouteStep =
          new PlaceRouteGatemateStep()
        val gmpackStep = new GmpackStep()

        val path = Files.createDirectories(Paths.get("./simWorkspace").resolve("GateMateFlow"))

        val context = new SingleDirFlowContext(path)
        val genExecution = context.execute(genStep, () => new TestComponent)

        val synthExecution = genExecution.thenExecute(
          synthStep,
          Seq(YosysSynthGatemateStep.UseLutTree(), YosysSynthGatemateStep.NoMx8())
        )

        info(s"synthStep version: ${synthStep.version()}")

        for (metric <- synthExecution.metrics) {
          info(s"${metric.name()}: ${metric.value} ${metric.unit()}")
        }

        import PlaceRouteGatemateStep._
        val pnrExecution = synthExecution.thenExecute(
          placeRouteStep,
          Seq(
            Seed(Int.MaxValue),
            Device("CCGM1A1"),
            TargetFrequency(100e6),
            FpgaMode("speed"),
            TimeMode("worst"),
            Router("router2")
          )
        )

        info(s"placeRouteStep version: ${placeRouteStep.version()}")

        for (metric <- pnrExecution.metrics) {
          info(s"${metric.name()}: ${metric.value} ${metric.unit()}")
        }

        println(s"routedNetlist: '${pnrExecution.output.routedNetlist.toString()}'")
        println(s"bitstream config: '${pnrExecution.output.bitstream.toString()}'")

        val gmpackExecution = pnrExecution.thenExecute(gmpackStep)

        info(s"gmpackStep version: ${gmpackStep.version()}")

        for (metric <- gmpackExecution.metrics) {
          info(s"${metric.name()}: ${metric.value} ${metric.unit()}")
        }

        println(s"bitstream: '${gmpackExecution.output.bitFile.toString()}'")

        // Verify the bitstream file exists
        assert(
          gmpackExecution.output.bitFile.toFile().exists(),
          s"Bitstream file ${gmpackExecution.output.bitFile} does not exist"
        )

      }
      new GateMateFlow()
    }

    "execute with a single external Verilog file and use it as a blackbox" in {
      class BlackBoxRam(wIDTH: Int = 8, dEPTH: Int = 256) extends BlackBox {
        val clk = in(Bool())
        val din = in(Bits(wIDTH bits))
        val addr = in(UInt(log2Up(dEPTH) bits))
        val wr_en = in(Bool())
        val dout = out(Bits(wIDTH bits))
        setBlackBoxName("BlackBoxRam")
      }

      class TestComponentWithBlackbox extends Component {
        val io = new Bundle {
          val clk = in(Bool())
          val input = in(SInt(8 bits))
          val output = out(SInt(8 bits))
        }

        // Instantiate the blackbox
        val ram = new BlackBoxRam(8, 256)
        ram.clk := io.clk
        ram.din := io.input.asBits
        ram.addr := 0
        ram.wr_en := True

        io.output := ram.dout.asSInt
      }

      val genStep = new SpinalGenVerilogStep()
      val synthStep = new YosysSynthGatemateStep("/opt/oss-cad-suite/bin/yosys")

      val path =
        Files.createDirectories(Paths.get("./simWorkspace").resolve("ExternalBlackboxSingle"))
      val context = new SingleDirFlowContext(path)

      val blackboxRamPath = path.resolve("BlackBoxRam.v")
      Files.write(
        blackboxRamPath,
        """`timescale 1ns/1ps
module BlackBoxRam #(parameter WIDTH=8, parameter DEPTH=256)(
    input clk, input [WIDTH-1:0] din, input [$clog2(DEPTH)-1:0] addr, input wr_en,
    output [WIDTH-1:0] dout
);
    reg [WIDTH-1:0] mem [0:DEPTH-1];
    always @(posedge clk) if (wr_en) mem[addr] <= din;
    assign dout = mem[addr];
endmodule""".getBytes
      )

      val genExecution = context.execute(
        genStep,
        () => new TestComponentWithBlackbox,
        Seq(VerilogFile.fromCurrentPath(path.resolve("BlackBoxRam.v").toString))
      )

      genExecution.thenExecute(
        synthStep,
        Seq(YosysSynthGatemateStep.UseLutTree(), YosysSynthGatemateStep.NoMx8())
      )

      // Verify that the external Verilog file is included in hdlFiles
      assert(genExecution.output.hdlFiles.size >= 2)
      assert(genExecution.output.hdlFiles.exists(_.toString.contains("BlackBoxRam.v")))

      info("Successfully executed with external Verilog blackbox")
      info(s"HDL files: ${genExecution.output.hdlFiles.mkString(", ")}")
    }

    "execute with two external Verilog files used as blackboxes" in {
      class BlackBoxRam(val wIDTH: Int = 8, val dEPTH: Int = 256) extends BlackBox {
        val clk = in(Bool())
        val din = in(Bits(wIDTH bits))
        val addr = in(UInt(log2Up(dEPTH) bits))
        val wr_en = in(Bool())
        val dout = out(Bits(wIDTH bits))
        setBlackBoxName("BlackBoxRam")
      }

      class BlackBoxFifo(val wIDTH: Int = 8, val dEPTH: Int = 16) extends BlackBox {
        val clk = in(Bool())
        val rst = in(Bool())
        val din = in(Bits(wIDTH bits))
        val wr_en = in(Bool())
        val rd_en = out(Bool())
        val dout = out(Bits(wIDTH bits))
        val full = out(Bool())
        val empty = out(Bool())
        setBlackBoxName("BlackBoxFifo")
      }

      class TestComponentWithMultipleBlackboxes extends Component {
        val io = new Bundle {
          val clk = in(Bool())
          val rst = in(Bool())
          val input = in(SInt(8 bits))
          val output = out(SInt(8 bits))
        }

        val ram = new BlackBoxRam(8, 256)
        ram.clk := io.clk
        ram.din := io.input.asBits
        ram.addr := 0
        ram.wr_en := True

        val fifo = new BlackBoxFifo(8, 16)
        fifo.clk := io.clk
        fifo.rst := io.rst
        fifo.din := ram.dout
        fifo.wr_en := True

        io.output := fifo.dout.asSInt
      }

      val genStep = new SpinalGenVerilogStep()
      val synthStep = new YosysSynthGatemateStep("/opt/oss-cad-suite/bin/yosys")

      val path =
        Files.createDirectories(Paths.get("./simWorkspace").resolve("ExternalBlackboxMultiple"))
      val context = new SingleDirFlowContext(path)

      // Create simple blackbox Verilog files in the working directory
      val blackboxRamPath = path.resolve("BlackBoxRam.v")
      Files.write(
        blackboxRamPath,
        """`timescale 1ns/1ps
module BlackBoxRam #(parameter WIDTH=8, parameter DEPTH=256)(
    input clk, input [WIDTH-1:0] din, input [$clog2(DEPTH)-1:0] addr, input wr_en,
    output [WIDTH-1:0] dout
);
    reg [WIDTH-1:0] mem [0:DEPTH-1];
    always @(posedge clk) if (wr_en) mem[addr] <= din;
    assign dout = mem[addr];
endmodule""".getBytes
      )

      val blackboxFifoPath = path.resolve("BlackBoxFifo.v")
      Files.write(
        blackboxFifoPath,
        """`timescale 1ns/1ps
module BlackBoxFifo #(parameter WIDTH=8, parameter DEPTH=16)(
    input clk, input rst, input [WIDTH-1:0] din, input wr_en,
    output rd_en, output [WIDTH-1:0] dout, output full, output empty
);
    reg [WIDTH-1:0] mem [0:DEPTH-1];
    reg [$clog2(DEPTH)-1:0] wr_ptr = 0, rd_ptr = 0;
    assign full = (wr_ptr == rd_ptr) ? 0 : 1;
    assign empty = (wr_ptr == rd_ptr) ? 1 : 0;
    always @(posedge clk) if (wr_en && !full) mem[wr_ptr] <= din;
    assign dout = mem[rd_ptr];
endmodule""".getBytes
      )

      val genExecution = context.execute(
        genStep,
        () => new TestComponentWithMultipleBlackboxes,
        Seq(
          VerilogFile.fromCurrentPath(path.resolve("BlackBoxRam.v").toString),
          VerilogFile.fromCurrentPath(path.resolve("BlackBoxFifo.v").toString)
        )
      )

      genExecution.thenExecute(
        synthStep,
        Seq(YosysSynthGatemateStep.UseLutTree(), YosysSynthGatemateStep.NoMx8())
      )

      // Verify that both external Verilog files are included
      assert(genExecution.output.hdlFiles.size >= 3)
      assert(genExecution.output.hdlFiles.exists(_.toString.contains("BlackBoxRam.v")))
      assert(genExecution.output.hdlFiles.exists(_.toString.contains("BlackBoxFifo.v")))

      info("Successfully executed with two external Verilog blackboxes")
      info(s"HDL files: ${genExecution.output.hdlFiles.mkString(", ")}")
    }
  }
}
