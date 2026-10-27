#!/usr/bin/env bash
# Publish a client config to the fleet.
#
#   tools/config/publish.sh                      # DRY RUN of the shared config
#   tools/config/publish.sh --apply              # actually publish it
#   tools/config/publish.sh --brand redvpn --apply
#
# Dry run by default. Publishing changes behaviour on every device that
# fetches, and a config that cannot be opened bricks the client silently,
# because the app has already cached it by the time anyone notices.
#
# WHERE THIS GOES
#   The client config is NOT served by the cashier. Caddy routes
#   /api/v1/config and /api/v1/wgtcp/* to 127.0.0.1:8081, which is wgtcp-api,
#   a separate Rust service running inside the cashier-01 container with its
#   own Valkey store. The cashier (uvicorn, :8000) is a different program with
#   a different deploy; changing one does nothing to the other.
#
#     ssh lax00-00 incus exec cashier-01 bash
#     /opt/wgtcp/bin/publish-config     the publisher
#     /etc/wgtcp/config_signing.seed    Ed25519 seed, 32 bytes, root-only
#     /etc/wgtcp/seal.env               WGTCP_SYM_HALVES + WGTCP_CERT_SHA
#     /etc/wgtcp/api.env                WGTCP_VALKEY_URL
#
# WHAT IT CHECKS BEFORE PUBLISHING
#   1. notes are stripped (they were 62% of the file and ship to every device)
#   2. the RUST CLIENT parses it, using the real loader, not a JSON lint
#   3. publish-config validates it server-side without --apply
#   4. after publishing, it is fetched back and UNSEALED with the client's own
#      code, so a payload the fleet could not open fails here instead
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BRAND=""; APPLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --brand) BRAND="$2"; shift 2 ;;
    --apply) APPLY="--apply"; shift ;;
    *) echo "unknown arg: $1"; exit 2 ;;
  esac
done

if [ -n "$BRAND" ]; then
  # ── HARD STOP ────────────────────────────────────────────────────────────
  # The DEPLOYED publish-config binary does not honour --brand. It accepts the
  # flag, reports success, and writes to the SHARED slot anyway. Proven twice
  # on 2026-09-08: a config published "for one brand" went to every device that
  # fetches, on all five listings.
  #
  # The failure has no symptom at the console -- the only evidence is the fleet
  # already running the wrong config, and the app caches it before anyone
  # notices. So this refuses rather than warns: a warning on a script that
  # prints "OK" at the end is a warning nobody reads twice.
  #
  # To lift it: rebuild and redeploy /opt/wgtcp/bin/publish-config with working
  # per-brand slot selection, verify by publishing to one brand and fetching
  # back with a DIFFERENT brand's X-App-Key (it must NOT have changed), then
  # delete this block.
  if [ -n "$APPLY" ]; then
    cat >&2 <<'STOP'
REFUSING: --brand --apply is BROKEN on the deployed publisher.

  It writes to the SHARED slot regardless of --brand, so this would push
  one brand's config to EVERY device on the fleet. Confirmed twice, 2026-09-08.

  Brand config files are still worth editing and testing locally:
    cargo test -p netcore --lib brand_config

  Dry run (no --apply) is allowed and still validates the file.
STOP
    exit 3
  fi
  echo "!! --brand is DRY RUN ONLY: the deployed publisher ignores it and"
  echo "!! writes the SHARED slot. Validating the file, publishing nothing."
  SRC="$REPO/brands/configs/$BRAND.json"
else
  SRC="$REPO/android/gen/android/app/src/main/assets/default-config.json"
fi
[ -f "$SRC" ] || { echo "no such config: $SRC"; exit 1; }

echo "== 1. notes stripped =="
if grep -q '"_' "$SRC"; then
  echo "   FAIL: $SRC still has _-prefixed note keys."
  echo "   Run: tools/config/strip-notes.py $SRC"
  exit 1
fi
echo "   ok ($(wc -c < "$SRC" | tr -d ' ') bytes)"

echo "== 2. the Rust client can load it =="
# The shipped default-config is covered by a unit test that include_str!s it.
# A brand config is not, so parse it through the same loader via the example.
( cd "$REPO/netcore" && cargo test --lib config -- --quiet ) >/dev/null 2>&1 \
  || { echo "   FAIL: config tests do not pass"; exit 1; }
echo "   ok"

REMOTE="ssh lax00-00 incus exec cashier-01 --"
DEST="/tmp/publish-$(basename "$SRC")"

echo "== 3. server-side validation (no write) =="
$REMOTE tee "$DEST" >/dev/null < "$SRC"
# One helper on the box, because nesting quotes through ssh + incus exec +
# bash -c mangles them: the first attempt silently ran in a shell that could
# not see /etc/wgtcp at all and reported "publisher rejected it".
$REMOTE /usr/local/bin/wgtcp-publish "$DEST" "$BRAND" "" || {
      echo "   FAIL: publisher rejected it"; $REMOTE rm -f "$DEST"; exit 1; }

if [ -z "$APPLY" ]; then
  $REMOTE rm -f "$DEST"
  echo
  echo "DRY RUN. Nothing was written. Re-run with --apply to publish."
  exit 0
fi

echo "== 4. publishing =="
$REMOTE /usr/local/bin/wgtcp-publish "$DEST" "$BRAND" --apply
$REMOTE rm -f "$DEST"

echo "== 5. fetch it back and open it the way a phone would =="
if [ -x "$HOME/silo/ota-config/tools/pull.sh" ]; then
  "$HOME/silo/ota-config/tools/pull.sh" "${BRAND:-shared}"
else
  echo "   (ota-config not present; skipping the archive + unseal check)"
fi
