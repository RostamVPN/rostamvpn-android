#!/usr/bin/env python3
# Copyright 2026 Internet Mastering & Company, Inc.
# SPDX-License-Identifier: Apache-2.0
"""Wrap a sealed config in a real PNG, so a public image proxy will carry it.

WHY. Every `api.base` the client knows resolves to one machine, 203.0.113.242.
Different hostnames on one IP defeat DNS poisoning and nothing else, so a single
IP block removes the config channel entirely -- and the config is how a client
learns where everything else is.

A public image proxy fixes that: the client's connection is to the PROXY, and
the proxy fetches our origin from its own network, so our address never appears
in the client's traffic. But those proxies carry IMAGES. Measured against
slack-imgs.com on 2026-09-11:

    text content type          -> 415 Unsupported Media Type
    our /api/v1/config         -> 404
    a real PNG, no resize      -> 200, and BYTE-IDENTICAL in and out
    a real PNG, o1=wi32.he32.si-> 200, resized 272x92 down to 32x32

So the payload has to be inside a valid PNG, and the mirror URL must not carry a
resize parameter. The byte-identical result is what makes this work at all: it
means a private chunk survives untouched.

WHERE THE PAYLOAD LIVES. A PNG private ancillary chunk, not the pixels. Pixels
would mean the client calls BitmapFactory, which premultiplies alpha and may
apply a colour-space transform -- both silently corrupt bytes that were never
meant to be colours. A chunk is exact, and every PNG decoder on earth skips an
unknown ancillary chunk, so the file stays a valid image to anything that looks.

The chunk name is deliberately unremarkable. A name describing the contents
would be a string worth grepping for on a decompiled binary, which is the same
reasoning that neutralised the peer vocabulary.

    wrap-png.py  < sealed.txt  > config.png
    wrap-png.py --unwrap < config.png     # round trip, prints the payload
    wrap-png.py --selftest                # no network, no files
"""
import binascii
import struct
import sys
import zlib
CHUNK = b"msDt"


def _chunk(kind: bytes, data: bytes) -> bytes:
    """One PNG chunk: length, type, data, CRC32 over type+data."""
    return (struct.pack(">I", len(data)) + kind + data
            + struct.pack(">I", binascii.crc32(kind + data) & 0xFFFFFFFF))


def wrap(payload: bytes) -> bytes:
    """A minimal valid PNG carrying `payload` in a private chunk.

    1x1 greyscale, because the image itself is not the point and every byte of
    it is paid for by someone on a metered connection in a censored country. The
    image still has to be REAL: a proxy that validates will fetch it, and one
    that resizes will decode it.
    """
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 0, 0, 0, 0)
    idat = zlib.compress(b"\x00\x00")
    return (b"\x89PNG\r\n\x1a\n"
            + _chunk(b"IHDR", ihdr)
            + _chunk(CHUNK, payload)
            + _chunk(b"IDAT", idat)
            + _chunk(b"IEND", b""))


def unwrap(png: bytes) -> bytes | None:
    """The payload, or None if this is just an image.

    Deliberately the same walk as `RemoteConfig.pngPayload`, including refusing
    a chunk whose declared length does not fit: walking past one would read
    whatever follows as a chunk header, which is how a hostile file steers a
    parser.
    """
    if len(png) < 20 or png[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    p = 8
    while p + 8 <= len(png):
        (length,) = struct.unpack(">I", png[p:p + 4])
        if p + 12 + length > len(png):
            return None
        if png[p + 4:p + 8] == CHUNK:
            return png[p + 8:p + 8 + length]
        p += 12 + length
    return None


def selftest() -> int:
    checks, failed = [], 0
    def ok(name, cond):
        nonlocal failed
        checks.append((name, cond))
        if not cond:
            failed += 1
    payload = b"A" * 7484
    png = wrap(payload)
    ok("round trips", unwrap(png) == payload)
    ok("has the PNG signature", png[:8] == b"\x89PNG\r\n\x1a\n")
    ok("ends at IEND", png.endswith(_chunk(b"IEND", b"")))
    ok("overhead under 200 bytes", len(png) - len(payload) < 200)
    ok("a plain PNG yields nothing", unwrap(wrap(b"")[:8] + _chunk(b"IHDR", struct.pack(
        ">IIBBBBB", 1, 1, 8, 0, 0, 0, 0)) + _chunk(b"IEND", b"")) is None)
    ok("not a PNG yields nothing", unwrap(b"not a png at all") is None)
    ok("truncated yields nothing", unwrap(png[:len(png) // 2]) is None)
    lying = png[:8] + struct.pack(">I", 0xFFFFFF) + b"junk" + png[16:]
    ok("a lying chunk length stops the walk", unwrap(lying) is None)
    try:
        from PIL import Image
        import io
        im = Image.open(io.BytesIO(png))
        im.load()
        ok(f"a real decoder opens it ({im.size[0]}x{im.size[1]} {im.mode})", True)
    except ImportError:
        checks.append(("Pillow absent, decoder check skipped", None))
    except Exception as e:
        ok(f"a real decoder opens it -- {e}", False)
    for name, c in checks:
        print(f"  {'skip' if c is None else 'ok  ' if c else 'FAIL'}  {name}")
    print(f"\n  {len(checks)} checks, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    if "--unwrap" in sys.argv:
        out = unwrap(sys.stdin.buffer.read())
        if out is None:
            sys.stderr.write("no payload in that file\n")
            sys.exit(1)
        sys.stdout.buffer.write(out)
    else:
        sys.stdout.buffer.write(wrap(sys.stdin.buffer.read().strip()))
