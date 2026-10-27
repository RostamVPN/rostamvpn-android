#!/usr/bin/env python3
"""Promote a versionCode to a Play track.

    tools/play/promote.py <pkg> <versionCode> <track> [--fraction 0.1] [--apply]

DRY RUN BY DEFAULT. Without --apply it prints what is live now and what would
change, and writes nothing.

🚨 A STAGED ROLLOUT IS THE DEFAULT FOR PRODUCTION, and it is not politeness.
§22b: these apps have ~755K installs and Play has no undo — a completed release
reaches everyone at once and the only remedy afterwards is halting it, which
leaves whoever already updated on the bad build. Pass --fraction 1.0 to go
straight to everyone, deliberately.

A halt is `--fraction` on an inProgress release with status halted; Play rejects
a halt that omits the fraction the release already had, which is why this prints
it.

Commit behaviour mirrors upload-bundle.py: bare first, then with
changesNotSentForReview, because Play has flipped which one it accepts twice.
"""
import json, sys, urllib.error, urllib.request
import play_api as P

def main():
    a = [x for x in sys.argv[1:] if x != "--apply"]
    apply_ = "--apply" in sys.argv
    frac = None
    if "--fraction" in a:
        i = a.index("--fraction"); frac = float(a[i + 1]); a = a[:i] + a[i + 2:]
    if len(a) != 3:
        sys.exit(__doc__)
    pkg, vc, track = a
    if track == "production" and frac is None:
        frac = 0.1

    tok = P.token()
    eid = P.call(tok, f"/applications/{pkg}/edits", "POST")["id"]
    try:
        cur = P.call(tok, f"/applications/{pkg}/edits/{eid}/tracks/{track}")
        for r in cur.get("releases", []):
            print(f"  live now: vc={r.get('versionCodes')} status={r.get('status')} "
                  f"fraction={r.get('userFraction')}")

        rel = {"versionCodes": [str(vc)],
               "releaseNotes": [{"language": "en-US", "text": "Bug fixes."}]}
        if frac is not None and frac < 1.0:
            rel["status"] = "inProgress"; rel["userFraction"] = frac
        else:
            rel["status"] = "completed"
        print(f"  would set: vc={vc} status={rel['status']} "
              f"fraction={rel.get('userFraction', 'all')}")

        if not apply_:
            print("  DRY RUN -- re-run with --apply")
            return
        P.call(tok, f"/applications/{pkg}/edits/{eid}/tracks/{track}", "PUT",
               body={"track": track, "releases": [rel]})
        r = P.call(tok, f"/applications/{pkg}/edits/{eid}:commit", "POST")
        if "_error" in r:
            r = P.call(tok, f"/applications/{pkg}/edits/{eid}:commit", "POST",
                       params={"changesNotSentForReview": "true"})
        if "_error" in r:
            print("  COMMIT FAILED:", r.get("_body", "")[:300]); sys.exit(1)
        print(f"  {pkg} {track} -> vc {vc} "
              f"({'all users' if rel['status'] == 'completed' else str(frac)})")
        eid = None
    finally:
        # An abandoned edit keeps nothing reserved, but leaving it dangling makes
        # the next read of this listing ambiguous.
        if eid:
            try: P.call(tok, f"/applications/{pkg}/edits/{eid}", "DELETE")
            except Exception: pass

main()
