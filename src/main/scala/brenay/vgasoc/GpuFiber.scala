package brenay.vgasoc

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib.DownCounter
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.Opcode
import spinal.lib.bus.tilelink.fabric.Node
import spinal.lib.fsm.{EntryPoint, State, StateMachine}

/** A non working Gpu
  *
  * Currently only a draft to see if fmax is doable. It is not really functional
  * it only drive tilelink with read and write in burst mode.
  *
  * To use it you have to connect `this.down` as tilelink master
  */
class GpuFiber() extends Area {
  val down = Node.down()

  val thread = Fiber build new Area {

    val burstSize = 64 // in bytes

    down.m2s.proposed load tilelink.M2sSupport(
      addressWidth = 32,
      dataWidth = 32,
      transfers = tilelink.M2sTransfers(
        get = tilelink.SizeRange(burstSize),
        putFull = tilelink.SizeRange(burstSize)
      )
    )

    down.m2s.parameters load tilelink.M2sParameters(
      support = down.m2s.proposed,
      sourceCount = 1
    )

    down.s2m.supported load tilelink.S2mSupport.none()

    val aBus = down.bus.a
    val dBus = down.bus.d

    aBus.valid.setAsReg()
    aBus.payload.address.setAsReg()
    aBus.payload.data.setAsReg()
    aBus.payload.mask.setAll()
    aBus.opcode.setAsReg()
    aBus.valid := False
    aBus.payload.assignDontCare()

    dBus.ready.setAsReg()
    dBus.ready := False

    val busParam = down.bus.p

    val fsm = new StateMachine {
      setEncoding(binaryOneHot)

      val data =
        Reg(Vec.fill(burstSize / busParam.dataBytes)(UInt(busParam.dataWidth bits)))

      val dataAddress = Reg(UInt(32 bits)) init (0)

      val busBeatCounter = DownCounter(burstSize / busParam.dataBytes)

      val sendGetState: State = new State with EntryPoint {

        whenIsActive {

          aBus.address := dataAddress
          aBus.payload.size := log2Up(burstSize) - 1
          aBus.payload.opcode := Opcode.A.GET
          aBus.valid := True

          when(aBus.fire) {
            goto(waitDataState)
          }
        }
      }
      val waitDataState: State = new State {
        onEntry {
          dBus.ready := True
          busBeatCounter.value := busBeatCounter.maxValue
        }
        whenIsActive {
          when(dBus.valid && dBus.payload.opcode === Opcode.D.ACCESS_ACK_DATA) {
            data(busBeatCounter) := dBus.payload.data.asUInt
            busBeatCounter.decrement()

            when(busBeatCounter === 0) {
              dBus.ready := False
              goto(processDataState)
            }
          }
        }
      }
      val processDataState: State = new State {

        whenIsActive {
          data.asBits := data.asBits.reversed
          goto(sendDataState)
        }
      }

      val sendDataState: State = new State {
        onEntry {
          busBeatCounter.value := busBeatCounter.maxValue
        }
        whenIsActive {
          aBus.address := dataAddress
          aBus.payload.size := log2Up(burstSize) - 1
          aBus.payload.opcode := Opcode.A.PUT_FULL_DATA
          aBus.payload.data := data(busBeatCounter.value).asBits
          aBus.valid := True

          when(aBus.fire) {
            busBeatCounter.decrement()
            when(busBeatCounter === 0) {
              dataAddress := dataAddress + burstSize
              goto(sendGetState)
            }
          }
        }
      }
    }

  }
}
