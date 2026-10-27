#!/usr/bin/env python3
"""Upload an AAB to a Play track and commit the edit.

    tools/play/upload-bundle.py <pkg> <aab> <track> [--notes "..."]
                                [--status draft|completed] [--apply]

DRY RUN BY DEFAULT. Without --apply it reads the lineage, says what it would
do, and writes nothing.

--status defaults to `completed`. A listing that has never been published
(no completed store listing yet) only accepts `draft` releases, and Play
refuses `completed` there; io.internet.pro was in that state on 2026-10-12.
A draft is then rolled out by a human from Console.

Two things this encodes so nobody re-learns them:

  * The lineage is the union of `bundles` AND `apks`. The keys are PLURAL --
    reading `bundle`/`apk` returns an empty list with no error, which is what
    made earlier lineage reads here confidently say "(none)".

  * `changesNotSentForReview` has flipped direction at least twice. It tracks
    whether the app has an unresolved Console item, and which app that is
    moves. So: try the commit BARE first, and only if Play refuses the other
    way pass the flag. A failed commit costs nothing -- the edit is deleted
    and the versionCode stays free.
"""
import json, sys, urllib.error, urllib.request
import play_api as P

UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"


def upload_bundle(tok, pkg, eid, path):
    body = open(path, "rb").read()
    req = urllib.request.Request(
        f"{UPLOAD}/applications/{pkg}/edits/{eid}/bundles?uploadType=media",
        data=body, method="POST",
        headers={"Authorization": "Bearer " + tok,
                 "Content-Type": "application/octet-stream"})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())


def commit(tok, pkg, eid):
    """Bare first, then with the flag. Returns (ok, sent_for_review, detail)."""
    r = P.call(tok, f"/applications/{pkg}/edits/{eid}:commit", "POST")
    if "_error" not in r:
        return True, True, "auto-sent for review"
    if "changesNotSentForReview" not in r.get("_body", ""):
        return False, False, r.get("_body", "")[:300]
    r = P.call(tok, f"/applications/{pkg}/edits/{eid}:commit", "POST",
               params={"changesNotSentForReview": "true"})
    if "_error" not in r:
        return True, False, "committed UNSENT -- a human must send it from Console"
    return False, False, r.get("_body", "")[:300]


def main():
    a = sys.argv[1:]
    apply_ = "--apply" in a
    a = [x for x in a if x != "--apply"]
    notes = "Bug fixes."
    if "--notes" in a:
        i = a.index("--notes"); notes = a[i + 1]; a = a[:i] + a[i + 2:]
    status = "completed"
    if "--status" in a:
        i = a.index("--status"); status = a[i + 1]; a = a[:i] + a[i + 2:]
    if status not in ("draft", "completed"):
        sys.exit(f"--status must be draft or completed, not {status!r}")
    if len(a) != 3:
        sys.exit(__doc__)
    pkg, aab, track = a

    tok = P.token()
    e = P.call(tok, f"/applications/{pkg}/edits", "POST", {})
    if "_error" in e:
        sys.exit(f"could not open an edit: {e}")
    eid = e["id"]
    try:
        b = P.call(tok, f"/applications/{pkg}/edits/{eid}/bundles")
        k = P.call(tok, f"/applications/{pkg}/edits/{eid}/apks")
        seen = sorted([x["versionCode"] for x in b.get("bundles", [])] +
                      [x["versionCode"] for x in k.get("apks", [])])
        # A listing that has never had a binary has an EMPTY lineage, and
        # max([]) raised before the dry run could say so.
        nxt = max(seen, default=0) + 1
        print(f"{pkg}\n  lineage max {max(seen, default='(none)')}   next free {nxt}   track {track}   status {status}")

        if not apply_:
            print(f"  DRY RUN -- would upload {aab} as a {status} release and commit. Re-run with --apply.")
            return

        up = upload_bundle(tok, pkg, eid, aab)
        vc = up["versionCode"]
        print(f"  uploaded versionCode {vc}  sha1 {up.get('sha1','?')[:16]}")
        if vc < nxt:
            sys.exit(f"  REFUSING: {vc} is at or below the lineage; Play would reject it.")

        r = P.call(tok, f"/applications/{pkg}/edits/{eid}/tracks/{track}", "PUT",
                   {"track": track,
                    "releases": [{"versionCodes": [str(vc)], "status": status,
                                  "releaseNotes": [{"language": "en-US", "text": notes}]}]})
        if "_error" in r:
            sys.exit(f"  track update failed: {r}")
        ok, sent, detail = commit(tok, pkg, eid)
        print(f"  commit: {'OK' if ok else 'FAILED'} -- {detail}")
        if not ok:
            # Leave NOTHING dangling. An abandoned edit keeps the versionCode
            # free either way, but a stale open edit is one more thing to
            # explain later.
            P.call(tok, f"/applications/{pkg}/edits/{eid}", "DELETE")
            sys.exit(1)
        eid = None  # committed; do not delete
    finally:
        if eid:
            P.call(tok, f"/applications/{pkg}/edits/{eid}", "DELETE")


if __name__ == "__main__":
    main()
