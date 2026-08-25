package brenay.vgasoc

import spinal.core._
import spinal.core.sim.SimMemPimper
import spinal.lib.fsm.{EntryPoint, State, StateMachine}
import spinal.lib.io.TriState
import spinal.lib.{DownCounter, slave}

/** Bit exact simulation model of the Ly68S3200 PSRAM
  *
  * Currently only enter QPI and QPI read and write operation implemented.
  *
  * @param meaningfulOperationAsserted operations that do not make sense like half beat interrupted read,
  *                                    data-less read and write, write pass end of address,
  *                                    non terminated commands are asserted in simulation
  * @param dangerousOperationAsserted operation that lead to unspecified behavior or
  *                                   wrong value (half beat write) are asserted in simulation
  */
class Ly68S3200Model(
    meaningfulOperationAsserted: Boolean = false,
    dangerousOperationAsserted: Boolean = false
) extends Component {
  val io = new Bundle {

    /** chip select active high */
    val cs = in Bool ()
    val sio = slave(TriState(Bits(4 bits)))
  }

  io.sio.read.setAsReg

  private def assertMeaningfulCs(expected: Bool): Unit = {
    if (meaningfulOperationAsserted)
      assert(io.cs === expected, "SioRam invalid use of CS")
  }

  private def assertDangerousCs(expected: Bool): Unit = {
    if (meaningfulOperationAsserted)
      assert(io.cs === expected, "SioRam invalid use of CS")
  }

  private def assertValidWriteEnable(expected: Bool): Unit = {
    if (meaningfulOperationAsserted) {
      assert(io.sio.writeEnable === expected, "SioRam invalid value for writeEnable")
    }
  }

  val mem = new Mem(UInt(8 bits), 1 << 24)
  mem.simPublic

  val fsm = new StateMachine {
    val command = Reg(UInt(8 bits))
    val beatCounter = DownCounter(log2Up(7) bits)
    val address = Reg(UInt(24 bits))

    val idleSpiState: State = new State with EntryPoint {
      whenIsActive {
        when(io.cs) {
          goto(readSpiCommandState)
        }
      }
    }

    val readSpiCommandState = new State {
      onEntry {
        beatCounter := 8 - 1
        command := io.sio.write(0).asUInt.resized
      }
      whenIsActive {
        when(!io.cs) {
          goto(idleSpiState)
        } otherwise {

          val nextCommand = UInt(8 bits)

          nextCommand := command |<< 1 | io.sio.write(0).asUInt.resized
          command := nextCommand

          when(beatCounter === 1) {
            switch(nextCommand) {
              import SioRam.Ly68S3200Cmd._
              is(U(EnterQpiMode.value)) {
                goto(idleQpiState)
              }
            }
          }
        }
        beatCounter.decrement()
      }
    }

    val idleQpiState: State = new State {
      whenIsActive {
        when(io.cs) {
          goto(readQpiCommandState)
        }
      }
    }

    val readQpiCommandState = new State {
      onEntry {
        command := io.sio.write.asUInt.resized
      }

      whenIsActive {
        val nextCommand = UInt(8 bits)

        nextCommand := command |<< 4 | io.sio.write.asUInt.resized

        when(!io.cs) {
          if (meaningfulOperationAsserted) {
            import SioRam.Ly68S3200Cmd._
            assert(beatCounter === 1, "single beat QPI mode command")
            assert(
              beatCounter > 1 || nextCommand === U(ExitQpiMode.value),
              "QPI command with multiple bytes interrupted prematurely"
            )
          }
          goto(idleQpiState)
        } otherwise {

          command := nextCommand

          switch(nextCommand) {
            import SioRam.Ly68S3200Cmd._
            is(U(ExitQpiMode.value)) {
              goto(idleSpiState)
            }
            is(U(QuadWrite.value), U(QuadRead.value)) {
              goto(quadReadAddressState)
            }
            default {
              if (meaningfulOperationAsserted) {
                assert(False, "unsupported QPI command")
              }
            }
          }
        }
      }
    }
    val quadReadAddressState: State = new State {
      onEntry {
        beatCounter := 6 - 1
      }
      whenIsActive {
        assertMeaningfulCs(True)
        when(!io.cs) {
          goto(idleQpiState)
        } otherwise {

          address := address |<< 4 | io.sio.write.asUInt.resized

          when(beatCounter === 0) {
            switch(command) {
              import SioRam.Ly68S3200Cmd._
              is(U(QuadWrite.value)) {
                goto(quadWriteState)
              }
              is(U(QuadRead.value)) {
                goto(quadReadWaitStateState)
              }
              default {}

            }
          }
        }
        beatCounter.decrement()
      }

    }

    val quadWriteState: State = new State {
      val lastBeat = Reg(Bits(4 bits))
      onEntry {
        beatCounter := 2 - 1
      }
      whenIsActive {
        lastBeat := io.sio.write

        if (dangerousOperationAsserted) {
          assert(io.sio.writeEnable, "SioRam writeEnable=0 when writing data")
        }

        when(beatCounter === 0) {
          mem.write(address, (lastBeat ## io.sio.write).asUInt)
          if (meaningfulOperationAsserted) {
            assert(!(address === address.maxValue && io.cs), "QuadWrite address wrap-up")
          }
          address := address + 1

          beatCounter := 1
        }

        when(!io.cs) {
          assertDangerousCs(beatCounter === 0)
          goto(idleQpiState)
        }
        beatCounter.decrement()

      }

    }

    val quadReadWaitStateState = new State {
      onEntry {
        beatCounter := 6 - 1
      }
      whenIsActive {
        assertMeaningfulCs(True)
        assertValidWriteEnable(False)
        when(!io.cs) {
          goto(idleQpiState)
        }
        when(beatCounter === 0) {
          goto(quadReadState)
        }
        beatCounter.decrement()
      }
    }
    val quadReadState: State = new State {
      onEntry {
        beatCounter := 2 - 1
        io.sio.read := mem.readAsync(address).asBits.resized >> 4
      }

      whenIsActive {
        assertValidWriteEnable(False)
        when(beatCounter === 1) {
          io.sio.read := mem.readAsync(address).asBits.resized
          if (meaningfulOperationAsserted) {
            assert(!(address === address.maxValue && io.cs), "QuadRead address wrap-up")
          }
          address := address + 1

        } elsewhen (beatCounter === 0) {
          io.sio.read := mem.readAsync(address).asBits >> 4
          beatCounter := 1
        }

        when(!io.cs) {
          assertMeaningfulCs(beatCounter === 0)
          goto(idleQpiState)
        }
        beatCounter.decrement()
      }
    }
  }
}
