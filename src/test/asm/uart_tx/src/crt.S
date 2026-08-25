// This test send a sequence of char, for example "0", "1", "2", ... "9"
// with the uart and use a busy wait loop th limit the rate.

// ---------- test parameters --------------
#define UART_TX_WAIT_LOOP_COUNT 300
#define BEGIN_CHAR '0'
#define END_CHAR '9'

// ------------ test code -----------------------------

    .option arch, +zicsr

    .data

#include "riscv_asm.h"
#include "sim_asm.h"
#include "custom_asm.h"

#define UART_REG_BASE        0x10001000l
#define UART_REG_OFFSET_RXTX		(0*4)
#define UART_REG_OFFSET_TXFULL		(1*4)
#define UART_REG_OFFSET_RXEMPTY  	(2*4)
#define UART_REG_OFFSET_EV_STATUS	(3*4)
#define UART_REG_OFFSET_EV_PENDING	(4*4)
#define UART_REG_OFFSET_EV_ENABLE	(5*4)

    .text
    .global _start
    _start:  
        
        la t0, UART_REG_BASE // load constant registers
        li t3, END_CHAR // load end char, inclusive
        li t1, 1  // start by writing a char

    load_begin_char:
        li t2, BEGIN_CHAR      

    dec_counter:
        addi t1, t1, -1
        bne t1, x0, dec_counter

        // end of wait loop write to uart tx
        sw t2, UART_REG_OFFSET_RXTX(t0)
        li t1, UART_TX_WAIT_LOOP_COUNT
        addi t2, t2, 1  // increment char
        ble t2, t3, dec_counter // branch if not end char
        j load_begin_char
