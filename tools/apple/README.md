# App Store Connect API

    python3 -c "import sys;sys.path.insert(0,'tools/apple');from asc import *; \
      tok=token('7D7QM6W397','~/silo/apple-store/AuthKey_7D7QM6W397.p8'); \
      print(get(tok,'/v1/apps'))"

Reads the `.p8` from disk and never prints it. The Issuer ID lives in
`~/.config/asc/issuer_id` (mode 600) — it is an identifier, not a secret, but it
is account state and does not belong in the repo.

Three keys exist on this machine; **only `7D7QM6W397` works**. `2UNA7UH2T2`
returns 401, because a key is bound to the team that issued it and only one of
these belongs to the team behind this Issuer ID.

Two details that cost time if unknown:

* ASC wants the ECDSA signature as raw `r||s`, not DER. `cryptography` returns
  DER, so it must be decoded and re-packed — a DER signature yields a bare 401
  that looks exactly like a wrong key.
* `exp` must be under 20 minutes or the token is rejected outright.

## What this account contains (read 2026-08-27)

    RedVPN: Fast Unlimited VPN   com.redvpn.ios
    Internet Pro                 app.internet.pro
    eSIM .net                    net.e-sim.global
    Hush Browser                 com.hushbrowser.proxy.vpn.tunnel.free

    com.redvpn.ios  redvpn.monthly  ONE_MONTH  APPROVED
    com.redvpn.ios  redvpn.yearly   ONE_YEAR   APPROVED

No other app has a subscription group.

**There is no RostamVPN record in this account.** Its iOS project declares
DEVELOPMENT_TEAM = LR3WWLG3FQ (Red Team LTD) while these apps belong to the other
team, so RostamVPN iOS is either unpublished or lives in a separate ASC account
with its own Issuer ID.


## Two accounts, two keys — this is not optional knowledge

The apps are split across TWO App Store Connect accounts, and a key only ever
sees its own:

| Issuer | Key | Apps |
|---|---|---|
| `1e5ef0d1-74fa-443f-abb0-f778364fdaa9` | `7D7QM6W397` | RedVPN, Internet Pro, eSIM .net, Hush Browser |
| `901067d3-7bd6-4a9c-8020-b0d4e8b4127c` | `9KJP7745B5` (`~/config/apple-store/`) | **RostamVPN**, **DollarVPN** |

`/v1/apps` under the first key returns four apps and no error, so it reads
exactly like "RostamVPN does not exist" rather than "you are looking at the
wrong account". `2UNA7UH2T2` 401s against both issuers and belongs to neither.

    RostamVPN   com.rostamvpn                                     1487875612
    DollarVPN   net.dollarvpn.ios.vpn.unlimited.super.proxy.app   6743068493

Note DollarVPN's iOS bundle is NOT `vpn.dollarvpn.app` — that is the Android
applicationId. They are different identifiers for the same brand.

## App Store Server Notifications (2026-08-27)

All six apps, both accounts, production and sandbox, now point at the cashier:

    https://cashier.example.com/webhooks/apple-assn   V2

RostamVPN and RedVPN previously pointed at `api.example.net`. The cashier is now
the single authority for Apple subscription lifecycle across every brand.

⚠️ Existing ixroute-era subscribers are NOT migrated. The handler acks a
notification whose `originalTransactionId` matches no cid and does NOT downgrade
anyone, so the switch cannot strip entitlement from a paying user — but until
those users re-verify through `/iap/verify`, the cashier does not know they are
paid. That linkage is stored only at purchase time.
