#!/usr/bin/env python3
"""Generate the checked-in Noto Emoji sprites and CLDC lookup tables.

The core sheet keeps the old 176-cell layout so the reaction palette and the
most frequent single-codepoint emoji remain available without decoding the
larger sheet. The extended sheet is built from tools/emoji-sources.txt: extra
single code points first, then whole-sequence sprites (flags, keycaps, ZWJ
compositions and common skin-tone variants). It is decoded lazily by
EmojiText, only when a message actually contains one of those tokens.
"""
from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
NOTO_VERSION = "v2.047"
NOTO_SOURCE = (
    "https://github.com/googlefonts/noto-emoji/releases/tag/"
    + NOTO_VERSION
)
REACTIONS = "👍❤🤣😱😢🙏🔥👎🎉🤔😍🤯"
EXT_BASE = 4096

# Single-codepoint subset: common Unicode frequency leaders plus the fixed
# reaction palette. This list is kept small and stable: the send palette must
# always render, and it must not require the extended sheet to be decoded.
EMOJI = (
    "😂❤😍🤣😊🙏💕😭😘👍😅👏😁🔥🥰💔💖💙🎉☺✨😢💗😆🤔💪😀🥳😎👌"
    "🤩😔😡🥺😳💜🙌🤗💚👀😋😱💛😉💯😞😄😃😬🤭😐🤨😇🤦🤷🙄😏😌"
    "😴🤤😷🤒🤕🤢🤮🤧🥵🥶🥴😵🤯🤠🥸😈👿👻💀☠👽🤖💩😺😸😹😻"
    "😼😽🙀😿😾👋🤚🖐✋🖖🤏✌🤞🤟🤘🤙👈👉👆👇☝👎✊👊🤛🤜"
    "🤝👐🤲🤳💅👂👃🧠🫀🫁👣👶👧🧒👦👩🧑👨👵🧓👴🌹🌸🌞⭐"
    "🌈☀☁⚡❄☕🍕🍰🎂🍻🥂⚽🏀🏆🎵🎶📷📱💡🎁🚀✅❌⚠"
    "🥹🫠🫢🫣🫡🫶🫰🫵🤌🥲🫄🫃🦄🦊🐼🐸🐧🦁🐯🪄🧿"
    "🌻🌵🌊🌙💫💥💬💤🧨🔔🔑🗺🧭🏅🥇🎈🎤🎮🧩📝📌💰💳🔒"
)


def core_codepoints() -> list[int]:
    cps: list[int] = []
    for char in REACTIONS + EMOJI:
        if ord(char) == 0xFE0F:
            continue
        cp = ord(char)
        if cp not in cps:
            cps.append(cp)
    return cps[:176]


def read_sources(path: Path) -> tuple[list[int], list[list[int]]]:
    singles: list[int] = []
    sequences: list[list[int]] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        kind = parts[0]
        cps = [int(value, 16) for value in parts[1:]]
        if not cps:
            continue
        if kind == "S" and len(cps) == 1:
            if cps[0] not in singles:
                singles.append(cps[0])
        elif kind == "Q" and len(cps) > 1:
            if cps not in sequences:
                sequences.append(cps)
        else:
            raise ValueError("bad emoji source line: %r" % raw)
    return singles, sequences


def render_sheet(entries: list[list[int]], font: ImageFont.FreeTypeFont) -> Image.Image:
    columns = 16
    rows = (len(entries) + columns - 1) // columns
    sheet = Image.new("RGBA", (columns * 16, rows * 16), (0, 0, 0, 0))
    for cell, cps in enumerate(entries):
        text = "".join(chr(cp) for cp in cps)
        tile = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
        draw = ImageDraw.Draw(tile)
        draw.text((64, 64), text, font=font, anchor="mm",
                  embedded_color=True, stroke_width=0)
        tile = tile.resize((16, 16), Image.Resampling.LANCZOS)
        sheet.alpha_composite(tile, ((cell % columns) * 16, (cell // columns) * 16))
    return sheet


def save_sheet(sheet: Image.Image, path: Path) -> None:
    # Indexed transparency compresses far better than a 32-bit sheet in a JAR.
    sheet.quantize(colors=96, method=Image.Quantize.FASTOCTREE).save(
        path, optimize=True)


def java_array(name: str, values: list[int], per_line: int = 8) -> list[str]:
    lines = ["    static final int[] %s = {" % name]
    for i in range(0, len(values), per_line):
        lines.append("        " + ", ".join("0x%x" % v for v in values[i:i + per_line]) + ",")
    lines.append("    };")
    return lines


def write_java(core: list[int], ext_singles: list[int],
               sequences: list[tuple[list[int], int]], path: Path) -> None:
    core_order = sorted((cp, cell) for cell, cp in enumerate(core))
    ext_order = sorted((cp, EXT_BASE + cell) for cell, cp in enumerate(ext_singles))
    seq_order = sorted(sequences, key=lambda item: (item[0][0], len(item[0])))

    lines = [
        "package tg.ui;",
        "",
        "/** GENERATED from Noto Emoji " + NOTO_VERSION
        + " by tools/generate-emoji.py; do not edit. */",
        "final class EmojiData",
        "{",
        "    static final int EXT_BASE = 0x%x;" % EXT_BASE,
    ]
    lines += java_array("CORE_CODEPOINTS", [cp for cp, _ in core_order])
    lines += ["    static final short[] CORE_CELLS = {"]
    for i in range(0, len(core_order), 16):
        lines.append("        " + ", ".join(str(cell) for _, cell in core_order[i:i + 16]) + ",")
    lines += [
        "    };",
        "    // EmojiPickerScreen still builds its palette from the core sheet.",
        "    static final int[] CODEPOINTS = CORE_CODEPOINTS;",
        "    static final short[] CELLS = CORE_CELLS;",
    ]
    lines += java_array("EXT_CODEPOINTS", [cp for cp, _ in ext_order])
    lines += ["    static final short[] EXT_CELLS = {"]
    for i in range(0, len(ext_order), 16):
        lines.append("        " + ", ".join(str(cell) for _, cell in ext_order[i:i + 16]) + ",")
    lines += ["    };"]

    lines += java_array("SEQ_FIRST", [cps[0] for cps, _ in seq_order])
    lines += ["    static final int[][] SEQ_CPS = {"]
    for cps, _ in seq_order:
        lines.append("        {" + ", ".join("0x%x" % cp for cp in cps) + "},")
    lines += ["    };", "    static final short[] SEQ_CELLS = {"]
    for i in range(0, len(seq_order), 12):
        lines.append("        " + ", ".join(str(cell) for _, cell in seq_order[i:i + 12]) + ",")
    lines += [
        "    };",
        "    private EmojiData() { }",
        "}",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--font", required=True, type=Path)
    parser.add_argument("--font-size", type=int, default=109,
                        help="NotoColorEmoji bitmap strike size")
    parser.add_argument("--sources", type=Path,
                        default=ROOT / "tools" / "emoji-sources.txt")
    args = parser.parse_args()

    font = ImageFont.truetype(str(args.font), args.font_size)
    core = core_codepoints()
    core_set = set(core)
    source_singles, source_sequences = read_sources(args.sources)
    ext_singles = [cp for cp in source_singles if cp not in core_set]

    sequence_entries: list[tuple[list[int], int]] = []
    ext_entries: list[list[int]] = [[cp] for cp in ext_singles]
    for cps in source_sequences:
        sequence_entries.append((cps, EXT_BASE + len(ext_entries)))
        ext_entries.append(cps)

    core_sheet = render_sheet([[cp] for cp in core], font)
    ext_sheet = render_sheet(ext_entries, font)
    out_core = ROOT / "res" / "emoji.png"
    out_ext = ROOT / "res" / "emoji_ext.png"
    out_core.parent.mkdir(parents=True, exist_ok=True)
    save_sheet(core_sheet, out_core)
    save_sheet(ext_sheet, out_ext)

    java = ROOT / "src" / "tg" / "ui" / "EmojiData.java"
    java.parent.mkdir(parents=True, exist_ok=True)
    write_java(core, ext_singles, sequence_entries, java)
    print("emoji: core %d (%dx%d, %d bytes), ext %d glyphs incl. %d sequences"
          " (%dx%d, %d bytes)" %
          (len(core), core_sheet.width, core_sheet.height, out_core.stat().st_size,
           len(ext_entries), len(sequence_entries), ext_sheet.width,
           ext_sheet.height, out_ext.stat().st_size))


if __name__ == "__main__":
    main()
