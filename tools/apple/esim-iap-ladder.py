#!/usr/bin/env python3
"""e-sim.net plan prices -> RedVPN iOS "eSIM credit" consumables, and the ladder JSON.

Rule (owner, Small Business Program, 15% commission): for every web plan price,
the IAP price is the FIRST App Store USD in-app purchase price point
>= web_cents / 0.85. One consumable per distinct IAP price:

    productId   esim.credit.<iap cents, 5 digits>      e.g. esim.credit.00239
    reference   eSIM credit $2.39       type CONSUMABLE
    en-US       display name = reference name, description below
    price       USA base territory, manual price at that point, automatic elsewhere
    availability  every territory, available in new territories

Re-runnable. It re-fetches the web prices, re-reads the ladder from App Store
Connect, creates only what is missing and fixes only what differs, then
rewrites ~/silo/e-sim.net/server/data/apple_iap_ladder.json.

PERMANENT: a productId, once created, can never be used again, even after
deletion. Run --dry-run first. This never submits anything for review.

    python3 esim-iap-ladder.py --dry-run
    python3 esim-iap-ladder.py --limit 1      # create/verify the first product only
    python3 esim-iap-ladder.py                # all of them

Needs `cryptography` (the e-sim.net server venv has it).
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import pathlib
import sys
import time
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from asc import client, get, post, patch, paged, app_id  # noqa: E402

BUNDLE = "com.redvpn.ios"
APP = app_id(BUNDLE)  # 6448588504, account "red"
WEB = "https://e-sim.net/v1/destinations"
OUT = pathlib.Path("~/silo/e-sim.net/server/data/apple_iap_ladder.json").expanduser()
SHARE_BPS = 8500  # developer keeps 85%
RULE = "ceil(web/0.85) to next App Store USD price point"
REVIEW_NOTE = ("Pays for an eSIM data or number plan of the same value, "
               "provisioned by e-sim.net after purchase.")
# Apple caps an IAP description at 55 characters (409 TOO_LONG). The brief asked for
# "Credit for an eSIM plan of this value. Provisioned instantly in the app." (72).
DESCRIPTION = "Credit for an eSIM plan of this value."
PAUSE = 0.4  # seconds between write calls


def log(*a):
    print(*a, flush=True)


def call(fn, *a):
    """ASC call with backoff on 429 and 5xx. Returns the decoded body."""
    for attempt in range(6):
        r = fn(*a)
        e = r.get("_error") if isinstance(r, dict) else None
        if e in (429, 500, 502, 503, 504):
            time.sleep(5 * (attempt + 1))
            continue
        return r
    return r


def web_prices() -> list[int]:
    def j(u):
        req = urllib.request.Request(u, headers={"User-Agent": "esim-iap-ladder"})
        return json.load(urllib.request.urlopen(req, timeout=30))
    d = j(WEB)
    ids = [x["id"] for x in d["countries"] + d["regions"]]
    out = set()
    for i in ids:
        for p in j(f"{WEB}/{i}").get("plans", []):
            out.add(int(p["price_cents"]))
    return sorted(out)


def cents(price: str) -> int:
    return round(float(price) * 100)


def usd_points(c, iap_id: str) -> dict[int, str]:
    """USD price points for one IAP: {cents: price point id}."""
    r = paged(c, f"/v2/inAppPurchases/{iap_id}/pricePoints?filter[territory]=USA"
                 "&fields[inAppPurchasePricePoints]=customerPrice")
    if "_error" in r:
        raise SystemExit(f"price points for {iap_id}: {r}")
    return {cents(x["attributes"]["customerPrice"]): x["id"] for x in r["data"]}


def reference_ladder(c) -> list[int]:
    """Before any IAP exists the IAP ladder cannot be read, so the dry run
    uses the subscription USD ladder. Real runs re-check against the IAP one."""
    g = get(c, f"/v1/apps/{APP}/subscriptionGroups")["data"][0]["id"]
    s = get(c, f"/v1/subscriptionGroups/{g}/subscriptions")["data"][0]["id"]
    r = paged(c, f"/v1/subscriptions/{s}/pricePoints?filter[territory]=USA"
                 "&fields[subscriptionPricePoints]=customerPrice")
    return sorted({cents(x["attributes"]["customerPrice"]) for x in r["data"]})


def build_ladder(web: list[int], points: list[int]) -> dict[int, list[int]]:
    pts = sorted(points)
    m: dict[int, list[int]] = {}
    for w in web:
        p = next((p for p in pts if p * SHARE_BPS >= w * 10000), None)
        if p is None:
            raise SystemExit(f"no USD price point covers web {w}")
        m.setdefault(p, []).append(w)
    return dict(sorted(m.items()))


def pid(p: int) -> str:
    return f"esim.credit.{p:05d}"


def label(p: int) -> str:
    return f"eSIM credit ${p // 100}.{p % 100:02d}"


def existing(c) -> dict[str, dict]:
    r = paged(c, f"/v1/apps/{APP}/inAppPurchasesV2"
                 "?fields[inAppPurchases]=productId,name,inAppPurchaseType,state,reviewNote")
    if "_error" in r:
        raise SystemExit(f"list IAPs: {r}")
    return {x["attributes"]["productId"]: x for x in r["data"]}


def territories(c) -> list[str]:
    r = paged(c, "/v1/territories?fields[territories]=currency")
    return [t["id"] for t in r["data"]]


def ensure_product(c, p: int, have: dict) -> str:
    want = {"name": label(p), "reviewNote": REVIEW_NOTE}
    x = have.get(pid(p))
    if x is None:
        r = call(post, c, "/v2/inAppPurchases", {"data": {
            "type": "inAppPurchases",
            "attributes": {"productId": pid(p), "inAppPurchaseType": "CONSUMABLE",
                           "familySharable": False, **want},
            "relationships": {"app": {"data": {"type": "apps", "id": APP}}}}})
        if "_error" in r:
            raise RuntimeError(f"create {pid(p)}: {r}")
        time.sleep(PAUSE)
        log(f"  created {pid(p)} id={r['data']['id']}")
        return r["data"]["id"]
    a = x["attributes"]
    if a.get("inAppPurchaseType") != "CONSUMABLE":
        raise RuntimeError(f"{pid(p)} exists as {a.get('inAppPurchaseType')}; cannot change type")
    diff = {k: v for k, v in want.items() if a.get(k) != v}
    if diff:
        r = call(patch, c, f"/v2/inAppPurchases/{x['id']}",
                 {"data": {"type": "inAppPurchases", "id": x["id"], "attributes": diff}})
        if "_error" in r:
            raise RuntimeError(f"patch {pid(p)}: {r}")
        time.sleep(PAUSE)
        log(f"  fixed {sorted(diff)} on {pid(p)}")
    return x["id"]


def ensure_localization(c, iap: str, p: int):
    want = {"name": label(p), "description": DESCRIPTION}
    r = call(get, c, f"/v2/inAppPurchases/{iap}/inAppPurchaseLocalizations")
    loc = next((l for l in r.get("data", []) if l["attributes"]["locale"] == "en-US"), None)
    if loc is None:
        r = call(post, c, "/v1/inAppPurchaseLocalizations", {"data": {
            "type": "inAppPurchaseLocalizations",
            "attributes": {"locale": "en-US", **want},
            "relationships": {"inAppPurchaseV2": {"data": {"type": "inAppPurchases", "id": iap}}}}})
        if "_error" in r:
            raise RuntimeError(f"localization {pid(p)}: {r}")
        time.sleep(PAUSE)
        return
    diff = {k: v for k, v in want.items() if loc["attributes"].get(k) != v}
    if diff:
        r = call(patch, c, f"/v1/inAppPurchaseLocalizations/{loc['id']}", {"data": {
            "type": "inAppPurchaseLocalizations", "id": loc["id"], "attributes": diff}})
        if "_error" in r:
            raise RuntimeError(f"patch localization {pid(p)}: {r}")
        time.sleep(PAUSE)


def current_usd_price(c, iap: str):
    """(base territory, USD cents of the current manual USA price) or (None, None)."""
    s = call(get, c, f"/v2/inAppPurchases/{iap}/iapPriceSchedule?include=baseTerritory")
    if "_error" in s:
        return None, None
    base = ((s["data"].get("relationships", {}).get("baseTerritory") or {}).get("data") or {}).get("id")
    sid = s["data"]["id"]
    r = call(get, c, f"/v1/inAppPurchasePriceSchedules/{sid}/manualPrices"
                     "?filter[territory]=USA&include=inAppPurchasePricePoint"
                     "&fields[inAppPurchasePricePoints]=customerPrice")
    pts = [i for i in r.get("included", []) if i["type"] == "inAppPurchasePricePoints"]
    return base, (cents(pts[0]["attributes"]["customerPrice"]) if pts else None)


def ensure_price(c, iap: str, p: int, point_id: str):
    base, now = current_usd_price(c, iap)
    if base == "USA" and now == p:
        return
    r = call(post, c, "/v1/inAppPurchasePriceSchedules", {
        "data": {"type": "inAppPurchasePriceSchedules", "relationships": {
            "inAppPurchase": {"data": {"type": "inAppPurchases", "id": iap}},
            "baseTerritory": {"data": {"type": "territories", "id": "USA"}},
            "manualPrices": {"data": [{"type": "inAppPurchasePrices", "id": "${usa}"}]}}},
        "included": [{"type": "inAppPurchasePrices", "id": "${usa}",
                      "attributes": {"startDate": None},
                      "relationships": {"inAppPurchasePricePoint": {
                          "data": {"type": "inAppPurchasePricePoints", "id": point_id}}}}]})
    if "_error" in r:
        raise RuntimeError(f"price {pid(p)}: {r}")
    time.sleep(PAUSE)
    log(f"  price {pid(p)}: {now} -> {p} (base {base} -> USA)")


def ensure_availability(c, iap: str, p: int, terr: list[str]):
    r = call(get, c, f"/v2/inAppPurchases/{iap}/inAppPurchaseAvailability")
    if "_error" not in r and r.get("data"):
        aid = r["data"]["id"]
        newt = r["data"]["attributes"].get("availableInNewTerritories")
        t = paged(c, f"/v1/inAppPurchaseAvailabilities/{aid}/availableTerritories")
        if newt and {x["id"] for x in t.get("data", [])} >= set(terr):
            return
    r = call(post, c, "/v1/inAppPurchaseAvailabilities", {"data": {
        "type": "inAppPurchaseAvailabilities",
        "attributes": {"availableInNewTerritories": True},
        "relationships": {
            "inAppPurchase": {"data": {"type": "inAppPurchases", "id": iap}},
            "availableTerritories": {"data": [{"type": "territories", "id": t} for t in terr]}}}})
    if "_error" in r:
        raise RuntimeError(f"availability {pid(p)}: {r}")
    time.sleep(PAUSE)


def write_json(ladder: dict[int, list[int]]):
    OUT.parent.mkdir(parents=True, exist_ok=True)
    doc = {"generated_at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
           "rule": RULE,
           "products": [{"product_id": pid(p), "iap_cents": p, "web_cents": ws}
                        for p, ws in ladder.items()]}
    OUT.write_text(json.dumps(doc, indent=2) + "\n")
    log(f"wrote {OUT} ({len(ladder)} products)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--limit", type=int, default=0, help="only the first N products")
    args = ap.parse_args()

    c = client(app=BUNDLE)
    web = web_prices()
    log(f"web: {len(web)} distinct prices, {web[0]}..{web[-1]}")
    have = existing(c)
    anchor = next((x["id"] for k, x in have.items() if k.startswith("esim.credit.")), None)
    if anchor:
        points = usd_points(c, anchor)
        ladder = build_ladder(web, list(points))
        log(f"IAP USD ladder: {len(points)} points (read from {anchor})")
    else:
        ref = reference_ladder(c)
        ladder = build_ladder(web, ref)
        log(f"no IAP yet; planning against the subscription USD ladder ({len(ref)} points)")
    log(f"{len(ladder)} products")

    if args.dry_run:
        for p, ws in ladder.items():
            log(f"  {pid(p)}  {label(p):22s} <- web {ws}  {'exists' if pid(p) in have else 'NEW'}")
        return

    terr = territories(c)
    log(f"{len(terr)} territories")
    todo = list(ladder.items())[: args.limit or None]
    points = None
    errors = []
    for p, ws in todo:
        log(f"{pid(p)} <- {ws}")
        try:
            iap = ensure_product(c, p, have)
            if points is None:
                points = usd_points(c, iap)
                fresh = build_ladder(web, list(points))
                if fresh != ladder:
                    raise SystemExit("IAP USD ladder differs from the planning ladder; "
                                     "stopping before creating more ids. Re-run.")
            # Price point ids are per IAP, so read this product's own.
            mine = usd_points(c, iap) if iap != anchor else points
            if p not in mine:
                raise RuntimeError(f"{p} is not a USD price point for {pid(p)}")
            ensure_localization(c, iap, p)
            ensure_price(c, iap, p, mine[p])
            ensure_availability(c, iap, p, terr)
        except RuntimeError as e:
            errors.append(str(e))
            log(f"  ERROR {e}")

    have = existing(c)
    states = {}
    for p in ladder:
        s = have.get(pid(p), {}).get("attributes", {}).get("state", "ABSENT")
        states[s] = states.get(s, 0) + 1
    log(f"states: {states}")
    if errors:
        log(f"{len(errors)} errors:")
        for e in errors:
            log("  " + e[:600])
    write_json(ladder)


if __name__ == "__main__":
    main()
