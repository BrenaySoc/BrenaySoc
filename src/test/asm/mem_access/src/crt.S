// This test access a memory regions, write data to it and then read back
// with a check. 
// 

// ---------- test parameters --------------
#define UART_TX_WAIT_LOOP_COUNT 100
#define PASS_CHAR 'P'
#define FAIL_CHAR 'F'

// ------------ test code -----------------------------

    .option arch, +zicsr


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


    .data

data_region:
    .balign 4
    .fill 256, 4, 0
data_region_end:


//#define START_ADDRESS data_region
//#define END_ADDRESS data_region_end
#define START_ADDRESS 0x90000000L
//#define TEST_BYTE_COUNT ((8+1)*1024) // enough for L1d eviction
#define TEST_BYTE_COUNT (4*1024*1024) // full memory access
#define END_ADDRESS (START_ADDRESS + TEST_BYTE_COUNT)
#define STOP_ON_ERROR


// a0-3 are reference values
// a4-a7 are read back values
// t0 is accessed address
// t1 is test_ok
// t2 is char to write on UART
// t3 is char wait counter
// t4 is uart base register
// t5 is END_ADDRESS

    .text
    .global _start
    _start:  

    li t1, 1

    li t3, 1 // start without delay
    
    // unroll for more values and 
    write_begin:
        
        li a0, 0x44332211l
        li a1, 0x88776655l
        li a2, 0xccbbaa99l
        li a3, 0x00f00ed0l

        la t0, START_ADDRESS
        la t5, END_ADDRESS

    // 4 words unroll of write and read
    write_data:
        sw a0, 0(t0)
        sw a1, 4(t0)
        sw a2, 8(t0)
        sw a3, 12(t0)

        addi t0, t0, 4*4
        blt t0, t5, write_data

    check_begin:
        la t0, START_ADDRESS    
        
    check_data:        
        lw a4, 0(t0)        
        lw a5, 4(t0)
        lw a6, 8(t0)
        lw a7, 12(t0)
        addi t0, t0, 4*4
        bne a4, a0, check_error_0  
        bne a5, a1, check_error_1        
        bne a6, a2, check_error_2
        bne a7, a3, check_error_3
        blt t0, t5, check_data

        li t1, 1 // t1 is test_ok
        li t2, PASS_CHAR
        j output_result

    // correct t0 address that failed (TODO print address to uart)
    check_error_3:
        addi t0, t0, 4
    check_error_2:
        addi t0, t0, 4
    check_error_1:
        addi t0, t0, 4
    check_error_0:
        li t1, 0
        li t2, FAIL_CHAR

    output_result:
        la t4, UART_REG_BASE // load constant registers

    dec_counter:
        addi t3, t3, -1
        bne t3, x0, dec_counter

        // end of wait loop write to uart tx
        sw t2, UART_REG_OFFSET_RXTX(t4)
        li t3, UART_TX_WAIT_LOOP_COUNT

#ifdef STOP_ON_ERROR
        beq t1, zero, dec_counter
#endif
        li t2, '_'  // for monitoring of t2 register on analyser
        j write_begin
