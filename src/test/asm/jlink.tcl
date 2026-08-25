source [find interface/jlink.cfg]
transport select jtag
adapter speed 100 // enough for most case, work with downsampled clocks
source [find ext/VexiiRiscv/src/main/tcl/openocd/vexiiriscv_jtag.tcl]

# Remove 5 V on jlink connector unused but what can cause damage if connected
# by mistake (probably off by default but, better to be on the safe side).
jlink targetpower off
