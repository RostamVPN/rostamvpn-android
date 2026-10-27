#!/usr/bin/env python3
"""Restrict a base plan to a named set of regions, without deleting prices.

    tools/play/set-availability.py <pkg> <productId> <basePlanId> <listName> [--apply]

Sets `newSubscriberAvailability` true for regions in the list and false for
every other region the plan carries. The price rows STAY, so re-opening a
market later is a one-line change rather than re-deriving 173 prices.

Existing subscribers in a closed region are NOT cancelled. They keep renewing.
Only NEW purchases are blocked, which is what "stop selling here" means.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from play_api import token, call

# ── The value-brand market list ─────────────────────────────────────────────
#
# DollarVPN sells at $0.99/mo against $3.99 for RostamVPN and RedVPN. Selling it
# anywhere that comfortably pays $3.99 does not win a new customer, it converts a
# $3.99 customer into a $0.99 one. So the list is built on two tests, both of
# which a market must pass.
#
#   1. BIG. Roughly 15 million people or more. Population is in the comment for
#      every entry so the rule can be audited instead of trusted.
#   2. NOT ALREADY ABLE TO PAY $3.99. That cuts the US, Canada, all of western
#      and northern Europe, the UK, Japan, Korea, Australia, New Zealand,
#      Singapore, Taiwan and Israel. It also cuts the wealthy Gulf: the UAE,
#      Kuwait, Qatar, Bahrain and Oman are small AND rich, so they fail both
#      tests. Saudi Arabia is the deliberate exception, kept for its size and
#      its very large VPN market.
#
# Iran, Russia, Cuba and Syria are absent for a different reason. They are FREE
# by policy, there is no purchase UI there at all, and Play does not serve the
# sanctioned ones regardless.
VALUE_MARKETS = {
    # South Asia
    "IN": "India 1.4B", "PK": "Pakistan 240M", "BD": "Bangladesh 170M",
    "NP": "Nepal 30M", "LK": "Sri Lanka 22M",
    # Southeast Asia
    "ID": "Indonesia 280M", "PH": "Philippines 115M", "VN": "Vietnam 100M",
    "TH": "Thailand 70M", "MM": "Myanmar 55M", "MY": "Malaysia 34M",
    "KH": "Cambodia 17M",
    # Middle East and North Africa
    "EG": "Egypt 110M", "TR": "Turkiye 85M", "DZ": "Algeria 45M",
    "IQ": "Iraq 44M", "MA": "Morocco 37M", "YE": "Yemen 34M",
    "SA": "Saudi Arabia 33M",
    # Sub-Saharan Africa
    "NG": "Nigeria 220M", "ET": "Ethiopia 120M", "CD": "DR Congo 100M",
    "TZ": "Tanzania 65M", "ZA": "South Africa 60M", "KE": "Kenya 55M",
    "UG": "Uganda 48M", "SD": "Sudan 48M", "AO": "Angola 35M",
    "GH": "Ghana 33M", "MZ": "Mozambique 33M", "MG": "Madagascar 30M",
    "CI": "Cote d'Ivoire 28M", "CM": "Cameroon 28M", "NE": "Niger 26M",
    "ML": "Mali 23M", "BF": "Burkina Faso 23M", "MW": "Malawi 21M",
    "ZM": "Zambia 20M", "SN": "Senegal 18M", "ZW": "Zimbabwe 16M",
    # Latin America
    "BR": "Brazil 215M", "MX": "Mexico 130M", "CO": "Colombia 52M",
    "AR": "Argentina 46M", "PE": "Peru 34M", "VE": "Venezuela 28M",
    "EC": "Ecuador 18M", "GT": "Guatemala 18M",
    # Eurasia
    "UA": "Ukraine 38M", "UZ": "Uzbekistan 35M", "KZ": "Kazakhstan 20M",
}
LISTS = {"value": VALUE_MARKETS, "all": None}  # None = every region the plan carries

def main():
    if len(sys.argv) < 5:
        raise SystemExit(__doc__)
    pkg, pid, bpid, listname = sys.argv[1:5]
    apply = "--apply" in sys.argv
    if listname not in LISTS:
        raise SystemExit(f"unknown list {listname}; have {list(LISTS)}")
    markets = LISTS[listname]
    tok = token()

    sub = call(tok, f"/applications/{pkg}/subscriptions/{pid}")
    if "_error" in sub:
        raise SystemExit(f"read failed: {sub['_error']} {sub['_body'][:300]}")
    plans = {b["basePlanId"]: b for b in sub["basePlans"]}
    bp = plans.get(bpid)
    if bp is None:
        raise SystemExit(f"no base plan {bpid}; have {list(plans)}")

    on, off, missing = [], [], []
    have = {r["regionCode"] for r in bp["regionalConfigs"]}
    for r in bp["regionalConfigs"]:
        want = True if markets is None else r["regionCode"] in markets
        r["newSubscriberAvailability"] = want
        (on if want else off).append(r["regionCode"])
    missing = [] if markets is None else sorted(set(markets) - have)

    print(f"  {pid}/{bpid}")
    print(f"  SELLING in  {len(on)}: {', '.join(sorted(on))}")
    print(f"  closed in   {len(off)} other regions (prices kept, re-openable)")
    if missing:
        print(f"  ⚠️ not offered by Play at all: {', '.join(missing)}")
    if not apply:
        print("\nDRY RUN. Nothing changed. Re-run with --apply.")
        return

    r = call(tok, f"/applications/{pkg}/subscriptions/{pid}", "PATCH",
             {"packageName": pkg, "productId": pid, "basePlans": list(plans.values()),
              "listings": sub.get("listings", [])},
             params={"updateMask": "basePlans", "regionsVersion.version": "2025/03",
                     "latencyTolerance": "PRODUCT_UPDATE_LATENCY_TOLERANCE_LATENCY_SENSITIVE"})
    if "_error" in r:
        print(f"  FAILED {r['_error']}: {r['_body'][:500]}")
    else:
        for b in r["basePlans"]:
            if b["basePlanId"] == bpid:
                live = sum(1 for x in b["regionalConfigs"] if x.get("newSubscriberAvailability"))
                print(f"  updated: selling in {live} of {len(b['regionalConfigs'])} regions")

if __name__ == "__main__":
    main()
