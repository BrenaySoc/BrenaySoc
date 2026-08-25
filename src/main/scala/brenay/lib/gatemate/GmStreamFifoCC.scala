package brenay.lib.gatemate

import spinal.core._
import spinal.lib.{Stream, master, slave, _}

import brenay.lib.blackbox.cologne.gatemate.{
  BramDataWidth,
  CcFifo40K,
  FifoDynStatMode,
  FifoMode,
  RamMode
}

/** @param dataType
  * @param depth
  * @param pushClock
  * @param popClock
  * @param withPopBufferedReset
  */
// TODO add don't care for unused bits
// TODO check depth and fail if to high
class GmStreamFifoCC[T <: Data](
    val dataType: HardType[T],
    val depth: Int,
    val pushClock: ClockDomain,
    val popClock: ClockDomain,
    val resetFromPushDomain: Boolean = true
) extends Component {

  val maxPrimitiveWidth = 40

  // "with StreamFifoInterface" seems not to be easily implementable because of push/popOccupancy
  // and we need probably extra logic around, not implemented for the moment.
  val io = new Bundle {
    val push = slave Stream (dataType)
    val pop = master Stream (dataType)
  }

  val hwFifoCount = (dataType.getBitsWidth - 1) / maxPrimitiveWidth + 1

  var remainingBits = dataType.getBitsWidth
  val hwFifos = for (hwFifoIndex <- 0 until hwFifoCount) yield new Area {

    val fifoPrimitiveWidth =
      BramDataWidth.minimumFor(
        BramDataWidth.BIT40,
        remainingBits.min(maxPrimitiveWidth)
      )

    val primitive = new CcFifo40K(
      dynStatSelect = FifoDynStatMode.ViaParams,
      almostFullOffset = depth,
      ramMode = RamMode.TrueDualPort,
      bWidth = fifoPrimitiveWidth,
      aWidth = fifoPrimitiveWidth,
      fifoMode = FifoMode.Async,
      bClkInv = pushClock.config.clockEdge == FALLING,
      aClkInv = popClock.config.clockEdge == FALLING
    )

    var bitOffset = hwFifoIndex * fifoPrimitiveWidth.toInt
    val chunkBitCount = fifoPrimitiveWidth.toInt.min(dataType.getBitsWidth - bitOffset)

    primitive.io.bClk := pushClock.readClockWire
    primitive.io.bDi := io.push.payload
      .asBits(bitOffset, chunkBitCount bits)
      .resize(maxPrimitiveWidth)
    primitive.io.bEn := True
    primitive.io.bWe := io.push.valid
    primitive.io.bBm.setAll()

    primitive.io.aClk := popClock.readClockWire

    io.pop.payload
      .assignFromBits(
        primitive.io.aDo(chunkBitCount - 1 downto 0),
        chunkBitCount + bitOffset - 1,
        bitOffset
      )
    primitive.io.aDi.clearAll()
    primitive.io.aBm.clearAll()

    // the CcFifo40K take care internally of reset cleaning
    val resetDomain = if (resetFromPushDomain) {
      pushClock
    } else {
      popClock
    }

    resetDomain.config.resetActiveLevel match {
      case HIGH => primitive.io.fRstN := ~resetDomain.readResetWire
      case LOW  => primitive.io.fRstN := resetDomain.readResetWire
    }

    primitive.io.fAlmostEmptyOffset.clearAll()
    primitive.io.fAlmostFullOffset.clearAll()

    bitOffset += chunkBitCount
  }

  val pushDomain = new ClockingArea(pushClock) {
    io.push.ready := ~hwFifos(0).primitive.io.fAlmostFull

    assert(
      hwFifos.map(~_.primitive.io.fAlmostFull).andR === ~hwFifos(0).primitive.io.fAlmostFull,
      "fifos work in lockstep and should have equal values"
    )
  }
  val popDomain = new ClockingArea(popClock) {

    val valid = RegInit(False)

    when(hwFifos(0).primitive.io.aEn) {
      valid := True
    } elsewhen (io.pop.fire) {
      valid := False
    }

    io.pop.valid := valid

    for (fifo <- hwFifos) yield new Area {
      fifo.primitive.io.aEn := (io.pop.fire || ~valid) && ~hwFifos(0).primitive.io.fEmpty
    }

    assert(
      hwFifos.map(~_.primitive.io.fEmpty).andR === ~hwFifos(0).primitive.io.fEmpty,
      "fifos work in lockstep and should have equal values "
    )
  }
}

object GmStreamFifoCC {
  def apply[T <: Data](
      dataType: HardType[T],
      depth: Int,
      pushClock: ClockDomain,
      popClock: ClockDomain
  ) = new GmStreamFifoCC(dataType, depth, pushClock, popClock)
  def apply[T <: Data](
      push: Stream[T],
      pop: Stream[T],
      depth: Int,
      pushClock: ClockDomain,
      popClock: ClockDomain
  ) = {
    val fifo = new GmStreamFifoCC(push.payloadType, depth, pushClock, popClock)
    fifo.io.push << push
    fifo.io.pop >> pop
    fifo
  }
}
