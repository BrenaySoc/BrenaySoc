package brenay.lib.blackbox.cologne.gatemate

import spinal.core._

/** GateMate CC_USR_RSTN primitive
  *
  * The CC_USR_RSTN primitive is used to generate the signal USR_RSTN which shows the
  * end of configuration. It can be used to generate an internal asynchronous set/reset
  * or start signal. No set/reset signal needs to be fed-in from any GPIO.
  *
  * This primitive has no parameters and provides a single output signal that indicates
  * the end of FPGA configuration.
  */
class CcUsrRstn(
) extends BlackBox {
  this.setBlackBoxName("CC_USR_RSTN")

  val io = new Bundle {

    /** Reset signal to the Cologne Programmable Element (CPE) array */
    val usrRstn = out Bool ()
  }

  io.usrRstn.setName("USR_RSTN")
  noIoPrefix()
}
