#!/usr/bin/env python3
"""A 65816 disassembler for the cartridge, LoROM.

    dis65816.py ROM START [END] [--m8|--m16] [--x8|--x16] [--cdl FILE]

START and END are CPU addresses (80B93E or $80:B93E). Without --cdl the
accumulator and index widths start at what the flags say and follow REP/SEP
in a straight line; with a code/data log (snesdbg.py's, saved by
`np.save`/raw bytes) each opcode takes the widths it actually ran with, and
bytes that never ran print as data.

The listing is generated from your dump and is not kept in the repository.
"""

import argparse
import sys

# mode -> operand bytes (M and X are the immediate forms whose size follows
# the accumulator / index width).
IMP, ACC, IMM8, IMMM, IMMX, DP, DPX, DPY, IND, INDX, INDY, INDL, INDLY, \
    ABS, ABSX, ABSY, LONG, LONGX, AIND, AINDX, AINDL, SR, SRIY, REL, RELL, \
    MOVE = range(26)

_SIZE = {IMP: 0, ACC: 0, IMM8: 1, DP: 1, DPX: 1, DPY: 1, IND: 1, INDX: 1,
         INDY: 1, INDL: 1, INDLY: 1, ABS: 2, ABSX: 2, ABSY: 2, LONG: 3,
         LONGX: 3, AIND: 2, AINDX: 2, AINDL: 2, SR: 1, SRIY: 1, REL: 1,
         RELL: 2, MOVE: 2}

_T = """
00 BRK IMM8|01 ORA INDX|02 COP IMM8|03 ORA SR|04 TSB DP|05 ORA DP|06 ASL DP|07 ORA INDL
08 PHP IMP|09 ORA IMMM|0A ASL ACC|0B PHD IMP|0C TSB ABS|0D ORA ABS|0E ASL ABS|0F ORA LONG
10 BPL REL|11 ORA INDY|12 ORA IND|13 ORA SRIY|14 TRB DP|15 ORA DPX|16 ASL DPX|17 ORA INDLY
18 CLC IMP|19 ORA ABSY|1A INC ACC|1B TCS IMP|1C TRB ABS|1D ORA ABSX|1E ASL ABSX|1F ORA LONGX
20 JSR ABS|21 AND INDX|22 JSL LONG|23 AND SR|24 BIT DP|25 AND DP|26 ROL DP|27 AND INDL
28 PLP IMP|29 AND IMMM|2A ROL ACC|2B PLD IMP|2C BIT ABS|2D AND ABS|2E ROL ABS|2F AND LONG
30 BMI REL|31 AND INDY|32 AND IND|33 AND SRIY|34 BIT DPX|35 AND DPX|36 ROL DPX|37 AND INDLY
38 SEC IMP|39 AND ABSY|3A DEC ACC|3B TSC IMP|3C BIT ABSX|3D AND ABSX|3E ROL ABSX|3F AND LONGX
40 RTI IMP|41 EOR INDX|42 WDM IMM8|43 EOR SR|44 MVP MOVE|45 EOR DP|46 LSR DP|47 EOR INDL
48 PHA IMP|49 EOR IMMM|4A LSR ACC|4B PHK IMP|4C JMP ABS|4D EOR ABS|4E LSR ABS|4F EOR LONG
50 BVC REL|51 EOR INDY|52 EOR IND|53 EOR SRIY|54 MVN MOVE|55 EOR DPX|56 LSR DPX|57 EOR INDLY
58 CLI IMP|59 EOR ABSY|5A PHY IMP|5B TCD IMP|5C JML LONG|5D EOR ABSX|5E LSR ABSX|5F EOR LONGX
60 RTS IMP|61 ADC INDX|62 PER RELL|63 ADC SR|64 STZ DP|65 ADC DP|66 ROR DP|67 ADC INDL
68 PLA IMP|69 ADC IMMM|6A ROR ACC|6B RTL IMP|6C JMP AIND|6D ADC ABS|6E ROR ABS|6F ADC LONG
70 BVS REL|71 ADC INDY|72 ADC IND|73 ADC SRIY|74 STZ DPX|75 ADC DPX|76 ROR DPX|77 ADC INDLY
78 SEI IMP|79 ADC ABSY|7A PLY IMP|7B TDC IMP|7C JMP AINDX|7D ADC ABSX|7E ROR ABSX|7F ADC LONGX
80 BRA REL|81 STA INDX|82 BRL RELL|83 STA SR|84 STY DP|85 STA DP|86 STX DP|87 STA INDL
88 DEY IMP|89 BIT IMMM|8A TXA IMP|8B PHB IMP|8C STY ABS|8D STA ABS|8E STX ABS|8F STA LONG
90 BCC REL|91 STA INDY|92 STA IND|93 STA SRIY|94 STY DPX|95 STA DPX|96 STX DPY|97 STA INDLY
98 TYA IMP|99 STA ABSY|9A TXS IMP|9B TXY IMP|9C STZ ABS|9D STA ABSX|9E STZ ABSX|9F STA LONGX
A0 LDY IMMX|A1 LDA INDX|A2 LDX IMMX|A3 LDA SR|A4 LDY DP|A5 LDA DP|A6 LDX DP|A7 LDA INDL
A8 TAY IMP|A9 LDA IMMM|AA TAX IMP|AB PLB IMP|AC LDY ABS|AD LDA ABS|AE LDX ABS|AF LDA LONG
B0 BCS REL|B1 LDA INDY|B2 LDA IND|B3 LDA SRIY|B4 LDY DPX|B5 LDA DPX|B6 LDX DPY|B7 LDA INDLY
B8 CLV IMP|B9 LDA ABSY|BA TSX IMP|BB TYX IMP|BC LDY ABSX|BD LDA ABSX|BE LDX ABSY|BF LDA LONGX
C0 CPY IMMX|C1 CMP INDX|C2 REP IMM8|C3 CMP SR|C4 CPY DP|C5 CMP DP|C6 DEC DP|C7 CMP INDL
C8 INY IMP|C9 CMP IMMM|CA DEX IMP|CB WAI IMP|CC CPY ABS|CD CMP ABS|CE DEC ABS|CF CMP LONG
D0 BNE REL|D1 CMP INDY|D2 CMP IND|D3 CMP SRIY|D4 PEI DP|D5 CMP DPX|D6 DEC DPX|D7 CMP INDLY
D8 CLD IMP|D9 CMP ABSY|DA PHX IMP|DB STP IMP|DC JML AINDL|DD CMP ABSX|DE DEC ABSX|DF CMP LONGX
E0 CPX IMMX|E1 SBC INDX|E2 SEP IMM8|E3 SBC SR|E4 CPX DP|E5 SBC DP|E6 INC DP|E7 SBC INDL
E8 INX IMP|E9 SBC IMMM|EA NOP IMP|EB XBA IMP|EC CPX ABS|ED SBC ABS|EE INC ABS|EF SBC LONG
F0 BEQ REL|F1 SBC INDY|F2 SBC IND|F3 SBC SRIY|F4 PEA ABS|F5 SBC DPX|F6 INC DPX|F7 SBC INDLY
F8 SED IMP|F9 SBC ABSY|FA PLX IMP|FB XCE IMP|FC JSR AINDX|FD SBC ABSX|FE INC ABSX|FF SBC LONGX
"""

OPS = [None] * 256
for _cell in _T.replace("\n", "|").split("|"):
    _cell = _cell.strip()
    if _cell:
        _h, _mn, _md = _cell.split()
        OPS[int(_h, 16)] = (_mn, globals()[_md])
assert all(OPS)

BRANCHES = {"BPL", "BMI", "BVC", "BVS", "BCC", "BCS", "BNE", "BEQ", "BRA",
            "BRL"}
ENDS = {"RTS", "RTL", "RTI", "JMP", "JML", "BRA", "BRL", "STP"}


class Rom:
    """LoROM reader: CPU address -> byte."""

    def __init__(self, data):
        self.data = data

    def offset(self, addr):
        bank = (addr >> 16) & 0x7F
        lo = addr & 0xFFFF
        if lo < 0x8000 or bank >= 0x7E:
            return None
        o = (bank << 15) | (lo & 0x7FFF)
        return o % len(self.data)

    def byte(self, addr):
        o = self.offset(addr)
        return None if o is None else self.data[o]

    def bytes(self, addr, n):
        out = []
        for i in range(n):
            a = (addr & 0xFF0000) | ((addr + i) & 0xFFFF)
            out.append(self.byte(a))
        return out


def decode(rom, addr, m8=True, x8=True):
    """-> (length, mnemonic, operand text, target or None, raw bytes)."""
    op = rom.byte(addr)
    mn, md = OPS[op]
    n = _SIZE.get(md)
    if md == IMMM:
        n = 1 if m8 else 2
    elif md == IMMX:
        n = 1 if x8 else 2
    raw = rom.bytes(addr, 1 + n)
    v = 0
    for i, b in enumerate(raw[1:]):
        v |= (b or 0) << (8 * i)
    bank = addr & 0xFF0000
    target = None
    if md in (IMP,):
        t = ""
    elif md == ACC:
        t = "A"
    elif md in (IMM8, IMMM, IMMX):
        t = "#$%0*X" % (2 * n, v)
    elif md == DP:
        t = "$%02X" % v
    elif md == DPX:
        t = "$%02X,X" % v
    elif md == DPY:
        t = "$%02X,Y" % v
    elif md == IND:
        t = "($%02X)" % v
    elif md == INDX:
        t = "($%02X,X)" % v
    elif md == INDY:
        t = "($%02X),Y" % v
    elif md == INDL:
        t = "[$%02X]" % v
    elif md == INDLY:
        t = "[$%02X],Y" % v
    elif md == ABS:
        t = "$%04X" % v
        if mn in ("JSR", "JMP"):
            target = bank | v
    elif md == ABSX:
        t = "$%04X,X" % v
    elif md == ABSY:
        t = "$%04X,Y" % v
    elif md == LONG:
        t = "$%06X" % v
        if mn in ("JSL", "JML"):
            target = v
    elif md == LONGX:
        t = "$%06X,X" % v
    elif md == AIND:
        t = "($%04X)" % v
    elif md == AINDX:
        t = "($%04X,X)" % v
    elif md == AINDL:
        t = "[$%04X]" % v
    elif md == SR:
        t = "$%02X,S" % v
    elif md == SRIY:
        t = "($%02X,S),Y" % v
    elif md == REL:
        target = bank | ((addr + 2 + (v - 256 if v >= 128 else v)) & 0xFFFF)
        t = "$%04X" % (target & 0xFFFF)
    elif md == RELL:
        target = bank | ((addr + 3 + (v - 65536 if v >= 32768 else v)) & 0xFFFF)
        t = "$%04X" % (target & 0xFFFF)
    elif md == MOVE:
        t = "$%02X,$%02X" % (raw[2], raw[1])  # MVN src,dst in assembler order
    else:
        t = "?"
    return 1 + n, mn, t, target, raw


def listing(rom, start, end, m8=True, x8=True, cdl=None):
    a = start
    lines = []
    while a < end:
        o = rom.offset(a)
        f = cdl[o] if (cdl is not None and o is not None) else None
        if f is not None and not (f & 1):
            # Not seen running: a data byte.
            lines.append("%06X  %02X           .db $%02X%s" % (
                a, rom.byte(a), rom.byte(a),
                "   ; read" if f & 4 else ""))
            a = (a & 0xFF0000) | ((a + 1) & 0xFFFF)
            continue
        if f is not None:
            m8, x8 = bool(f & 0x10), bool(f & 0x20)
        n, mn, t, target, raw = decode(rom, a, m8, x8)
        hexb = " ".join("%02X" % b for b in raw)
        lines.append("%06X  %-12s %s %s" % (a, hexb, mn, t))
        if mn == "REP":
            m8 = m8 and not (raw[1] & 0x20)
            x8 = x8 and not (raw[1] & 0x10)
        elif mn == "SEP":
            m8 = m8 or bool(raw[1] & 0x20)
            x8 = x8 or bool(raw[1] & 0x10)
        a = (a & 0xFF0000) | ((a + n) & 0xFFFF)
    return lines


def _addr(s):
    return int(s.replace("$", "").replace(":", ""), 16)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("rom")
    ap.add_argument("start", type=_addr)
    ap.add_argument("end", type=_addr, nargs="?")
    ap.add_argument("--m16", action="store_true")
    ap.add_argument("--x16", action="store_true")
    ap.add_argument("--cdl")
    a = ap.parse_args()
    rom = Rom(open(a.rom, "rb").read())
    cdl = open(a.cdl, "rb").read() if a.cdl else None
    end = a.end if a.end is not None else a.start + 0x40
    for line in listing(rom, a.start, end, not a.m16, not a.x16, cdl):
        print(line)


if __name__ == "__main__":
    sys.exit(main())
