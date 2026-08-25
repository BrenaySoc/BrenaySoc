package brenay.vgasoc

import spinal.core._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.{NodeParameters, Opcode}
import spinal.lib.fsm.{EntryPoint, State, StateMachine}
import spinal.lib.{DownCounter, EndiannessSwap, Flow, master, slave}

import brenay.lib.gatemate.SioSerializerCc
import brenay.lib.gatemate.SioSerializerCc.Cmd

/** A tilelink-UH interface to a external RAM interfaced by a higher frequency serializer
  *
  * The idea is to communicate in parallel with `io.sioBus`, which is connected through
  * cross domain fifos to a serdes such as SioSerializerCc what works at an higher frequency.
  * This soft serdes is simpler an can reach higher fmax. `sioBus` is 32 bit data wide
  * and the Qpi/dual-qpi, can works at 2-4 time the clock rate. This allows higher
  * bandwidth in burst mode.
  *
  * Currently only for 32 bit tilelink bus and QPI access only, on a
  * single Ly68S3200 PSRAM. The atomics Opcode and write access smaller that a
  * word are currently not supported. Only GET and A.PUT_FULL_DATA are currently
  * supported, and for the later the beat must be contiguous.
  *
  * The idea is that in case of tilelink master not providing data fast enough
  * during write or accepting read data, we retry at the address where the
  * QPI access was stopped. In write mode, the SioSerializerCc feed back the
  * data not written so they are stored in the fifo, that should be large enough
  * for a entire tilelink burst.
  *
  * To respect Ly68S3200 "Power-up initialization", The tPu >= 150 us with SCLK
  * low after Vcc rise are fullfil by the FPGA bitstream load that take much more.
  * The one SLCK sample for "Device Reset" is guarantied by the SioSerializerCc latency.
  *
  * @param p tilelink node configuration
  * @param memPIWidth memory parallel access width
  * @param memAddressWidth memory address width in the commands, in bit
  */
class SioRam(
    val p: NodeParameters,
    val memPIWidth: BitCount = 4 bits,
    val memAddressWidth: BitCount = 24 bits,
    val memReadWaitCycles: Int = 6
) extends Component {

  import SioRam.Ly68S3200Cmd

  val busParam = p.toBusParameter()

  val io = new Bundle {
    val up = slave port tilelink.Bus(p)

    val sioBus = master(SioSerializerCc.Bus())

  }

  // register tilelink outputs
  io.up.a.ready.setAsReg() init (False)
  // payload.data is directly workData what is registered
  io.up.d.payload.opcode.setAsReg()
  io.up.d.payload.source.setAsReg()
  io.up.d.payload.size.setAsReg()
  io.up.d.valid.setAsReg() init (False)
  io.up.d.payload.sink.assignDontCare() // in Tilelink-UH any value is valid
  io.up.d.payload.param.clearAll() // spec mandate zero if not used

  assert(busParam.dataWidth == 32, s"bus of ${busParam.dataWidth} bits not supported")

  // We have to register A message because when we accept a tilelink beat, it
  // signals can change afterward (see "4.1. Flow Control Rules" part
  // of tilelink spec).
  // For fields that does not change between beats, we use up.d registered values.
  // val aOpcode = Reg(io.up.a.payload.opcode)

  // working copy that is used as output data
  val outData = Reg(busParam.data)
  if (busParam.withDataD) io.up.d.payload.data := outData

  // buffered input data
  val inData = Reg(busParam.data)
  val inDataValid = Reg(Bool())
  val aDataLast = Reg(Bool())

  // This address is used for write missed words, for smaller that a word accesses
  // and atomic accesses.
  val workAddress = Reg(UInt(memAddressWidth))

  // -----------
  // d value only make sense when valid

  if (busParam.withDataD) io.up.d.payload.corrupt := False // no ECC so can never be corrupt
  io.up.d.payload.denied := False // TODO make access check

  io.sioBus.downStream.payload.setAsReg()
  io.sioBus.downStream.valid.setAsReg() init (False)
  io.sioBus.downStream.payload.minFifoSize := 0 // TODO

  io.sioBus.upStream.ready.setAsReg()

  // This is needed for the Tilelink test framework to work properly.
  // it tell when to read the reference memory can be accessed.
  val testerOrdering = Flow(tilelink.coherent.OrderingCmd(p.sizeBytes))
  testerOrdering.payload.setAsReg
  Component.current.addTag(new tilelink.OrderingTag(testerOrdering))

  // ----------- D channel micro-controller -------------
  // aFsm change `command` and this controller do the operation a set it to Command.None
  // for AckPut there is the `result`

  object Command extends SpinalEnum {
    val None, ReadbackWrite, AckPut, FifoToD, ReadWorkData, DataToD = newElement()
  }

  object Result extends SpinalEnum {
    val Passed, Missed = newElement()
  }

  val command = Reg(Command())
  val result = Reg(Result())

  val dropCounter = DownCounter(log2Up(3) bits)
  val isLast = Reg(Bool())

  def executeAckPut(): Unit = {
    command := Command.AckPut
    io.up.d.valid := True
  }

  def executeReadbackWrite(): Unit = {
    command := Command.ReadbackWrite
    io.sioBus.upStream.ready := True
    io.up.d.valid := False
  }

  def executeFifoToD(dropCount: Int): Unit = {
    io.sioBus.upStream.ready := True
    dropCounter := dropCount
    command := Command.FifoToD
    assert(!io.sioBus.upStream.ready)
    assert(!io.up.d.valid)
  }

  def executeReadWorkData(dropCount: Int): Unit = {
    dropCounter := dropCount
    command := Command.ReadWorkData
  }

  def executeWorkDataToD(): Unit = {
    io.up.d.valid := True
    command := Command.FifoToD
  }

  testerOrdering.valid := False
  switch(command) {
    is(Command.None) {}
    is(Command.AckPut) {
      testerOrdering.valid := io.up.d.ready
      when(io.up.d.fire) {
        io.up.d.valid := False
        command := Command.None
      }
    }

    is(Command.ReadbackWrite) {
      outData.assignDontCare
      when(io.sioBus.upStream.fire) {
        when(io.sioBus.upStream.payload.missed) {
          // TODO more area/fmax efficient to have the mixed endianness from bellow and to redo
          outData := io.sioBus.upStream.payload.data
          io.sioBus.upStream.ready := False
          command := Command.None
          result := Result.Missed
        } otherwise {
          when(io.sioBus.upStream.payload.last) {
            io.sioBus.upStream.ready := False
            command := Command.None
            result := Result.Passed
          }
        }
      }
    }

    is(Command.FifoToD) {
      when(io.sioBus.upStream.fire) {
        assert(memPIWidth.value == 4, "only support for 4 bits for the moment")
        outData := EndiannessSwap(io.sioBus.upStream.payload.data)
        isLast := io.sioBus.upStream.payload.last
      }

      when(dropCounter =/= 0) {
        if (busParam.withDataD) io.up.d.payload.data.assignDontCare
        isLast := False
        assert(io.sioBus.upStream.ready, "set in executeFifoToD()")
        when(io.sioBus.upStream.valid) {
          when(io.sioBus.upStream.last) {
            io.sioBus.upStream.ready := False
            command := Command.None
          }
          dropCounter.decrement
        }
      } otherwise {

        io.up.d.valid := io.sioBus.upStream.fire || io.up.d.isStall
        io.sioBus.upStream.ready := !isLast && io.up.d.ready

        when(io.up.d.fire) {
          when(isLast) {
            command := Command.None
            testerOrdering.valid := True
          }
        }
      }
    }
    is(Command.DataToD) {
      when(io.up.d.fire) {
        io.up.d.valid := False
        command := Command.None
      }
    }

    is(Command.ReadWorkData) {
      io.sioBus.upStream.ready := True
      when(io.sioBus.upStream.fire) {
        when(dropCounter =/= 0) {
          io.sioBus.upStream.ready := True
          outData.assignDontCare
          dropCounter.decrement
        } otherwise {
          io.sioBus.upStream.ready := False
          outData := io.sioBus.upStream.payload.data
          command := Command.None
        }
      }
    }
  }

  // --------- A channel main state machine ------------

  val aFsm = new StateMachine {
    setEncoding(binaryOneHot)

    val aOpcode = Reg(Opcode.A)
    val aSize = Reg(busParam.size)
    val aParam = Reg(Bits(3 bits))
    val aSource = Reg(busParam.source)
    val aMask = Reg(busParam.mask)

    val aBusBeatCounter = DownCounter(
      (busParam.beatWidth + 1).max(2) bits
    )

    val sendEnterQpiState = new State with EntryPoint {
      whenIsActive {
        assert(!io.up.a.ready)

        // serialize ENTER_QPI_MODE on first data wire
        io.sioBus.downStream.payload.data := 0
        assert(busParam.dataWidth == 32)
        var shiftedCmd = Ly68S3200Cmd.EnterQpiMode.value
        for (i <- 0 until busParam.dataWidth / 4) {
          io.sioBus.downStream.payload.data(i * 4) := U(shiftedCmd, 8 bits)(0)
          shiftedCmd = shiftedCmd >> 1
        }

        io.sioBus.downStream.payload.cmd := Cmd.WRITE
        io.sioBus.downStream.payload.byteSizeMinusOne := 3
        io.sioBus.downStream.valid := True
        io.sioBus.downStream.payload.last := True
        executeReadWorkData(dropCount = 0) // dummy read

        goto(receiveEnterQpiState)

      }
    }

    val receiveEnterQpiState = new State {
      whenIsActive {
        io.sioBus.downStream.valid := False
        when(command === Command.None) {
          goto(waitStartNextMessageState)
        }
      }
    }
    val waitStartNextMessageState: State = new State {
      whenIsActive {
        // always the same for a command
        io.sioBus.downStream.payload.cmd := Cmd.WRITE
        io.sioBus.downStream.payload.last := False
        io.sioBus.downStream.payload.byteSizeMinusOne := 3
        io.sioBus.downStream.valid := False

        // We read aligned to the bus with because we always read the bus with bits.
        // This simplify implementation (no mux needed) with a minor penality in read latency.
        io.sioBus.downStream.payload.data(
          0,
          24 bits
        ) := io.up.a.address.asBits.resized
        io.sioBus.downStream.payload.data(0, busParam.dataBytesLog2Up bits) := 0

        // save address for special cases
        workAddress := io.up.a.address.resized

        // buffer data because it can change when !a.valid, and to increase fmax
        if (busParam.withDataA) inData := io.up.a.data
        aOpcode := io.up.a.payload.opcode
        aSize := io.up.a.payload.size
        aParam := io.up.a.payload.param
        aSource := io.up.a.payload.source
        if (busParam.withDataA) aMask := io.up.a.payload.mask

        val sioRamCommand = Bits(8 bits)

        switch(io.up.a.payload.opcode) {
          import Ly68S3200Cmd._
          is(Opcode.A.PUT_FULL_DATA) {
            when(io.up.a.payload.size < 2) {
              // will need read-modify-write
              sioRamCommand := QuadRead.value
            } otherwise {
              sioRamCommand := QuadWrite.value
            }
          }
          is(Opcode.A.PUT_PARTIAL_DATA) {
            sioRamCommand := QuadRead.value
          }

          is(Opcode.A.GET) {
            sioRamCommand := QuadRead.value
          }
          default {
            sioRamCommand.assignDontCare
          }
        }
        io.sioBus.downStream.payload.data(24, 8 bits) := sioRamCommand

        assert(io.sioBus.downStream.ready, "fifo should always be big enough")

        when(command === Command.None) {
          io.up.a.ready := True
          when(io.up.a.fire) {
            assert(io.up.a.payload.address < (U(1) << 24))
            // internal to spinal tilelink test
            testerOrdering.payload.debugId := io.up.a.debugId
            testerOrdering.bytes := (U(1) << io.up.a.size).resized

            io.up.a.ready := False
            io.sioBus.downStream.valid := True
            goto(decodeActionState)
          }
        } otherwise {
          io.up.a.ready := False
        }

      }
    }

    val decodeActionState = new State {
      whenIsActive {
        aBusBeatCounter := ((U(1) << (aSize - 2)) - 1).resized

        io.sioBus.downStream.payload.data.assignDontCare

        // compute known
        io.up.d.payload.size := aSize
        io.up.d.payload.source := aSource
        switch(aOpcode) {
          is(Opcode.A.GET) {
            io.up.d.payload.opcode := Opcode.D.ACCESS_ACK_DATA
          }
          is(Opcode.A.PUT_FULL_DATA) {
            io.up.d.payload.opcode := Opcode.D.ACCESS_ACK
          }
          default {
            io.up.d.payload.opcode.assignDontCare
            assert(False, "opcode not implemented")
          }
        }

        assert(io.sioBus.downStream.valid, "set in previous state")
        assert(!io.up.a.ready)

        when(aOpcode === Opcode.A.GET || ((aOpcode === Opcode.A.PUT_FULL_DATA) && (aSize < 2))) {
          // these operations require a read
          assert(memReadWaitCycles % 2 == 0)
          assert(memReadWaitCycles / 2 <= 4)
          io.sioBus.downStream.payload.byteSizeMinusOne := memReadWaitCycles / 2 - 1
          assert(!io.sioBus.downStream.payload.last, "set in previous state")
          io.sioBus.downStream.payload.cmd := Cmd.READ // mem wait state should be in high-Z
          when(aOpcode === Opcode.A.GET) {
            executeFifoToD(dropCount = 2)
            goto(readState)
          } otherwise {
            executeReadWorkData(dropCount = 2)
            goto(readWorkDataState)
          }
          aDataLast.assignDontCare
        } elsewhen (aOpcode === Opcode.A.PUT_FULL_DATA) {
          if (busParam.withDataA) {
            // Currently we lost 1 cycle of a.ready to simplify the design.
            assert(!io.up.a.ready)
            io.sioBus.downStream.payload.data := EndiannessSwap(inData)
            io.sioBus.downStream.valid := True
            inDataValid := False
            io.sioBus.downStream.payload.last := aSize === 2
            aDataLast.assignDontCare

            assert(aSize >= 2, "write smaller that bus are done elsewhere")
            io.up.a.ready := aSize =/= 2

            executeReadbackWrite()
            goto(writeState)
          } else {
            assert(False, "write capability not present")
          }

        } otherwise {}
      }
    }

    val readWorkDataState: State = new State {
      whenIsActive {
        // io.sioBus.downStream.payload.last := aDataLast
        // TODO
      }
    }

    val writeState: State = busParam.withDataA generate new State {
      whenIsActive {

        io.sioBus.downStream.payload.data := EndiannessSwap(inData)
        io.sioBus.downStream.valid := inDataValid
        io.sioBus.downStream.last := aDataLast
        assert(io.sioBus.downStream.payload.cmd === Cmd.WRITE, "set in previous state")

        assert(io.sioBus.downStream.ready, "largest request should fit in fifo")
        inDataValid := False

        when(io.up.a.fire) {
          workAddress := workAddress + busParam.dataBytes
          inData := io.up.a.payload.data
          inDataValid := True

          when(aBusBeatCounter === 1) {
            io.up.a.ready := False
            aDataLast := True
          } otherwise {
            aBusBeatCounter.decrement()
            aDataLast := False
          }
        }

        when(command === Command.None) {
          switch(result) {
            is(Result.Passed) {
              executeAckPut()
              goto(waitStartNextMessageState)
            }
            is(Result.Missed) {
              goto(startWriteRetryState)
            }
          }
        }
      }
    }

    val startWriteRetryState = new State {
      whenIsActive {
        // TODO
        io.sioBus.downStream.payload.data := workAddress.asBits.resized
        io.sioBus.downStream.payload.cmd := Cmd.WRITE
        io.sioBus.downStream.payload.byteSizeMinusOne := 3
        io.sioBus.downStream.payload.last := False
        io.sioBus.downStream.valid := True
        goto(writeRetryState)
      }
    }

    val writeRetryState = new State {
      whenIsActive {
        // TODO
        io.sioBus.downStream.payload.data := io.sioBus.upStream.payload.data
        io.sioBus.downStream.valid := io.sioBus.upStream.valid
        io.sioBus.downStream.payload.last := io.sioBus.upStream.payload.last

        // Normally check of upstream valid is redundant */
        when(io.sioBus.upStream.valid && io.sioBus.upStream.payload.last) {
          goto(waitStartNextMessageState)
        }
      }
    }

    val readState: State = new State {

      whenIsActive {

        assert(
          io.sioBus.downStream.ready,
          "downStream fifo should be able to contain a full tilelink burst, so its ready should always be true"
        )
        assert(io.sioBus.downStream.payload.cmd === Cmd.READ, "from previous state")
        assert(io.sioBus.downStream.valid, "from previous state")
        io.sioBus.downStream.payload.byteSizeMinusOne := 3
        io.sioBus.downStream.payload.last := aBusBeatCounter === 0
        io.sioBus.downStream.valid := True

        aBusBeatCounter.decrement()

        when(aBusBeatCounter === 0) {
          // io.sioBus.downStream.valid := False
          // this state will start only when command is completed
          goto(waitStartNextMessageState)
        }
      }
    }
  }
}

object SioRam {
  sealed abstract class Ly68S3200Cmd(val value: Int)

  object Ly68S3200Cmd {
    case object Read extends Ly68S3200Cmd(0x03)
    case object FastRead extends Ly68S3200Cmd(0x0b)
    case object QuadRead extends Ly68S3200Cmd(0xeb)
    case object Write extends Ly68S3200Cmd(0x02)
    case object QuadWrite extends Ly68S3200Cmd(0x38)
    case object EnterQpiMode extends Ly68S3200Cmd(0x35)
    case object ExitQpiMode extends Ly68S3200Cmd(0xf5)
  }
}
