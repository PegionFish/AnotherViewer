#!/usr/bin/env python3
"""Generate the shared V-gate integrity test fixtures (Wave 0 / C2).

Spec: contracts/integrity-vgate.md (self-contained byte-level definition of the
V gate: V1 magic header, V2 end-of-stream marker). Both the server test suite
(anotherviewer-web, Kotlin) and the Android test suite (app, Java) consume the
SAME byte-identical fixtures from their respective
src/test/resources/integrity/ directories; MANIFEST.sha256 (sha256sum format,
written next to this script) is the single source of truth.

Outputs (written into this script's directory, idempotent):
    valid.jpg valid.png valid.gif valid.webp   minimal, genuinely decodable
    truncated.jpg truncated.png                valid sample minus last 8 bytes
    html_disguised.jpg                         HTML error page with .jpg name
    padded.png                                 valid PNG + 16 trailing garbage
    empty.bin                                  0 bytes
    MANIFEST.sha256

After (re)generating: byte-copy all files (including MANIFEST.sha256) to
app/src/test/resources/integrity/ and run `shasum -c MANIFEST.sha256` in both
directories.

Determinism: the JPEG / PNG / GIF87a samples are hand-assembled with the
Python standard library only (zlib/struct), so a re-run on this machine
reproduces the exact bytes; the WebP sample is embedded as base64 (produced
once with `cwebp -lossless`, RIFF length field self-consistent by
construction and re-verified below). The committed fixtures + manifest are
canonical: if a rebuild ever changes bytes, refresh the app-side copy too.

No third-party dependencies; runs on any python3 >= 3.8.
"""

import hashlib
import struct
import zlib
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent

# --- hand-assembled minimal valid samples ------------------------------------


def jpeg_minimal() -> bytes:
    """Baseline JPEG, 8x8 grayscale, JFIF APP0. ~159 bytes."""
    b = bytearray(b"\xFF\xD8")  # SOI
    # APP0 JFIF v1.01, 1x1 aspect, no thumbnail
    b += b"\xFF\xE0" + struct.pack(">H", 16) + b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
    dqt = b"\x00" + bytes([1] * 64)  # table 0, 8-bit precision, all quant values 1
    b += b"\xFF\xDB" + struct.pack(">H", len(dqt) + 2) + dqt
    sof = b"\x08" + struct.pack(">HH", 8, 8) + b"\x01\x01\x11\x00"  # 8x8, 1 comp, 1x1, qtbl 0
    b += b"\xFF\xC0" + struct.pack(">H", len(sof) + 2) + sof

    def dht(cls: int, tid: int, counts: list, values: list) -> bytes:
        p = bytes([(cls << 4) | tid]) + bytes(counts) + bytes(values)
        return b"\xFF\xC4" + struct.pack(">H", len(p) + 2) + p

    b += dht(0, 0, [1] + [0] * 15, [0])  # DC table 0: single 1-bit code -> symbol 0 (diff 0)
    b += dht(1, 0, [1] + [0] * 15, [0])  # AC table 0: single 1-bit code -> symbol 0 (EOB)
    sos = b"\x01\x01\x00\x00\x3F\x00"  # 1 component, DC/AC table 0, Ss 0 Se 63 Ah 0
    b += b"\xFF\xDA" + struct.pack(">H", len(sos) + 2) + sos
    b += b"\x3F"  # entropy: DC code '0' + AC EOB '0', padded with 1-bits (no 0xFF stuffing)
    b += b"\xFF\xD9"  # EOI
    return bytes(b)


def _png_chunk(typ: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data)) + typ + data
        + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)
    )


def png_minimal() -> bytes:
    """Truecolor RGB PNG, 8x8 gradient. ~165 bytes."""
    w = h = 8
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)  # 8-bit, color type 2 (RGB)
    raw = b""
    for y in range(h):
        raw += b"\x00" + bytes(
            v for x in range(w) for v in ((x * 32) % 256, (y * 32) % 256, 128)
        )
    return (
        b"\x89PNG\r\n\x1a\n"
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"IDAT", zlib.compress(raw, 9))
        + _png_chunk(b"IEND", b"")
    )


class _BitWriter:
    """GIF LZW packs codes little-endian (LSB first)."""

    def __init__(self) -> None:
        self._acc = 0
        self._n = 0
        self._out = bytearray()

    def write(self, value: int, width: int) -> None:
        self._acc |= value << self._n
        self._n += width
        while self._n >= 8:
            self._out.append(self._acc & 0xFF)
            self._acc >>= 8
            self._n -= 8

    def flush(self) -> bytes:
        if self._n:
            self._out.append(self._acc & 0xFF)
        return bytes(self._out)


def _gif_lzw(indices: list, min_code_size: int) -> bytes:
    """Minimal GIF LZW encoder, byte-exact GIFCOMPR width semantics: pack the
    current code with the current width, THEN widen once the table (including
    entries added so far, not this iteration's) outgrows the width."""
    clear = 1 << min_code_size
    eoi = clear + 1
    bw = _BitWriter()
    next_code = eoi + 1
    table: dict = {}
    state = {"width": min_code_size + 1}

    def emit(code: int) -> None:
        w = state["width"]
        bw.write(code, w)
        if next_code > (1 << w) - 1 and w < 12:
            state["width"] = w + 1

    emit(clear)
    prefix = indices[0]
    for px in indices[1:]:
        key = (prefix, px)
        if key in table:
            prefix = table[key]
        else:
            emit(prefix)
            table[key] = next_code
            next_code += 1
            prefix = px
    emit(prefix)
    emit(eoi)
    return bw.flush()


def gif87a_minimal() -> bytes:
    """GIF87a, 8x8 2-color checkerboard. 45 bytes."""
    w = h = 8
    pixels = [(x + y) % 2 for y in range(h) for x in range(w)]
    b = b"GIF87a" + struct.pack("<HH", w, h)
    b += bytes([0x80, 0x00, 0x00])  # GCT flag, size field 0 -> 2^(0+1) = 2 entries
    b += b"\xFF\xFF\xFF\x00\x00\x00"  # white, black
    b += b"\x2C" + struct.pack("<HHHH", 0, 0, w, h) + b"\x00"  # image descriptor
    data = _gif_lzw(pixels, 2)
    b += b"\x02"  # LZW minimum code size (GIF floor, fine for 2 colors)
    for i in range(0, len(data), 255):
        chunk = data[i : i + 255]
        b += bytes([len(chunk)]) + chunk
    b += b"\x00"  # block terminator
    b += b"\x3B"  # trailer
    return b


# Embedded WebP: 8x8 lossless VP8L inside a RIFF container, produced once via
#   cwebp -lossless valid.png -o valid.webp   (libwebp, macOS homebrew)
# Verified decodable by sips and PIL; RIFF size field (46) == file size - 8.
# Kept as hex (not base64) so the bytes are directly readable/reviewable.
WEBP_MINIMAL_8X8_LOSSLESS = bytes.fromhex(
    "524946462e000000574542505650384c220000002f07c00100b93244f43f7611d1"
    "ff0061b65151ce9f73af231890018c09a07aa0ff00"
)


def webp_minimal() -> bytes:
    b = WEBP_MINIMAL_8X8_LOSSLESS
    # sanity: RIFF header + self-consistent container length (this is exactly
    # the V2 check the gate performs on WebP)
    assert b[:4] == b"RIFF" and b[8:12] == b"WEBP"
    assert struct.unpack("<I", b[4:8])[0] == len(b) - 8
    return b


# --- derived samples ---------------------------------------------------------

HTML_ERROR_PAGE = (
    b"<html><head><title>Service Unavailable</title></head>"
    b"<body><h1>503 Service Unavailable</h1>"
    b"<p>The upstream server returned an error page.</p></body></html>"
)

PAD_GARBAGE = b"PADDED-GARBAGE16"  # exactly 16 bytes of trailing junk


def build_all() -> dict:
    valid_jpg = jpeg_minimal()
    valid_png = png_minimal()
    return {
        "valid.jpg": valid_jpg,
        "valid.png": valid_png,
        "valid.gif": gif87a_minimal(),
        "valid.webp": webp_minimal(),
        # chop 8 bytes (>= 4 required by spec): tail marker destroyed
        "truncated.jpg": valid_jpg[:-8],
        "truncated.png": valid_png[:-8],
        "html_disguised.jpg": HTML_ERROR_PAGE,
        "padded.png": valid_png + PAD_GARBAGE,
        "empty.bin": b"",
    }


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    fixtures = build_all()
    for name in sorted(fixtures):
        (OUT_DIR / name).write_bytes(fixtures[name])
    manifest = "".join(
        f"{sha256_hex(fixtures[name])}  {name}\n" for name in sorted(fixtures)
    )
    (OUT_DIR / "MANIFEST.sha256").write_text(manifest, encoding="ascii")
    print(f"{'file':<22}{'bytes':>7}  sha256")
    for name in sorted(fixtures):
        print(f"{name:<22}{len(fixtures[name]):>7}  {sha256_hex(fixtures[name])}")
    print(f"\nwrote {len(fixtures)} fixtures + MANIFEST.sha256 to {OUT_DIR}")


if __name__ == "__main__":
    main()
