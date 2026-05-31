@ GBA startup stub — minimal CRT0
@ Sets up the stack, clears BSS, and jumps to main()

    .section .text.start
    .global _start
    .arm

_start:
    @ GBA ROM header space (192 bytes) – filled with NOPs then overwritten by gbafix
    @ The linker script places the header at 0x08000000; we just branch to _gba_start
    b       _gba_start
    .space  188, 0      @ placeholder header bytes

_gba_start:
    @ Set CPU to System mode, IRQs and FIQs disabled
    mov     r0, #0xD3
    msr     cpsr, r0

    @ Stack pointers
    @ IRQ stack
    mov     r0, #0xD2
    msr     cpsr, r0
    ldr     sp, =0x03007FA0

    @ System/User stack
    mov     r0, #0xDF
    msr     cpsr, r0
    ldr     sp, =0x03007F00

    @ Clear BSS
    ldr     r0, =__bss_start__
    ldr     r1, =__bss_end__
    mov     r2, #0
.clear_bss:
    cmp     r0, r1
    strlt   r2, [r0], #4
    blt     .clear_bss

    @ Call main
    ldr     r0, =main
    mov     lr, pc
    bx      r0

    @ Infinite loop if main returns
.hang:
    b       .hang
