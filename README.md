# Brenay SoC

Brenay is a System on Chip tailor-made for the 
[Cologne Chip GateMate FPGA](https://colognechip.com/programmable-logic/gatemate/)
 series.

The goal is to have reference design using modern languages that can be extended/forked
as a base for low power/cost optimized industrial solutions. The current first
target is the Olimex GateMateA1-EVB board for a MCU + GPU + VGA controller
for a vintage 4:4:4 game design platform, but components are aimed at being reusable
for other designs.

Features:

- optional [VexiiRiscv](https://spinalhdl.github.io/VexiiRiscv-RTD/master/VexiiRiscv/Introduction/)
  RISC-V core optimized for GateMate
- interconnect with [Tilelink-UH](https://www.sifive.com/document-file/tilelink-spec-1.9.3)
  bus protocol
- multi-clock domain design, targeting `extMems` at 100 MHz, `processing`
  at >= 25 MHz, `graphic` at 25.175 MHz, independent of each other and
  communicating with hardware FIFOs
- full [SpinalHDL](https://spinalhdl.github.io/SpinalDoc-RTD/master) 
  RTL with possible VHDL/verilog integration
- build system based on Scala [sbt](https://www.scala-sbt.org/)
  for simulation, synthesis, place & route, flash
- firmware in full Rust + [Embassy](https://embassy.dev) or bare-metal C
- full open source toolchain with [Yosys](https://yosyshq.readthedocs.io/projects/yosys/en/latest/introduction.html)
  for synthesis, place and route and bitstream generation (official GateMate toolchain)
- JTAG debug
- github CI integration


## Architecture


## Current status and roadmap

All frequencies are stated for `nextpnr -o fpga_mode=speed -o time_mode=worst`, i.e.
-40° to +125 °C junction temperature operating range for 1.1V core voltage.
This means the `VDD_CORE_SET1` jumper on GateMateA1-EVB should be set between 
pin 2 and 3.

Working RTL:

- [x] 80 MHz single PSRAM + Vexii 25 MHz with L1d+i
- [x] Bank EB1 JTAG debug at 5 MHz
- [x] 115.2 kbps UART on USB tty through RP2040
- [x] clean simultaneous reset of all domains after 64 clock of the slowest domain

Working firmware: 

- [x] assembler PSRAM full read-write test with UART output

RTL roadmap:

- [ ] implement a VGA compliant display controller with frame buffer on PSRAM
- [ ] add GPU slave command bus from MCU
- [ ] implement an example GPU with a demo application
- [ ] support for dual PSRAM
- [ ] support PSRAM Tilelink PUT width of 8 and 16 bits (useful for uncached access 
      or L1d-less core) 
- [ ] optimize PSRAM serializer to reach 100 MHz
- [ ] optimize VexiiRiscv to reach > 25 MHz
- [ ] optimize VexiiRiscv BRAM usage
- [ ] mcu JTAG debug using modified dirty-jtag firmware RP2040 for single USB connection
      for power, bitstream and firmware load and debug
- [ ] add QPI flash memory read in burst capable mode
- [ ] add flash memory programming
- [ ] currently using branch fork BrenaySoc/SpinalHDL/brenay-soc-improvements,
      get improvement into upstream
- [ ] make vexii truly using the same SpinalHDL lib as the main project
- [ ] integrate automatic asm build to the test workflow
- [ ] setup github CI      
- [ ] setup gitlab mirror and example CI

Firmware roadmap:

- [ ] Rust bare metal setup
- [ ] Embassy setup
- [ ] Embassy timer driver
- [ ] Embassy UART driver
- [ ] game demo

## Resource usage

Minimal MCU config with:

- RV32 IM
- 8 KiB internal RAM with bitstream init
- 4 KiB L1d
- UART with PLIC interrupt and 4KiB rx/tx FIFOs
- JTAG
- 4 MiB PSRAM

On CCGM1A1: 

```text
      PLL:       2/      4    50%
   CPE_LT:   12210/  40960    29%
   CPE_FF:    4141/  40960    10%
CPE_RAMIO:    1300/  40960     3%
 RAM_HALF:      17/     64    26%
```

The number of RAM_HALF could probably be optimized.

## Getting the source

```sh
git submodule update --init # no --recursive to avoid duplication
git submodule update --init --recursive  ext/VexiiRiscv/ # currently needed to build vexii
```

## Tools installation

[Install SpinalHDL dependencies](https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Getting%20Started/Install%20and%20setup.html)

 For Ubuntu 24.04, with Verilator being installed later with OSS CAD Suite, this should be enough:

```sh
sudo apt-get update
sudo apt-get install openjdk-21-jdk-headless curl git
curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > cs
chmod +x cs
# should find the just installed jdk, agree to cs' questions for adding to your PATH
./cs setup
source ~/.profile
```

Install oss-cad-suite from <https://github.com/YosysHQ/oss-cad-suite-build/releases/download/2026-08-26>.

For linux x86:
```sh
wget https://github.com/YosysHQ/oss-cad-suite-build/releases/download/2026-08-26/oss-cad-suite-linux-x64-20260826.tgz
sudo rm  -rf /opt/oss-cad-suite
sudo tar xzf oss-cad-suite-linux-x64-20260826.tgz -C /opt/
```

Create a `.env` file :

```sh
source /opt/oss-cad-suite/environment

# This is to use the same spinal source for vexii submodule and brenay.
# Use your favorite sh .env dir extraction or absolute path.
export SPINALHDL_PATH="$(realpath "$(dirname "${BASH_SOURCE[0]}")/ext/SpinalHDL")"
export SPINALHDL_FROM_SOURCE=1

# Ensure test are reproducible with 1 but allows testing with different seeds.
export SPINAL_SIM_SEED="1"  
```

## codium/vscode settings

Install:

 - [Scala(Metals)](https://github.com/scalameta/metals-vscode)
 - optionally [Code Spell Checker](https://github.com/streetsidesoftware/vscode-spell-checker)

The following config seems to work fine. Currently (codium 1.126, scalameta 1.70.0)
the Bloop build server does not work, execute "View -> Command Palette ... -> Metals: Switch
build server" and select "sbt".

```json
{
    // this excludes directories with a lot of build activity that penalize perf
    "files.watcherExclude": {
        "**/target/**": true,
        "simWorkspace/**": true,
        "flow_build/**": true
    },
    "terminal.integrated.profiles.linux": {
        "Bash oss-cad-suite": {
            "path": "/usr/bin/bash",
            "args": [
                // this ensure that the .bashrc is loaded
                "-c",
                "source .env && bash -i"
            ],
            "overrideName": true
        }
    },
    "terminal.integrated.defaultProfile.linux": "Bash oss-cad-suite",
    "metals.testEnvironmentVariables": {
        "PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/oss-cad-suite/bin:${env:PATH}",
        "SPINALHDL_PATH": "<REPOS ABSOLUTE PATH>/ext/SpinalHDL", // !!!!!!! change this !!!!!!!!!
        "SPINALHDL_FROM_SOURCE": "1",
        "SPINAL_SIM_SEED": "1"
    },
    "editor.rulers": [
        80,   // mainly for text block to still be readable
        100,  // project scala line width
        120   // for scala deep functional code (not needed in HDL generally)
    ],
    "editor.formatOnSave": true,
    "[scala]": {
        "editor.defaultFormatter": "scalameta.metals",
    },
}
```


## Run tests

In `.env` sourced shell, run this:

```sh
make -C src/test/asm/mem_access
make -C src/test/asm/uart_tx/

sbt test # all tests
sbt "testOnly brenay.vgasoc.ProcessingSim -- -z psram" # filter AnyFunSuite by test() name
sbt "testOnly brenay.vgasoc.ProcessingSim -- -oF" # output full call stack

# Test with different seeds (don't use sbt --client):
for seed in {1..10}; do SPINAL_SIM_SEED=$seed sbt "testOnly *.GmStreamFifoCCSim" || break; done
```

## Build bitstream for the Olimex

The build system is entirely done in scala.

TODO currently you still need to run make for the asm test codes:

```sh
make -C src/test/asm/mem_access
```

To build the FPGA bitstream, do:

```sh
runMain brenay.vgasoc.board.olimex.gatemate_a1_evb.Build
```

connect the USB and run:

```sh
runMain brenay.vgasoc.board.olimex.gatemate_a1_evb.Load
```

Reset the target with the FPGA_RST1 button. Run in a terminal the following command:
```sh 
plink -serial /dev/ttyACM0 -sercfg 115200,8,n,1,N
```

The firmware is full write, then reread of the PSRAM test. It outputs a single
'P' char when successful and stop and output 'F' when there is an error.
The code can be found in `src/test/asm/mem_access/src/crt.S`. If everything
is fine, you should see something like:

```sh
PPPPPPPPP
```

## Scala auto formatting

To check if the formatting is correct:

```sh
sbt scalafmtCheckAll
sbt "scalafixAll --test"
```

To format in place all the scala files:

```sh
sbt "scalafixAll;scalafmtAll"
```

It's important to run scalafmt after scalafix, because it rearrange some imports.


## Hardware debug

### Serial from USB

`plink -serial /dev/ttyACM0 -sercfg 115200,8,n,1,N`

### Install Segger JLink tools

Currently working on ubuntu 24.04 x86_64:

```sh
download from https://www.segger.com/downloads/jlink/:
sudo apt-get install ./JLink_Linux_V966_x86_64.deb 
```

### Debug with JLink

See [part of Vexii tutorial about openocd](https://spinalhdl.github.io/VexiiRiscv-RTD/master/VexiiRiscv/Tutorial/index.html#connecting-with-openocd-to-the-simulation).

Start server on one terminal:

```sh
/usr/bin/openocd -f src/test/asm/jlink.tcl
```

Use telnet to debug using openocd directly:

```sh
telnet localhost 4444

halt
load_image src/test/asm/uart_tx/build/uart_tx.elf
load_image src/test/asm/mem_access/build/mem_access.elf
reset init
resume

reg fp # is s0
mdw 0x80000000
mww 0x80000000 0x04200513
# Move the CPU PC to the instruction we just wrote at 0x80000000
reg pc 0x80000000
```

## Useful links

* [VGA signal timing](https://web.archive.org/web/20250106055734/https://tinyvga.com/vga-timing)
* [`openFPGALoader` usage with GateMate](https://trabucayre.github.io/openFPGALoader/vendors/colognechip.html#jtag-flash-access)
* [`openFPGALoader` guide for `udev`](https://umarcor.github.io/openFPGALoader/guide/install.html#udev-rules)
* [`udev` config for openFPGALoader](https://github.com/trabucayre/openFPGALoader/blob/master/99-openfpgaloader.rules
)

