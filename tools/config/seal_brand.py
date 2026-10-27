"""Seal a brand config and write ONLY that brand's keys.

The deployed publish-config (Aug 27) accepts --brand, validates the slug, and
then writes the SHARED key anyway: `strings` finds zero `client_config:brand`.
That has already put one brand's config on the whole fleet twice.

This never touches the shared slot. It derives the key exactly as
publish-config.rs and the client's cfgseal.rs do -- same order, same label --
because any divergence produces a config that publishes cleanly and fails to
open on every device.
"""
import hashlib, json, os, secrets, sys
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.asymmetric import ed25519

brand = sys.argv[1]
src   = sys.argv[2]

# WHICH GENERATION this config is for. Default 2, because this script now seals
# WITHOUT the certificate and a cert-less config belongs in the v2 slot: v1 is
# read by every client shipped before the two-key unseal, which derives its key
# WITH the certificate and has nothing else to try. Writing a cert-less body to
# a v1 key would leave those installs unable to open anything -- and because a
# client keeps the config it has when one fails to open, they would freeze on
# their last-read config silently, with no error, until the app updated.
#
# Pass `1` ONLY together with WGTCP_SEAL_WITH_CERT=1, i.e. to republish a
# cert-sealed config to the old slot.
gen = int(sys.argv[3]) if len(sys.argv) > 3 else 2
if gen == 1 and os.environ.get("WGTCP_SEAL_WITH_CERT") != "1":
    sys.exit("REFUSING: generation 1 is the CERT-SEALED slot. Sealing cert-less "
             "into it would silently freeze every pre-two-key install. Set "
             "WGTCP_SEAL_WITH_CERT=1 if that is really what you mean.")

halves = bytes.fromhex(os.environ["WGTCP_SYM_HALVES"])

# 🚨 THE CERTIFICATE IS NO LONGER MIXED IN, and it never could have worked.
#
# Play RE-SIGNS every app with its own key, so the fingerprint a client reads at
# runtime is not the one this script sealed with. Measured 2026-09-23:
#
#     publisher (upload key)  6432a955...
#     Play ships rostam       32ea7e35...
#     Play ships redvpn       21de9346...
#     Play ships dollarvpn    9c06b3ed...
#
# Not one matches. So NO Play install of ANY brand had ever opened an OTA
# config: each fetched, failed to unseal, and silently kept its bundled
# defaults. Only the direct-download APKs -- signed here with the upload key --
# ever worked, which is exactly backwards, because Play is where the installs
# are and the direct APK is the Iran channel.
#
# ⚠️ SEQUENCING -- and the v2 endpoint is what removed it. A cert-less config
# goes to the v2 SLOT, which only a client that asks /api/v2/config can reach.
# v1 keeps serving the cert-sealed config forever, so no old client can see
# this and none has to be waited for. Writing cert-less into a v1 slot is the
# thing that would freeze them, and `gen` refuses it.
#
# One more property worth knowing: because v2 is new-clients-only BY
# CONSTRUCTION, a config published here may safely use anything only the new
# core understands -- the region hostnames of 12b included, which could never
# be published to the shared slot because an old core dials a name in
# regions.*.ips early and pays a distinctive DNS lookup on the happy path.
#
# Set WGTCP_SEAL_WITH_CERT=1 to restore the old behaviour for a rollback.
cert = (bytes.fromhex(os.environ.get("WGTCP_CERT_SHA256", ""))
        if os.environ.get("WGTCP_SEAL_WITH_CERT") == "1" else b"")
seed   = open("/etc/wgtcp/config_signing.seed", "rb").read()
assert len(halves) >= 32, "sym halves must be >= 32 bytes"
assert len(seed) == 32, f"signing seed must be 32 bytes, got {len(seed)}"

# Minified: 25% of every fetch was indentation, paid by a metered censored link.
payload = json.dumps(json.load(open(src)), separators=(",", ":"), ensure_ascii=False)

# Sign FIRST, then encrypt. The symmetric key ships in the APK and will leak,
# so a forged ciphertext must still fail signature verification.
sig = ed25519.Ed25519PrivateKey.from_private_bytes(seed).sign(payload.encode())
plain = sig + payload.encode()

h = hashlib.blake2s(digest_size=32)
h.update(b"rostam-cfg-v1")
h.update(halves[:16]); h.update(halves[16:32])
if cert: h.update(cert)
key = h.digest()

nonce = secrets.token_bytes(12)
sealed = nonce + ChaCha20Poly1305(key).encrypt(nonce, plain, None)

import base64
suffix = ":v2" if gen >= 2 else ""
base_key = ("wgtcp:client_config:brand:%s" % brand) if brand != "shared" else "wgtcp:client_config"
print("BRAND=%s" % brand)
print("GEN=%d  (%s)" % (gen, "cert-less" if gen >= 2 else "cert-sealed"))
print("KEY_CONFIG=%s%s" % (base_key, suffix))
print("KEY_VERSION=%s:version%s" % (base_key, suffix))
print("KEY_PUBLISHED=%s:published_at%s" % (base_key, suffix))
print("SRC_BYTES=%d" % len(payload))
print("SEALED_B64_BYTES=%d" % len(base64.b64encode(sealed)))
open("/tmp/sealed_%s.b64" % brand, "w").write(base64.b64encode(sealed).decode())
print("WROTE=/tmp/sealed_%s.b64" % brand)
