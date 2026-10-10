// Instrumentation compiled into the snes9x libretro core (see build.sh).
//
// What it gives tools/snesdbg.py, through plain C entry points:
//   - a code/data log over the cartridge (which ROM bytes ran as opcodes,
//     under which M/X widths, which were operands, which were read as data
//     and which went out by DMA): the seed of the disassembly;
//   - a log of every general-purpose DMA (source, B-bus register, size, and
//     the VRAM/CGRAM/WRAM address it lands at);
//   - execution breakpoints that call back into Python;
//   - an instruction trace to a text file;
//   - pointers to WRAM, VRAM, CGRAM, OAM and the PPU state a screen needs.
//
// None of it changes what the emulated console does.

#include "snes9x.h"
#include "memmap.h"
#include "ppu.h"
#include "dma.h"
#include "cpuexec.h"
#include "dbg.h"

#include <stdio.h>
#include <string.h>

bool8 dbg_enabled = FALSE;

static uint8 cdl[0x800000];
static uint32 frame_no;

#define BP_MAX 64
static uint32 bp_addr[BP_MAX];
static int bp_count;
static void (*bp_callback)(uint32);

static FILE *trace_fp;
static long trace_left;

#define DMA_LOG_MAX 65536
static uint32 dma_log[DMA_LOG_MAX][8];
static int dma_log_n;

static inline int rom_offset_of(const uint8 *p)
{
	if (p >= Memory.ROM && p < Memory.ROM + Memory.CalculatedSize)
		return (int) (p - Memory.ROM);
	return -1;
}

// A read with no side effects: memory the map points straight at (ROM, WRAM,
// SRAM), and 0 for registers and anything else.
static uint8 peek(uint32 address)
{
	uint8 *base = Memory.Map[(address & 0xffffff) >> MEMMAP_SHIFT];
	if (base < (uint8 *) CMemory::MAP_LAST)
		return 0;
	return base[address & 0xffff];
}

static int rom_offset(uint32 address)
{
	uint8 *base = Memory.Map[(address & 0xffffff) >> MEMMAP_SHIFT];
	if (base < (uint8 *) CMemory::MAP_LAST)
		return -1;
	return rom_offset_of(base + (address & 0xffff));
}

void dbg_on_exec(uint8 op)
{
	uint32 pc = Registers.PBPC & 0xffffff;
	int off = rom_offset(pc);
	if (off >= 0)
	{
		uint8 f = DBG_CODE;
		if (CheckMemory()) f |= DBG_M8;
		if (CheckIndex()) f |= DBG_X8;
		if (CheckEmulation()) f |= DBG_EMU;
		cdl[off] |= f;
		int len = ICPU.S9xOpLengths[op];
		for (int i = 1; i < len; i++)
		{
			int o = rom_offset((pc & 0xff0000) | ((pc + i) & 0xffff));
			if (o >= 0) cdl[o] |= DBG_OPERAND;
		}
	}

	for (int i = 0; i < bp_count; i++)
		if (bp_addr[i] == pc && bp_callback)
		{
			S9xPackStatus();
			bp_callback(pc);
			break;
		}

	if (trace_fp && trace_left > 0)
	{
		S9xPackStatus();
		int len = ICPU.S9xOpLengths[op];
		fprintf(trace_fp, "%06X", pc);
		for (int i = 0; i < 4; i++)
		{
			if (i < len)
				fprintf(trace_fp, " %02X", peek((pc & 0xff0000) | ((pc + i) & 0xffff)));
			else
				fprintf(trace_fp, "   ");
		}
		fprintf(trace_fp, " A:%04X X:%04X Y:%04X S:%04X D:%04X DB:%02X P:%02X%s F:%u\n",
			Registers.A.W, Registers.X.W, Registers.Y.W, Registers.S.W,
			Registers.D.W, Registers.DB, Registers.PL, CheckEmulation() ? "E" : "",
			frame_no);
		if (--trace_left == 0)
		{
			fclose(trace_fp);
			trace_fp = NULL;
		}
	}
}

void dbg_on_read(const uint8 *p, int n)
{
	int off = rom_offset_of(p);
	if (off < 0) return;
	for (int i = 0; i < n && off + i < (int) Memory.CalculatedSize; i++)
		cdl[off + i] |= DBG_DATA;
}

void dbg_on_dma(uint8 channel)
{
	SDMA *d = &DMA[channel];
	uint32 a = ((uint32) d->ABank << 16) | d->AAddress;
	uint32 count = d->TransferBytes ? d->TransferBytes : 0x10000;
	uint32 dest = 0;
	switch (d->BAddress)
	{
		case 0x18: case 0x19: dest = PPU.VMA.Address; break;  // VRAM word address
		case 0x22: dest = PPU.CGADD; break;                    // CGRAM colour index
		case 0x04: dest = PPU.OAMAddr; break;
		case 0x80: dest = PPU.WRAM; break;
	}
	if (dma_log_n < DMA_LOG_MAX)
	{
		uint32 *e = dma_log[dma_log_n++];
		e[0] = frame_no;
		e[1] = channel;
		e[2] = a;
		e[3] = d->BAddress;
		e[4] = count;
		e[5] = d->ReverseTransfer;
		e[6] = dest;
		e[7] = d->TransferMode | (d->AAddressFixed << 4) | (d->AAddressDecrement << 5);
	}
	if (!d->ReverseTransfer && !d->AAddressFixed)
	{
		for (uint32 i = 0; i < count; i++)
		{
			int o = rom_offset((a & 0xff0000) | ((a + i) & 0xffff));
			if (o >= 0) cdl[o] |= DBG_DMA;
		}
	}
}

void dbg_on_frame(void)
{
	frame_no++;
}

extern "C" {

void dbg_enable(int on) { dbg_enabled = on ? TRUE : FALSE; }
uint8 *dbg_cdl(void) { return cdl; }
void dbg_cdl_clear(void) { memset(cdl, 0, sizeof(cdl)); }
uint32 dbg_frame(void) { return frame_no; }

uint8 *dbg_ptr(int which)
{
	switch (which)
	{
		case 0: return Memory.RAM;
		case 1: return Memory.VRAM;
		case 2: return (uint8 *) PPU.CGDATA;
		case 3: return PPU.OAMData;
		case 4: return Memory.ROM;
		case 5: return Memory.SRAM;
		case 6: return Memory.FillRAM;
	}
	return NULL;
}

// PBPC A X Y S D DB P E
void dbg_regs(uint32 *out)
{
	S9xPackStatus();
	out[0] = Registers.PBPC & 0xffffff;
	out[1] = Registers.A.W;
	out[2] = Registers.X.W;
	out[3] = Registers.Y.W;
	out[4] = Registers.S.W;
	out[5] = Registers.D.W;
	out[6] = Registers.DB;
	out[7] = Registers.PL;
	out[8] = CheckEmulation() ? 1 : 0;
}

// BGMode, BG3Priority, then for BG1-4: SCBase SCSize NameBase HOffset VOffset
// BGSize, then OBJNameBase OBJNameSelect OBJSizeSelect, then $212C $212D
// $2130 $2131 $2100.
void dbg_ppu(uint32 *out)
{
	int n = 0;
	out[n++] = PPU.BGMode;
	out[n++] = PPU.BG3Priority;
	for (int i = 0; i < 4; i++)
	{
		out[n++] = PPU.BG[i].SCBase;
		out[n++] = PPU.BG[i].SCSize;
		out[n++] = PPU.BG[i].NameBase;
		out[n++] = PPU.BG[i].HOffset;
		out[n++] = PPU.BG[i].VOffset;
		out[n++] = PPU.BG[i].BGSize;
	}
	out[n++] = PPU.OBJNameBase;
	out[n++] = PPU.OBJNameSelect;
	out[n++] = PPU.OBJSizeSelect;
	out[n++] = Memory.FillRAM[0x212c];
	out[n++] = Memory.FillRAM[0x212d];
	out[n++] = Memory.FillRAM[0x2130];
	out[n++] = Memory.FillRAM[0x2131];
	out[n++] = Memory.FillRAM[0x2100];
}

uint8 dbg_read(uint32 address) { return peek(address); }

void dbg_set_breakpoints(const uint32 *addrs, int n, void (*cb)(uint32))
{
	if (n > BP_MAX) n = BP_MAX;
	for (int i = 0; i < n; i++) bp_addr[i] = addrs[i] & 0xffffff;
	bp_count = n;
	bp_callback = cb;
}

int dbg_trace_start(const char *path, long max_instructions)
{
	if (trace_fp) fclose(trace_fp);
	trace_fp = fopen(path, "w");
	trace_left = max_instructions;
	return trace_fp != NULL;
}

void dbg_trace_stop(void)
{
	if (trace_fp) fclose(trace_fp);
	trace_fp = NULL;
	trace_left = 0;
}

int dbg_dma_log(uint32 *out, int max)
{
	int n = dma_log_n < max ? dma_log_n : max;
	memcpy(out, dma_log, (size_t) n * sizeof(dma_log[0]));
	return n;
}

void dbg_dma_clear(void) { dma_log_n = 0; }

}
