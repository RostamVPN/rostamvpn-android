"""App Store Connect API client. Reads .p8 keys from disk; never prints one.

⚠️ THERE ARE TWO ACCOUNTS AND A KEY ONLY EVER SEES ITS OWN. `/v1/apps` returns
the other account's apps with NO error — it reads exactly like "that app does not
exist" rather than "wrong account". That has cost real time, so this module
resolves the account FOR you: pass a bundle id or an app id and it picks the key.

    from asc import client, get, post, patch
    c = client(app="com.rostamvpn")          # or app="1487875612", or account="rostam"
    apps = get(c, "/v1/apps?limit=200")

Accounts:
    rostam  issuer 901067d3-…  key 9KJP7745B5  RostamVPN, DollarVPN
    red     issuer 1e5ef0d1-…  key 656H9V8A2N (7D7QM6W397 revoked 2026-10-23)  RedVPN, Internet Pro, eSIM .net,
                                               Hush Browser, Calculator
"""
from __future__ import annotations

import base64
import json
import pathlib
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Optional

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils

BASE = "https://api.appstoreconnect.apple.com"

ACCOUNTS = {
    "rostam": {
        "issuer": "901067d3-7bd6-4a9c-8020-b0d4e8b4127c",
        "kid": "9KJP7745B5",
        "key": "~/config/apple-store/AuthKey_9KJP7745B5.p8",
        "apps": {
            "com.rostamvpn": "1487875612",
            "net.dollarvpn.ios.vpn.unlimited.super.proxy.app": "6743068493",
        },
    },
    "red": {
        "issuer": "1e5ef0d1-74fa-443f-abb0-f778364fdaa9",
        "kid": "656H9V8A2N",
        "key": "~/silo/apple-store/AuthKey_656H9V8A2N.p8",
        "apps": {
            "com.redvpn.ios": "6448588504",
            "app.internet.pro": "6787171213",
            "net.e-sim.global": "6753609137",
            "com.hushbrowser.proxy.vpn.tunnel.free": "6753610557",
            "com.calculator.app.ios": "6806853185",
        },
    },
}

# The team each account signs under. Needed for provisioning, not for the API.
TEAMS = {"rostam": "LR3WWLG3FQ", "red": "8Y6Q4B7AN5"}


def _b64(d: bytes) -> bytes:
    return base64.urlsafe_b64encode(d).rstrip(b"=")


def account_for(app: str) -> str:
    """Which account owns this bundle id or numeric app id."""
    for name, a in ACCOUNTS.items():
        if app in a["apps"] or app in a["apps"].values():
            return name
    raise SystemExit(
        f"unknown app {app!r}. Known: "
        + ", ".join(sorted(k for a in ACCOUNTS.values() for k in a["apps"]))
    )


def app_id(app: str) -> str:
    """Numeric ASC id for a bundle id (or the id itself if already numeric)."""
    if app.isdigit():
        return app
    return ACCOUNTS[account_for(app)]["apps"][app]


def client(app: Optional[str] = None, account: Optional[str] = None) -> dict:
    """A signed token plus the context it belongs to. Give `app` OR `account`."""
    name = account or (account_for(app) if app else None)
    if name not in ACCOUNTS:
        raise SystemExit(f"pass app= or account= (one of {list(ACCOUNTS)})")
    a = ACCOUNTS[name]
    pem = pathlib.Path(a["key"]).expanduser().read_bytes()
    key = serialization.load_pem_private_key(pem, password=None)
    now = int(time.time())
    hdr = _b64(json.dumps({"alg": "ES256", "kid": a["kid"], "typ": "JWT"}).encode())
    pay = _b64(json.dumps({"iss": a["issuer"], "iat": now, "exp": now + 1100,
                           "aud": "appstoreconnect-v1"}).encode())
    der = key.sign(hdr + b"." + pay, ec.ECDSA(hashes.SHA256()))
    r, s = utils.decode_dss_signature(der)
    # ASC wants the raw r||s pair, not DER. Getting this wrong yields a 401 that
    # looks exactly like a bad key.
    sig = _b64(r.to_bytes(32, "big") + s.to_bytes(32, "big"))
    return {"token": (hdr + b"." + pay + b"." + sig).decode(),
            "account": name, "team": TEAMS[name]}


def _call(c: dict, path: str, method: str = "GET", body: Any = None) -> Any:
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Authorization": "Bearer " + c["token"]}
    if data:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            t = r.read().decode()
            return json.loads(t) if t.strip() else {}
    except urllib.error.HTTPError as e:
        return {"_error": e.code, "_body": e.read().decode()[:1500]}


def get(c, path):            return _call(c, path)
def post(c, path, body):     return _call(c, path, "POST", body)
def patch(c, path, body):    return _call(c, path, "PATCH", body)
def delete(c, path):         return _call(c, path, "DELETE")


def paged(c: dict, path: str, limit: int = 200):
    """Follow `links.next` so a caller never silently sees only the first page."""
    out, url = [], path
    while url:
        sep = "&" if "?" in url else "?"
        r = _call(c, f"{url}{sep}limit={limit}" if "limit=" not in url else url)
        if "_error" in r:
            return r
        out += r.get("data", [])
        nxt = (r.get("links") or {}).get("next")
        url = nxt[len(BASE):] if nxt and nxt.startswith(BASE) else None
    return {"data": out}


if __name__ == "__main__":
    import sys
    if len(sys.argv) < 2:
        print(__doc__)
        print("\nusage: asc.py <app-or-account> [path]")
        print("       asc.py com.rostamvpn /v1/apps")
        raise SystemExit(1)
    who = sys.argv[1]
    c = client(app=who) if who not in ACCOUNTS else client(account=who)
    path = sys.argv[2] if len(sys.argv) > 2 else "/v1/apps?limit=200&fields[apps]=name,bundleId"
    print(json.dumps(_call(c, path), indent=2)[:4000])
