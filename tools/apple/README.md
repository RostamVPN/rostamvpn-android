# App Store Connect API

    python3 -c "import sys;sys.path.insert(0,'tools/apple');from asc import *; \
      c=client(account='rostam'); print(get(c,'/v1/apps'))"

`asc.py` reads the `.p8` key from disk and never prints it. Fill in the
`ACCOUNTS` table (issuer id, key id, path to the `.p8`) before use; a key is
bound to the team that issued it and only ever sees that team's apps.

Two details that cost time if unknown:

* ASC wants the ECDSA signature as raw `r||s`, not DER. `cryptography` returns
  DER, so it must be decoded and re-packed; a DER signature yields a bare 401
  that looks exactly like a wrong key.
* `exp` must be under 20 minutes or the token is rejected outright.

App Store Server Notifications point at the cashier's
`/webhooks/apple-assn` (V2). Subscribers from before that switch are not
migrated automatically: the handler acks a notification whose
`originalTransactionId` matches no account and never downgrades anyone, so the
switch cannot strip entitlement from a paying user, but until those users
re-verify through `/iap/verify` the cashier does not know they are paid.
