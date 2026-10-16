#!/usr/bin/env python3
# Copyright 2026 Internet Mastering & Company, Inc.
# SPDX-License-Identifier: Apache-2.0
"""Minimal Google Play Developer API client.

Auth is a Play Console service-account JSON; set SA below.

Subscriptions live on the `monetization` API, NOT `inappproducts`, which
answers 403 "Please migrate to the new publishing API". There is no
"list all apps" endpoint; everything is per package name.
"""
import base64, json, time, urllib.parse, urllib.request
SA = "REPLACE_WITH_PATH_TO_SERVICE_ACCOUNT_JSON"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3"


def token(scope="https://www.googleapis.com/auth/androidpublisher"):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    sa = json.load(open(SA))
    b64 = lambda d: base64.urlsafe_b64encode(d).rstrip(b"=")
    now = int(time.time())
    hdr = b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    pay = b64(json.dumps({"iss": sa["client_email"], "scope": scope,
                          "aud": "https://oauth2.googleapis.com/token",
                          "iat": now, "exp": now + 3600}).encode())
    key = serialization.load_pem_private_key(sa["private_key"].encode(), None)
    sig = b64(key.sign(hdr + b"." + pay, padding.PKCS1v15(), hashes.SHA256()))
    jwt = (hdr + b"." + pay + b"." + sig).decode()
    body = urllib.parse.urlencode(
        {"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": jwt}).encode()
    return json.load(urllib.request.urlopen("https://oauth2.googleapis.com/token", body))["access_token"]


def call(tok, path, method="GET", body=None, params=None):
    url = f"{API}{path}" + ("?" + urllib.parse.urlencode(params) if params else "")
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": "Bearer " + tok,
        **({"Content-Type": "application/json"} if data else {})})
    try:
        with urllib.request.urlopen(r) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        return {"_error": e.code, "_body": e.read().decode()[:600]}
