# Copyright 2026 Internet Mastering & Company, Inc.
# SPDX-License-Identifier: Apache-2.0
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
gen = int(sys.argv[3]) if len(sys.argv) > 3 else 2
if gen == 1 and os.environ.get("WGTCP_SEAL_WITH_CERT") != "1":
    sys.exit("REFUSING: generation 1 is the CERT-SEALED slot. Sealing cert-less "
             "into it would silently freeze every pre-two-key install. Set "
             "WGTCP_SEAL_WITH_CERT=1 if that is really what you mean.")
halves = bytes.fromhex(os.environ["WGTCP_SYM_HALVES"])
cert = (bytes.fromhex(os.environ.get("WGTCP_CERT_SHA256", ""))
        if os.environ.get("WGTCP_SEAL_WITH_CERT") == "1" else b"")
seed   = open("/etc/wgtcp/config_signing.seed", "rb").read()
assert len(halves) >= 32, "sym halves must be >= 32 bytes"
assert len(seed) == 32, f"signing seed must be 32 bytes, got {len(seed)}"
payload = json.dumps(json.load(open(src)), separators=(",", ":"), ensure_ascii=False)
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
