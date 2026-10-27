#!/usr/bin/env python3
"""Emit the exact Play `edits/{id}/listings/{lang}` payloads for one brand.

    python3 tools/store/listing/to-payload.py dollarvpn
    python3 tools/store/listing/to-payload.py dollarvpn --json > payloads.json

GENERATES ONLY. It creates no edit, sends nothing and needs no credentials, so
it cannot publish by accident.

Why this exists rather than copy-paste: the thing the owner reviews must be
byte-identical to the thing that gets sent. Pasting three fences by hand into a
console for fifteen locales is forty-five chances to drop a trailing newline or
a right-to-left mark, and none of them would be visible in the diff.

⚠️ It REFUSES to emit a payload for a file that fails `validate.py`. A listing
that fails validation should never reach a reviewable payload at all, because a
payload looks finished and a failing .md file looks like work in progress.
"""
import argparse
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from validate import check, fields  # noqa: E402

# Play's applicationId per brand. Permanent — never invent one (CLAUDE.md 6b).
PKG = {
    "rostam": "com.rostamvpn.android",
    "redvpn": "com.redvpn.android",
    "dollarvpn": "vpn.dollarvpn.app",
    "internetpro": "io.internet.pro",
    "goglobe": "esim.goglobe.app",
}


def payloads(brand):
    pkg = PKG[brand]
    out = []
    for f in sorted((HERE / "play" / brand).glob("*.md")):
        errs, _ = check(f, brand)
        if errs:
            raise SystemExit(
                f"{f.name} FAILS validate.py and will not be emitted:\n  "
                + "\n  ".join(errs))
        g = fields(f.read_text(encoding="utf-8"))
        missing = [k for k in ("Title", "Short description", "Full description")
                   if k not in g]
        if missing:
            raise SystemExit(f"{f.name} is missing {missing}")
        out.append({
            "method": "PUT",
            "path": f"/applications/{pkg}/edits/{{editId}}/listings/{f.stem}",
            "body": {
                "language": f.stem,
                "title": g["Title"],
                "shortDescription": g["Short description"],
                "fullDescription": g["Full description"],
            },
        })
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("brand", choices=sorted(PKG))
    ap.add_argument("--json", action="store_true",
                    help="emit the raw payload array and nothing else")
    a = ap.parse_args()

    ps = payloads(a.brand)
    if a.json:
        print(json.dumps(ps, ensure_ascii=False, indent=2))
        return 0

    print(f"{len(ps)} payloads for {a.brand} ({PKG[a.brand]}) — NOTHING SENT\n")
    for p in ps:
        b = p["body"]
        print("=" * 72)
        print(f"{p['method']} {p['path']}")
        print("=" * 72)
        print(json.dumps(b, ensure_ascii=False, indent=2))
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
