#!/usr/bin/env python3
"""Extend an existing base plan to every region Play offers.

WHY: RedVPN's live plans covered 43 regions while Rostam's covered 173, so the
app was simply unbuyable in 130 markets and nothing in the app or the backend
said so. The gap is invisible from the client: queryProducts returns the
product, it just has no price where the user is.

    tools/play/expand-regions.py <pkg> <productId> <basePlanId> <usd> [--apply]

EXISTING REGIONS ARE NEVER TOUCHED. Only missing ones are added, so a price a
human tuned by hand stays exactly as it is, and no existing subscriber sees a
price change. Prices for the new regions come from Play's own conversion of the
USD figure, and the regions version comes from that same response so the two
cannot disagree.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from play_api import token, call

def money(usd):
    w, _, f = usd.partition(".")
    return {"currencyCode": "USD", "units": w, "nanos": int((f or "0").ljust(3, "0")[:3]) * 1_000_000}

def main():
    if len(sys.argv) < 5:
        raise SystemExit(__doc__)
    pkg, pid, bpid, usd = sys.argv[1:5]
    apply = "--apply" in sys.argv
    tok = token()

    sub = call(tok, f"/applications/{pkg}/subscriptions/{pid}")
    if "_error" in sub:
        raise SystemExit(f"read failed: {sub['_error']} {sub['_body'][:200]}")
    plans = {b["basePlanId"]: b for b in sub["basePlans"]}
    if bpid not in plans:
        raise SystemExit(f"no base plan {bpid}; have {list(plans)}")
    bp = plans[bpid]
    have = {r["regionCode"]: r for r in bp.get("regionalConfigs", [])}

    conv = call(tok, f"/applications/{pkg}/pricing:convertRegionPrices", "POST", {"price": money(usd)})
    if "_error" in conv:
        raise SystemExit(f"conversion failed: {conv['_error']} {conv['_body'][:300]}")
    version = conv["regionVersion"]["version"]

    added = []
    for region, v in conv["convertedRegionPrices"].items():
        if region in have:
            continue
        have[region] = {"regionCode": region, "newSubscriberAvailability": True, "price": v["price"]}
        added.append(region)

    print(f"  {pid}/{bpid}: {len(have) - len(added)} kept, {len(added)} added -> {len(have)} regions")
    print(f"  regionsVersion {version}")
    if not apply:
        print("\nDRY RUN. Nothing changed. Re-run with --apply.")
        return

    bp["regionalConfigs"] = sorted(have.values(), key=lambda r: r["regionCode"])
    r = call(tok, f"/applications/{pkg}/subscriptions/{pid}/basePlans/{bpid}:migratePrices"
             if False else f"/applications/{pkg}/subscriptions/{pid}", "PATCH",
             {"packageName": pkg, "productId": pid, "basePlans": list(plans.values()),
              "listings": sub.get("listings", [])},
             params={"updateMask": "basePlans", "regionsVersion.version": version,
                     "latencyTolerance": "PRODUCT_UPDATE_LATENCY_TOLERANCE_LATENCY_SENSITIVE"})
    if "_error" in r:
        print(f"  FAILED {r['_error']}: {r['_body'][:500]}")
    else:
        n = {b["basePlanId"]: len(b.get("regionalConfigs", [])) for b in r["basePlans"]}
        print(f"  updated: {n}")

if __name__ == "__main__":
    main()
