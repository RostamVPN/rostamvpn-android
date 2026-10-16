#!/usr/bin/env python3
# Copyright 2026 Internet Mastering & Company, Inc.
# SPDX-License-Identifier: Apache-2.0
"""Set a base plan's price in every region it already covers.

    tools/play/set-price.py <pkg> <productId> <basePlanId> <usd> [--apply]

Dry run by default.

WHAT THIS DOES AND DOES NOT DO. It rewrites the price for regions the plan
ALREADY has, using Play's own conversion of the USD figure so each market gets
the local price Google recommends rather than a naive FX result. It does not
add or remove regions; use expand-regions.py for that.

EXISTING SUBSCRIBERS ARE NOT MIGRATED. A regional price change applies to NEW
purchases. Anyone already subscribed keeps the price they signed up at until
someone explicitly runs a price migration, which is a separate, notified, and
partly irreversible process. That is the safe default and it is why this script
does not offer to do it.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from play_api import token, call


def money(usd):
    w, _, f = usd.partition(".")
    return {"currencyCode": "USD", "units": w, "nanos": int((f or "0").ljust(3, "0")[:3]) * 1_000_000}


def show(p):
    return "%s %s.%02d" % (p.get("currencyCode", "?"), p.get("units", "0"), int(p.get("nanos", 0)) / 1e7)


def main():
    if len(sys.argv) < 5:
        raise SystemExit(__doc__)
    pkg, pid, bpid, usd = sys.argv[1:5]
    apply = "--apply" in sys.argv
    tok = token()
    sub = call(tok, f"/applications/{pkg}/subscriptions/{pid}")
    if "_error" in sub:
        raise SystemExit(f"read failed: {sub['_error']} {sub['_body'][:300]}")
    plans = {b["basePlanId"]: b for b in sub["basePlans"]}
    bp = plans.get(bpid)
    if bp is None:
        raise SystemExit(f"no base plan {bpid}; have {list(plans)}")
    conv = call(tok, f"/applications/{pkg}/pricing:convertRegionPrices", "POST", {"price": money(usd)})
    if "_error" in conv:
        raise SystemExit(f"conversion failed: {conv['_error']} {conv['_body'][:300]}")
    new = conv["convertedRegionPrices"]
    version = conv["regionVersion"]["version"]
    before = {r["regionCode"]: r.get("price", {}) for r in bp.get("regionalConfigs", [])}
    changed = 0
    for r in bp["regionalConfigs"]:
        n = new.get(r["regionCode"])
        if n and r.get("price") != n["price"]:
            r["price"] = n["price"]
            changed += 1
    us_before, us_after = before.get("US", {}), next(
        (r["price"] for r in bp["regionalConfigs"] if r["regionCode"] == "US"), {})
    print(f"  {pid}/{bpid}: {changed} of {len(bp['regionalConfigs'])} regions repriced")
    print(f"  US {show(us_before)} -> {show(us_after)}   regionsVersion {version}")
    print("  existing subscribers keep their current price; this affects NEW purchases")
    if not apply:
        print("\nDRY RUN. Nothing changed. Re-run with --apply.")
        return
    r = call(tok, f"/applications/{pkg}/subscriptions/{pid}", "PATCH",
             {"packageName": pkg, "productId": pid, "basePlans": list(plans.values()),
              "listings": sub.get("listings", [])},
             params={"updateMask": "basePlans", "regionsVersion.version": version,
                     "latencyTolerance": "PRODUCT_UPDATE_LATENCY_TOLERANCE_LATENCY_SENSITIVE"})
    if "_error" in r:
        print(f"  FAILED {r['_error']}: {r['_body'][:500]}")
    else:
        for b in r["basePlans"]:
            if b["basePlanId"] == bpid:
                us = next((x["price"] for x in b["regionalConfigs"] if x["regionCode"] == "US"), {})
                print(f"  updated: US now {show(us)}, {len(b['regionalConfigs'])} regions, state={b.get('state')}")


if __name__ == "__main__":
    main()
