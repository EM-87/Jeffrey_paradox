/* Minimal GBA linker script */
OUTPUT_FORMAT("elf32-littlearm")
OUTPUT_ARCH(arm)
ENTRY(_start)

MEMORY {
    ROM  (rx)  : ORIGIN = 0x08000000, LENGTH = 32M
    EWRAM(rwx) : ORIGIN = 0x02000000, LENGTH = 256K
    IWRAM(rwx) : ORIGIN = 0x03000000, LENGTH = 32K
}

SECTIONS {
    . = 0x08000000;

    .text : {
        *(.text.start)
        *(.text*)
        *(.rodata*)
        . = ALIGN(4);
    } > ROM

    .data : {
        __data_start__ = .;
        *(.data*)
        . = ALIGN(4);
        __data_end__ = .;
    } > IWRAM AT > ROM

    .bss (NOLOAD) : {
        __bss_start__ = .;
        *(.bss*)
        *(COMMON)
        . = ALIGN(4);
        __bss_end__ = .;
    } > IWRAM
}
