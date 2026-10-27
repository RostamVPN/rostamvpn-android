# App Store listing — RostamVPN, en-US (primary)

App `1487875612`, bundle `com.rostamvpn`, `rostam` account.

🚨 **The live description is the worst §0 leak on either store.** It is not a
screenshot problem, it is the text, in the primary locale, on a page that has
been public for months:

> • State-of-the-art cryptography: ChaCha20, Poly1305, BLAKE2
> • Perfect forward secrecy via Noise protocol framework
> • Edge servers worldwide for low-latency connections
> • Multiple connection protocols for optimal performance
> • Automatic server selection for the best experience

Those five lines name the exact primitives of the handshake the app actually
uses. A censor reading the listing learns what to fingerprint without touching
a single packet. Everything in that block is gone from the copy below and
nothing replaces it: **the listing is not the place to explain how the tunnel
works, and there is no safe way to say it.**

Also removed, and why:

- **"No activity logs, no tracking, no data collection"** — the binary links
  GoogleMobileAds with Meta Audience Network mediation, reports cumulative
  usage to the backend, and carries a persistent install id. "No data
  collection" contradicts the binary and the App Privacy label at the same
  time, which is the exact pattern that has already caused trouble on Play.
- **"Zero-knowledge architecture"** — an unverifiable absolute.
- **"Built by US-based cybersecurity professionals" / "Made in USA"** — an
  attribution most of this app's users would rather the listing did not make
  on their behalf. Owner's call; it is simply absent below rather than
  argued about.

Voice: **"Your Digital Guardian." Free to start.**

## Name
<!-- max 30 -->
```
RostamVPN: Fast Free VPN
```

## Subtitle
<!-- max 30. ⚠️ This spends the subtitle on the brand line and carries no
     indexed term. Apple indexes name + subtitle + keywords together, and of
     the twenty top-ranking VPN apps surveyed, every single one uses the
     subtitle for keywords and none for a tagline (median length 28 of 30).
     This is the owner's line, so it stays unless he says otherwise; the
     alternative that keeps it and buys an indexed term is:
         Your digital guardian. Free.     (28)
     Decide deliberately rather than by default. -->
```
Your digital guardian
```

## Promotional text
<!-- max 170. Editable on the LIVE listing with no review and no new build.
     This is the only field that can be changed today, so it is the one place
     to correct the impression the current description leaves. -->
```
Free to start. Install it, tap once, and you're on. No account, no email, nothing to set up, nothing to understand.
```

## Keywords
<!-- max 100, no spaces after commas. Nothing repeats a word from the name or
     subtitle. The live field spends characters on "vpn" and "fast", both of
     which are already in the app name. -->
```
proxy,secure,privacy,wifi,anonymous,encryption,protect,safe,browsing,hotspot,network,connection
```

## Description
<!-- max 4000 -->
```
Your digital guardian.

Install it, tap once, and you're on the open internet. No account to make, no email to give, nothing to configure.

ONE TAP
Tap connect. RostamVPN finds a route that works on the network you are actually on, and holds it. If it drops, it comes back on its own. You do not have to choose anything or understand anything.

BUILT FOR DIFFICULT NETWORKS
Some networks block, filter and throttle. This app was built for exactly those. When one route stops working it looks for another by itself, without asking you.

FREE TO START
Every install starts free, at full speed, with an allowance you can see at any time on the usage screen. It renews every month.

When it runs out you are not disconnected and not locked out. The connection keeps working at a lower speed for as long as you want it. Premium has no cap and never slows down.

In some regions the app is free in full, paid for by donations. No payment screen appears there, nothing is metered, and every region is open. The app works that out on its own. You do not have to ask for it or prove anything to anyone.

NOTHING TO HAND OVER
No account. No email. No phone number. No name. We do not ask for any of it, so there is nothing of that kind for us to know, hand over or lose. Deleting the app deletes everything.

WHAT'S IN IT
- Connect in one tap, with the region chosen for you
- Or choose a region yourself and change it whenever you like
- Works on iPhone and iPad
- Reconnects by itself when the network changes or drops
- Usage stats, so you always know what you have used
- Dark and light
- Restore Premium on a new phone with an account code, without signing in

ABOUT THE CONNECTION
RostamVPN uses the iOS VPN system to carry your device's traffic over an encrypted connection, so the network you are attached to cannot read it or filter it. iOS asks your permission the first time, and it only runs while you leave it on. Turn it off and your traffic goes straight back to the normal network.

There is also a setting that lets your device help other people find a working connection. It is in Settings and you can turn it off whenever you want.

PREMIUM
Premium removes the speed limit and opens every region.

- Monthly: US$3.99 per month
- Yearly: US$39.99 per year

Prices are shown in your own currency inside the app and vary by country. In regions where the app is already free, Premium is never offered at all.

Premium is an auto-renewing subscription. Payment is charged to your Apple Account when you confirm the purchase. It renews automatically unless you turn auto-renew off at least 24 hours before the current period ends, and your account is charged for the renewal within the 24 hours before that. Manage or cancel it any time in Settings, under your name, in Subscriptions.

Terms of Use: https://www.apple.com/legal/internet-services/itunes/dev/stdeula/
Privacy Policy: https://www.rostam.app/en/privacy/

rostam.app
feedback@rostam.app
```

## What's New
<!-- max 4000. ⚠️ DRAFT. These bullets are written from recent iOS commits and from the bugs this audit found, NOT from a real changelog. What's New is metadata and is read by a reviewer; rewrite it from the actual diff before submitting. -->
```
- Connects faster and holds the connection when the network changes
- Fixed the case where the app said connected but nothing was passing
- Account, subscription and usage screens are translated
- Region list is quicker and remembers what you picked
- Many small fixes to how the connection recovers on a bad network
```

## Support URL
```
https://www.rostam.app/en/
```

## Marketing URL
```
https://www.rostam.app/en/
```

---

## The prices above are verified, but the products are not live

Read from `/v1/subscriptions/{id}/prices?filter[territory]=USA`, not assumed:
`rostamvpn.monthly` is **$3.99** and `rostamvpn.yearly` is **$39.99**, which
agrees with Play. Two things to know before this ships:

- Both products are **`READY_TO_SUBMIT`**, not `APPROVED`. They have never gone
  up attached to a version, and a product in that state returns **nothing**
  from production StoreKit — so the paywall is empty today and RostamVPN iOS
  earns no subscription revenue regardless of what this text says.
- **Neither product has an introductory offer.** `introductoryOffers` is empty
  on both, where RedVPN carries a 1-week free trial on each. The description
  above therefore promises no trial, correctly. Adding one is the single
  cheapest conversion change available here; see README §9.

Their public display names are also still placeholders ("Premium Monthly" as
both the name and the description).

## Screenshot brief

**All six live iPhone screenshots must be replaced. Four of them leak.**
Details in the README; this is what to capture instead.

Follow the same style as the RedVPN brief: a headline over a real screen in a
device frame, which is what all twenty surveyed competitors do and what 2.3.3
permits. What must be real is the screen inside the frame.

⚠️ **One competitor convention is closed to us.** Numeric proof panels are
near-universal in slot 2 — "165+ locations", "140+ countries / 20,000+
servers". §0 forbids every one of those. Lead on the one-tap promise and on
what is never asked for instead; that is a real conversion cost, taken
deliberately.

Rules for every frame:

- **No transport, protocol or connection-mode names anywhere on screen.** The
  live set publishes four of them plus the words "Disguised as normal web
  traffic".
- **No country names, no flags, no region picker sheet.** Set the region to
  Automatic before capturing.
- No settings or drawer screens in slots 1 and 2. Guideline 2.3.3 wants the
  app in use.
- No stale version strings. One live frame shows "Version 2.0.0" on a listing
  whose current version is 4.1.3.

| Slot | Screen | State |
|---|---|---|
| 1 | Home, connected | "VPN On", region on Automatic, no flag, no mode row |
| 2 | Home, disconnected | The one-tap idle screen |
| 3 | Usage stats | Real numbers from a real session |
| 4 | Paywall | Both plans, real prices, Terms and Privacy links visible |
| 5 | Region list | Only if it can be shown without country names; otherwise settings |
| 6 | Home, connected, dark | Same rules as slot 1 |

Slot 5 is the hard one. If the region list cannot be rendered without naming
countries, **drop the slot** rather than shipping it: an empty slot costs a
little conversion, and the current one costs more than that.
