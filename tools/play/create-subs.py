#!/usr/bin/env python3
"""Create a brand's monthly + yearly subscriptions on Google Play.

WHY A SCRIPT: a subscription needs a price in ~173 regions. Hand-writing those
is how a listing ends up with a sensible USD price and nonsense everywhere
else, so the local prices come from Play's own `pricing:convertRegionPrices`,
which is what Console uses when you type one price into the picker.

    tools/play/create-subs.py <package> <monthly_usd> <yearly_usd> [--apply]

Dry run by default. Creating a product is not something to discover by
accident: product ids are PERMANENT on a listing, exactly like versionCodes,
and a typo cannot be renamed away later.
"""
import sys, json, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from play_api import token, call

def money(usd: str):
    whole, _, frac = usd.partition(".")
    return {"currencyCode": "USD", "units": whole, "nanos": int((frac or "0").ljust(3, "0")[:3]) * 1_000_000}

def regional(tok, pkg, usd):
    """Regional prices AND the regions version they belong to.

    They must be returned together. Hardcoding "2022/02" while asking Google to
    convert today's prices fails on any country that has since changed
    currency: Bulgaria moved to the euro, so the conversion returns EUR and the
    old version insists on BGN. Taking the version from the same response makes
    that class of mismatch unrepresentable.
    """
    d = call(tok, f"/applications/{pkg}/pricing:convertRegionPrices", "POST", {"price": money(usd)})
    if "_error" in d:
        raise SystemExit(f"price conversion failed: {d['_error']} {d['_body'][:300]}")
    version = d.get("regionVersion", {}).get("version")
    out = []
    for region, v in d["convertedRegionPrices"].items():
        # newSubscriberAvailability defaults to false, which creates a product
        # nobody can buy. Say it explicitly.
        out.append({"regionCode": region, "newSubscriberAvailability": True, "price": v["price"]})
    return out, version

def make(tok, pkg, pid, base_id, period, usd, title, desc, apply):
    regions, version = regional(tok, pkg, usd)
    body = {
        "packageName": pkg,
        "productId": pid,
        "basePlans": [{
            "basePlanId": base_id,
            "state": "DRAFT",          # activated separately, deliberately
            "autoRenewingBasePlanType": {
                "billingPeriodDuration": period,
                # Play's own default. A shorter grace period means a failed card
                # cancels a real subscriber faster than their bank retries.
                "gracePeriodDuration": "P7D",
                "accountHoldDuration": "P30D",
                "resubscribeState": "RESUBSCRIBE_STATE_ACTIVE",
            },
            "regionalConfigs": regions,
        }],
        "listings": [{"languageCode": "en-US", "title": title, "description": desc}],
    }
    print(f"  {pid:22s} {period}  ${usd}  {len(regions)} regions  regionsVersion={version}")
    if not apply:
        return
    r = call(tok, f"/applications/{pkg}/subscriptions", "POST",
             body, params={"productId": pid, "regionsVersion.version": version})
    if "_error" in r:
        print(f"      FAILED {r['_error']}: {r['_body'][:400]}")
    else:
        print(f"      created, basePlan {r['basePlans'][0]['basePlanId']} state={r['basePlans'][0].get('state')}")

if __name__ == "__main__":
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    pkg, m_usd, y_usd = sys.argv[1], sys.argv[2], sys.argv[3]
    apply = "--apply" in sys.argv
    tok = token()
    brand = {"vpn.dollarvpn.app": "DollarVPN", "io.internet.pro": "Internet Pro",
             "esim.goglobe.app": "GoGlobe"}.get(pkg, pkg)
    make(tok, pkg, f"{brand.lower().replace(' ','')}_monthly", "monthly", "P1M", m_usd,
         f"{brand} Premium (monthly)",
         "Full speed, every region, no ads. Renews monthly, cancel any time.", apply)
    make(tok, pkg, f"{brand.lower().replace(' ','')}_yearly", "yearly", "P1Y", y_usd,
         f"{brand} Premium (yearly)",
         "Full speed, every region, no ads. Renews yearly, cancel any time.", apply)
    if not apply:
        print("\nDRY RUN. Nothing created. Re-run with --apply.")
