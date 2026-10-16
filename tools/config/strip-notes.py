#!/usr/bin/env python3
# Copyright 2026 Internet Mastering & Company, Inc.
# SPDX-License-Identifier: Apache-2.0
"""Move `_*` note keys out of a config and into a companion Markdown file.

Why this exists: 62% of default-config.json was prose. The file is sealed,
served from Redis and fetched by ~750K devices on censored, metered
connections, so every byte of commentary was paid for by a user in Iran on a
slow link, and again on every refresh.

The notes themselves are worth keeping. They record failures somebody already
paid for once ("this key ships identically to every install", "IR is off,
owner's call"). Deleting them loses that. So they move to
<config>.notes.md next to the file, keyed by their JSON path, and the shipped
JSON carries only what the client parses.

    tools/config/strip-notes.py android/.../default-config.json
    tools/config/strip-notes.py brands/configs/*.json

Idempotent: running it on an already-stripped file appends nothing.
"""
import json, sys, collections, os, datetime


def walk(node, path, out):
    """Pull every _-prefixed key out, recording where it came from."""
    if isinstance(node, dict):
        clean = collections.OrderedDict()
        for k, v in node.items():
            here = f"{path}.{k}" if path else k
            if k.startswith("_"):
                out.append((path or "(root)", k, v))
            else:
                clean[k] = walk(v, here, out)
        return clean
    if isinstance(node, list):
        return [walk(x, f"{path}[{i}]", out) for i, x in enumerate(node)]
    return node


def main(paths):
    for p in paths:
        raw = open(p, encoding="utf-8").read()
        d = json.loads(raw, object_pairs_hook=collections.OrderedDict)
        notes = []
        clean = walk(d, "", notes)
        if not notes:
            print(f"  {p}: already clean")
            continue
        json.dump(clean, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        open(p, "a", encoding="utf-8").write("\n")
        md = os.path.splitext(p)[0] + ".notes.md"
        with open(md, "w", encoding="utf-8") as f:
            f.write(f"# Notes for `{os.path.basename(p)}`\n\n")
            f.write("Moved out of the JSON on "
                    f"{datetime.date.today()} because they were 62% of a file that "
                    "ships sealed to every device on a metered connection.\n\n"
                    "They are kept because each one records something that already "
                    "cost somebody a debugging session. Edit them here; they are no "
                    "longer read by anything.\n\n")
            by = collections.OrderedDict()
            for where, key, val in notes:
                by.setdefault(where, []).append((key, val))
            for where, items in by.items():
                f.write(f"## `{where}`\n\n")
                for key, val in items:
                    f.write(f"**`{key}`**\n\n")
                    text = val if isinstance(val, str) else json.dumps(val, ensure_ascii=False, indent=1)
                    f.write(f"{text}\n\n")
        after = len(open(p, encoding="utf-8").read())
        print(f"  {p}: {len(raw)} -> {after} bytes "
              f"({100 - 100*after//len(raw)}% smaller), {len(notes)} notes -> {md}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
