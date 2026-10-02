#!/usr/bin/env python3
"""The message banks of both games, as one tagged text, and back.

    tools/msgbank.py dump-n64 ROM OUT.txt        the cartridge's bank (from the dump)
    tools/msgbank.py dump-gc DATA TABLE OUT.txt  a GameCube bank (tools/gciso.py extracts them)
    tools/msgbank.py check-n64 ROM               dump, parse and encode again: must be the same bytes

Both games store a bank as a text file and a table of u32 end offsets, one
per message, and both encode text one byte per character with 0x7F
starting a control code (the code number, then its arguments; the sizes
are the same for the 97 codes the N64 has, the GameCube added 26 more).
The N64 bank is 0x2DE8 messages: the index at vrom 0xCF9000, the text at
0xBD4000 (`func_8009E388_jp` in src/code/m_msg_main.c), both stored
uncompressed in the cartridge, found here through dmadata (at 0x19D40).
The GameCube's `message_data.bin` and `_table.bin` begin with 32 bytes
(eight zero table entries) that the game's ARAM copies skip: message 0
ends at the table's ninth entry (measured: 16,273 terminators in the
data, 16,273 non-zero entries, each 32 bytes earlier).

The text form: characters as themselves, a message's newlines (0xCD) as
newlines, a control code as `<NAME>` or `<NAME hex-arguments>`, a literal
`<` as `\<`, a byte the charset does not name as `{hex}`. Each message is
headed by `## number`; a line starting `#:` is a note and not text. The N64 charset is the AF Project's reading of the
font (its Documentation/text table.txt: kana, ASCII and a few marks; 0x80
is unnamed); the GameCube charset and code names are ac-decomp's
(tools/msg_tool.py, CC0). Nothing of the banks themselves is in this file.
"""

import struct
import sys

# byte -> character; the AF Project's table of the N64 font (0x80 unnamed)
N64_CHARS = ['あ', 'い', 'う', 'え', 'お', 'か', 'き', 'く', 'け', 'こ', 'さ', 'し', 'す', 'せ', 'そ', 'た', 'ち', 'つ', 'て', 'と', 'な', 'に', 'ぬ', 'ね', 'の', 'は', 'ひ', 'ふ', 'へ', 'ほ', 'ま', 'み', ' ', '!', '"', 'む', 'め', '%', '&', "'", '(', ')', '～', '♥', ',', '-', '.', '♪', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':', '💧', '<', '=', '>', '?', '@', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'も', '✚', 'や', 'ゆ', '_', 'よ', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'ら', 'り', 'る', 'れ', '', '', '。', '「', '」', '、', '・', 'ヲ', 'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ャ', 'ュ', 'ョ', 'ッ', 'ー', 'ア', 'イ', 'ウ', 'エ', 'オ', 'カ', 'キ', 'ク', 'ケ', 'コ', 'サ', 'シ', 'ス', 'セ', 'ソ', 'タ', 'チ', 'ツ', 'テ', 'ト', 'ナ', 'ニ', 'ヌ', 'ネ', 'ノ', 'ハ', 'ヒ', 'フ', 'ヘ', 'ホ', 'マ', 'ミ', 'ム', 'メ', 'モ', 'ヤ', 'ユ', 'ヨ', 'ラ', 'リ', 'ル', 'レ', 'ロ', 'ワ', 'ン', 'ヴ', '☺', 'ろ', 'わ', 'を', 'ん', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'ゃ', 'ゅ', 'ょ', 'っ', '\n', 'ガ', 'ギ', 'グ', 'ゲ', 'ゴ', 'ザ', 'ジ', 'ズ', 'ゼ', 'ゾ', 'ダ', 'ヂ', 'ヅ', 'デ', 'ド', 'バ', 'ビ', 'ブ', 'ベ', 'ボ', 'パ', 'ピ', 'プ', 'ペ', 'ポ', 'が', 'ぎ', 'ぐ', 'げ', 'ご', 'ざ', 'じ', 'ず', 'ぜ', 'ぞ', 'だ', 'ぢ', 'づ', 'で', 'ど', 'ば', 'び', 'ぶ', 'べ', 'ぼ', 'ぱ', 'ぴ', 'ぷ', 'ぺ', 'ぽ']
# the GameCube's, from ac-decomp's tools/msg_tool.py (CC0)
GC_CHARS = ['¡', '¿', 'Ä', 'À', 'Á', 'Â', 'Ã', 'Å', 'Ç', 'È', 'É', 'Ê', 'Ë', 'Ì', 'Í', 'Î', 'Ï', 'Ð', 'Ñ', 'Ò', 'Ó', 'Ô', 'Õ', 'Ö', 'Ø', 'Ù', 'Ú', 'Û', 'Ü', 'ß', 'Þ', 'à', ' ', '!', '"', 'á', 'â', '%', '&', "'", '(', ')', '~', '♥', ',', '-', '.', '♪', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':', '🌢', '<', '=', '>', '?', '@', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'ã', '💢', 'ä', 'å', '_', 'ç', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'è', 'é', 'ê', 'ë', '\x7f', '�', 'ì', 'í', 'î', 'ï', '•', 'ð', 'ñ', 'ò', 'ó', 'ô', 'õ', 'ö', '⁰', 'ù', 'ú', 'ー', 'û', 'ü', 'ý', 'ÿ', 'þ', 'Ý', '¦', '§', 'ḏ', 'ṉ', '‖', 'µ', '³', '²', '¹', '¯', '¬', 'Æ', 'æ', '„', '»', '«', '☀', '☁', '☂', '🌬', '☃', '∋', '∈', '/', '∞', '○', '🗙', '□', '△', '+', '⚡', '♂', '♀', '🍀', '★', '💀', '😮', '😄', '😣', '😠', '😃', '×', '➗', '🔨', '🎀', '✉', '💰', '🐾', '🐶', '🐱', '🐰', '🐦', '🐮', '🐷', '\n', '🐟', '🐞', ';', '#', 'Ò', 'Ó', '⚷', 'Õ', 'Ö', '×', 'Ø', 'Ù', 'Ú', 'Û', 'Ü', 'Ỳ', 'ꟓ', 'ß', 'à', 'á', 'â', 'ã', 'ä', 'å', 'æ', 'ç', 'è', 'é', 'ê', 'ë', 'ì', 'í', 'î', 'ï', 'ð', 'ñ', 'ò', 'ó', 'ô', 'õ', 'ö', '÷', 'ø', 'ù', 'ú', 'û', 'ü', 'ý', 'þ', 'ÿ']
# control codes 0..122 (the N64 has 0..96) and their sizes with the 0x7F
COMMANDS = ['MSGEND', 'MSGCONTINUE', 'MSGCLEAR', 'PAUSE', 'BTN', 'TEXTCOLOR', 'ABLECANCEL', 'UNABLECANCEL', 'DEMOPLR', 'DEMONPC0', 'DEMONPC1', 'DEMONPC2', 'DEMONPCQST', 'OPENCHOICE', 'SETFORCEMSG', 'SETNEXTMSG0', 'SETNEXTMSG1', 'SETNEXTMSG2', 'SETNEXTMSG3', 'SETNEXTMSGRND2', 'SETNEXTMSGRND3', 'SETNEXTMSGRND4', 'SETSELSTR2', 'SETSELSTR3', 'SETSELSTR4', 'FORCENEXT', 'STR_PLAYERNAME', 'STR_TALKNAME', 'STR_TAIL', 'STR_YEAR', 'STR_MONTH', 'STR_WEEK', 'STR_DAY', 'STR_HOUR', 'STR_MIN', 'STR_SEC', 'STR_FREE0', 'STR_FREE1', 'STR_FREE2', 'STR_FREE3', 'STR_FREE4', 'STR_FREE5', 'STR_FREE6', 'STR_FREE7', 'STR_FREE8', 'STR_FREE9', 'STR_DETERMINATION', 'STR_COUNTRYNAME', 'STR_RNDNUM', 'STR_ITEM0', 'STR_ITEM1', 'STR_ITEM2', 'STR_ITEM3', 'STR_ITEM4', 'STR_FREE10', 'STR_FREE11', 'STR_FREE12', 'STR_FREE13', 'STR_FREE14', 'STR_FREE15', 'STR_FREE16', 'STR_FREE17', 'STR_FREE18', 'STR_FREE19', 'STR_MAIL', 'LUCK_NEUTRAL', 'LUCK_RELATIONSHIP', 'LUCK_UNPOPULAR', 'LUCK_BAD', 'LUCK_MONEY', 'LUCK_GOODS', 'LUCK_6', 'LUCK_7', 'LUCK_8', 'LUCK_9', 'MSGCONTENTS_NORMAL', 'MSGCONTENTS_ANGRY', 'MSGCONTENTS_SAD', 'MSGCONTENTS_FUN', 'MSGCONTENTS_SLEEPY', 'COLORCHARS', 'SNDCUT', 'LINEOFS', 'LINETYPE', 'CHARSCALE', 'BTN2', 'BGMMAKE', 'BGMDELETE', 'MSGTIMEEND', 'SNDTRGSYS', 'LINESCALE', 'SNDNOPAGE', 'VOICETRUE', 'VOICEFALSE', 'SELNOB', 'GIVEOPEN', 'GIVECLOSE', 'MSGCONTENTS_GLOOMY', 'SELNOBCLOSE', 'SETNEXTMSGRNDSECTION', 'AGBDUMMY0', 'AGBDUMMY1', 'AGBDUMMY2', 'SPACE', 'AGBDUMMY3', 'AGBDUMMY4', 'MALEFEMALECHK', 'AGBDUMMY5', 'AGBDUMMY6', 'AGBDUMMY7', 'AGBDUMMY8', 'AGBDUMMY9', 'AGBDUMMY10', 'STR_ISLANDNAME', 'SETCURSORJUST', 'CLRCUSRORJUST', 'CUTARTICLE', 'CAPTIALIZE', 'STR_AMPM', 'SETNEXTMSG4', 'SETNEXTMSG5', 'SETSELSTR5', 'SETSELSTR6']
SIZES = [2, 2, 2, 3, 2, 5, 2, 2, 5, 5, 5, 5, 5, 2, 4, 4, 4, 4, 4, 6, 8, 10, 6, 8, 10, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 6, 3, 3, 3, 3, 2, 4, 4, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2, 2, 6, 3, 3, 4, 3, 2, 2, 6, 2, 2, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 4, 4, 12, 14]

CODE_NUM_N64 = 0x61                      # D_80106BF4_jp: (size, kind) per code, 97 codes
NAMES = {name: i for i, name in enumerate(COMMANDS)}
N64_BYTES = {c: i for i, c in reversed(list(enumerate(N64_CHARS))) if c}
TERMINATORS = (NAMES["MSGEND"], NAMES["MSGCONTINUE"], NAMES["MSGTIMEEND"])


def decode(raw, chars):
    """[('t', text) | ('c', code, args)] from a message's bytes."""
    out, buf, i = [], [], 0
    while i < len(raw):
        b = raw[i]
        if b == 0x7F:
            if buf:
                out.append(("t", "".join(buf)))
                buf = []
            code = raw[i + 1]
            n = SIZES[code] if code < len(SIZES) else 2
            out.append(("c", code, bytes(raw[i + 2:i + n])))
            i += n
        else:
            c = chars[b] if b < len(chars) else ""
            buf.append(c if c else "{%02x}" % b)
            i += 1
    if buf:
        out.append(("t", "".join(buf)))
    return out


def render(tokens):
    parts = []
    for t in tokens:
        if t[0] == "t":
            parts.append(t[1].replace("\\", "\\\\").replace("<", "\\<"))
        else:
            name = COMMANDS[t[1]] if t[1] < len(COMMANDS) else "CODE%02X" % t[1]
            parts.append("<%s%s>" % (name, (" " + t[2].hex()) if t[2] else ""))
    return "".join(parts)


def parse(text):
    """The inverse of render."""
    out, buf, i = [], [], 0
    while i < len(text):
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            buf.append(text[i + 1])
            i += 2
        elif c == "<":
            j = text.index(">", i)
            if buf:
                out.append(("t", "".join(buf)))
                buf = []
            name, _, args = text[i + 1:j].partition(" ")
            code = NAMES[name] if name in NAMES else int(name[4:], 16)
            out.append(("c", code, bytes.fromhex(args)))
            i = j + 1
        else:
            buf.append(c)
            i += 1
    if buf:
        out.append(("t", "".join(buf)))
    return out


def encode(tokens, charset=None):
    """A message's bytes, N64 charset by default. ValueError names a character
    the charset lacks."""
    table = charset if charset is not None else N64_BYTES
    out = bytearray()
    for t in tokens:
        if t[0] == "c":
            out += bytes((0x7F, t[1])) + t[2]
            continue
        s, i = t[1], 0
        while i < len(s):
            c = s[i]
            if c == "{":
                out.append(int(s[i + 1:i + 3], 16))
                i += 4
                continue
            if c not in table:
                raise ValueError("no %r in the charset" % c)
            out.append(table[c])
            i += 1
    return bytes(out)


class Bank:
    """messages: list of bytes."""

    def __init__(self, messages, chars):
        self.messages, self.chars = messages, chars

    def __len__(self):
        return len(self.messages)

    def tokens(self, i):
        return decode(self.messages[i], self.chars)

    def text(self, i):
        return render(self.tokens(i))

    @classmethod
    def from_table(cls, data, table, chars, header=0):
        """A (text, end offsets) pair; `header` bytes lead both files."""
        ends = struct.unpack(">%dI" % (len(table) // 4), table)[header // 4:]
        text = data[header:]
        last = max((i for i, e in enumerate(ends) if e), default=-1)
        messages, prev = [], 0
        for e in ends[:last + 1]:
            messages.append(text[prev:e] if e else b"")
            prev = e if e else prev
        return cls(messages, chars)

    @classmethod
    def from_n64(cls, rom):
        """From the cartridge (big-endian), through dmadata."""
        def prom(vrom):
            off = 0x19D40
            while True:
                vs, ve, ps, pe = struct.unpack(">IIII", rom[off:off + 16])
                if (vs, ve, ps, pe) == (0, 0, 0, 0):
                    raise ValueError("vrom %#x is not in dmadata" % vrom)
                if vs <= vrom < ve:
                    if pe:
                        raise ValueError("vrom %#x is compressed in this ROM" % vrom)
                    return ps + (vrom - vs), ve - vrom
                off += 16
        idx, idx_size = prom(0xCF9000)
        txt, txt_size = prom(0xBD4000)
        n = 0x2DE8
        return cls.from_table(rom[txt:txt + txt_size], rom[idx:idx + 4 * n], N64_CHARS)

    @classmethod
    def from_gc(cls, data, table):
        return cls.from_table(data, table, GC_CHARS, header=32)

    def dump(self):
        out = []
        for i, m in enumerate(self.messages):
            text = self.text(i)
            if text.endswith("\n"):
                raise ValueError("message %d ends with a newline; the dump could not keep it" % i)
            out.append("## %d\n%s\n\n" % (i, text))
        return "".join(out)

    @staticmethod
    def parse_dump(text):
        """{number: tokens} from dump()'s output."""
        out, number, lines = {}, None, []

        def flush():
            if number is not None:
                body = "\n".join(lines)
                while body.endswith("\n"):
                    body = body[:-1]
                out[number] = parse(body)

        for line in text.split("\n"):
            if line.startswith("## ") and line[3:].strip().isdigit():
                flush()
                number, lines = int(line[3:]), []
            elif line.startswith("#:"):
                continue                        # a note for the reader, not text
            else:
                lines.append(line)
        flush()
        return out


def main(argv):
    cmd = argv[0]
    if cmd == "dump-n64":
        bank = Bank.from_n64(open(argv[1], "rb").read())
        open(argv[2], "w", encoding="utf-8").write(bank.dump())
        print("%s: %d messages" % (argv[2], len(bank)))
    elif cmd == "dump-gc":
        bank = Bank.from_gc(open(argv[1], "rb").read(), open(argv[2], "rb").read())
        open(argv[3], "w", encoding="utf-8").write(bank.dump())
        print("%s: %d messages" % (argv[3], len(bank)))
    elif cmd == "check-n64":
        bank = Bank.from_n64(open(argv[1], "rb").read())
        back = Bank.parse_dump(bank.dump())
        bad = [i for i, m in enumerate(bank.messages) if encode(back[i]) != m]
        print("round trip: %d messages, %d differ" % (len(bank), len(bad)), bad[:5])
        return 1 if bad else 0
    else:
        raise SystemExit(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
