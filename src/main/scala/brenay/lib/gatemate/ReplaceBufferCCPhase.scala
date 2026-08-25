package brenay.lib.gatemate

import spinal.core._
import spinal.core.internals.{PhaseContext, PhaseNetlist}
import spinal.lib.BufferCC

/** Phase to remove keep_hierarchy in  BufferCC so nextpnr doesn't fail
  *
  * This is needed with current nextpnr, because spinal add these kind of attribute:
  * {{{
  *   (* keep_hierarchy = "TRUE" *) module BufferCC_5 (...);
  *   (* async_reg = "true" *) reg buffers_0;
  *   (* async_reg = "true" *) reg buffers_1;
  * }}}
  *
  * This is inferred by vivado as cross domain crossing constraint relaxation.
  * But yosys do not flatten the BufferCC and because nextpnr doesn't recognize
  * it there is an error.
  *
  * This fix remove the "keep_hierarchy" and the error goes away. But the
  * relaxation is not added and there is extra unnecessary routing constraints.
  * In the future nextpnr may support constraints and inference and could detect
  * these relaxations.
  */
class ReplaceBufferCCPhase extends PhaseNetlist {
  override def impl(pc: PhaseContext): Unit = {
    pc.walkComponents {
      case c: BufferCC[_] => {
        c.removeTags(c.getTags().collect {
          case a: Attribute if a.getName == "keep_hierarchy" => a
        })
      }
      case _ =>
    }
  }
}
