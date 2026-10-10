#ifndef LV2_DBG_H
#define LV2_DBG_H

// Bits of the code/data log, one byte per ROM byte.
#define DBG_CODE    0x01  // ran as an opcode
#define DBG_OPERAND 0x02  // operand of an opcode that ran
#define DBG_DATA    0x04  // read by the CPU as data
#define DBG_DMA     0x08  // sent out by DMA
#define DBG_M8      0x10  // the opcode ran with an 8-bit accumulator
#define DBG_X8      0x20  // the opcode ran with 8-bit index registers
#define DBG_EMU     0x40  // the opcode ran in emulation mode

extern bool8 dbg_enabled;
void dbg_on_exec(uint8 op);
void dbg_on_read(const uint8 *p, int n);
void dbg_on_dma(uint8 channel);
void dbg_on_frame(void);

#endif
