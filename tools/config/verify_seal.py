# Copyright 2026 Internet Mastering & Company, Inc.
# SPDX-License-Identifier: Apache-2.0
"""Reverse the seal and check the signature. Decrypt THEN verify, which is the
only order consistent with sign-then-encrypt."""
import base64, hashlib, json, os, sys
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.asymmetric import ed25519
brand = sys.argv[1]
blob = base64.b64decode(open(f"/tmp/sealed_{brand}.b64").read())
halves = bytes.fromhex(os.environ["WGTCP_SYM_HALVES"])
cert   = bytes.fromhex(os.environ.get("WGTCP_CERT_SHA256",""))
seed   = open("/etc/wgtcp/config_signing.seed","rb").read()
h=hashlib.blake2s(digest_size=32); h.update(b"rostam-cfg-v1")
h.update(halves[:16]); h.update(halves[16:32])
if cert: h.update(cert)
plain = ChaCha20Poly1305(h.digest()).decrypt(blob[:12], blob[12:], None)
print("  decrypt: OK")
sig, body = plain[:64], plain[64:]
pub = ed25519.Ed25519PrivateKey.from_private_bytes(seed).public_key()
pub.verify(sig, body)
print("  signature: VALID")
cfg = json.loads(body)
print("  version          :", cfg.get("version"))
print("  ads.enabled      :", cfg["ads"]["enabled"])
print("  freedom_tier     :", cfg["freedom_tier"])
print("  hard_stop        :", cfg["throttle"]["hard_stop"])
print("  region labels    :", [v.get("label") for v in cfg.get("regions",{}).values()])
print("  entries          :", len(cfg["transport"]["redsocket"]["entries"]))
