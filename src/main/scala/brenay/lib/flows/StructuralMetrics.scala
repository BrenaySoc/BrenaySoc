package brenay.lib.flows

final case class FlipFlopCount(count: Int) extends Metric(count) {
  override def name(): String = "flip_flop_count"
  override def description(): String = "Number of flip-flops (edge-triggered 1-bit storage)"
}

final case class LatchCount(count: Int) extends Metric(count) {
  override def name(): String = "latch_count"
  override def description(): String = "Number of level-sensitive latches"
}

final case class InputBufferCount(count: Int) extends Metric(count) {
  override def name(): String = "ibuf_count"
  override def description(): String = "Number of input buffers"
}

final case class OutputBufferCount(count: Int) extends Metric(count) {
  override def name(): String = "obuf_count"
  override def description(): String = "Number of output buffers"
}

final case class BidirectionalBufferCount(count: Int) extends Metric(count) {
  override def name(): String = "iobuf_count"
  override def description(): String = "Number of bidirectional/tristate IO buffers"
}

final case class MultiplierCount(count: Int) extends Metric(count) {
  override def name(): String = "mult_count"
  override def description(): String = "Number of multiplier/DSP blocks"
}

/** Number of the smallest splittable memory count in the family */
final case class BramCount(count: Int) extends Metric(count) {
  override def name(): String = "bram_count"
  override def description(): String = "Number of the smallest splittable block RAM instances"
}

final case class ClockBufferCount(count: Int) extends Metric(count) {
  override def name(): String = "clock_buffer_count"
  override def description(): String =
    "Number of global buffers usually used for clock distribution"
}
