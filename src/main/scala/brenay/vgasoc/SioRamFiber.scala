package brenay.vgasoc

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib.bus.tilelink.M2sTransfers
import spinal.lib.bus.tilelink.fabric.Node
import spinal.lib.bus.tilelink.fabric.sim.NodeRamModelTag
import spinal.lib.system.tag.PMA

/** Fiber to instantiate PSRAM + gatemate Phy
  *
  * Provide a `this.up` tilelink slave on the instantiation clock domain and
  * instantiate the PSRAM and it's phy in th provided `psramClockDomain`
  * with correct cross domain crossing.
  *
  * `thread.controller.io.sioBus` should be connected to the sioBus
  */
class SioRamFiber() extends Area {
  val up = Node.up()
  up.addTag(PMA.MAIN)
  up.addTag(PMA.EXECUTABLE)
  up.addTag(NodeRamModelTag)

  val thread = Fiber build new Area {

    up.m2s.supported load up.m2s.proposed
      .intersect(M2sTransfers.allGetPut)
      .copy(addressWidth = log2Up(4 MiB))
    up.s2m.none()

    val controller = new SioRam(up.bus.p.node)

    controller.io.up <> up.bus
  }
}
