# App Store listing — RostamVPN, en-GB

App `1487875612`, bundle `com.rostamvpn`, `rostam` account.

## Why this locale exists, and why it is not "en-US with British spelling"

**Every Middle East storefront defaults to English (U.K.), not English (U.S.)**
— verified in the audit recorded in `appstore/README.md` §9, which read the
live product pages for the UAE and Saudi storefronts. `en-US` and `en-GB` are
separate App Store localizations with separate name, subtitle, keyword and
description fields. Today RostamVPN runs **one** `en-US` localization, so every
shopper in the UAE, Saudi Arabia, Qatar, Kuwait, Bahrain, Oman, Egypt, Jordan
and the rest of that region reads whatever Apple falls back to, in a field set
nobody wrote for them.

That is why this is the one App Store locale added here rather than five.
`ru` was considered and rejected: `/v1/apps/1487875612` reports RostamVPN
`available: false` in Russia, so a Russian localization would have no
storefront to appear in.

**This is not a spelling pass.** Two things are written for the market rather
than translated into it:

1. **The monthly plan leads.** RevenueCat's 2026 cross-app data, relayed in the
   README rather than measured here, has the MEA bucket preferring monthly over
   annual and showing the highest day-zero paid conversion of any region. The
   `en-US` block leads monthly too, so this is consistent rather than novel,
   but it is deliberate here.
2. **No country is named and no act is named**, which matters more in this
   locale than in any other. The UAE criminalises VPN misuse under its
   cybercrime law, and RedVPN's live Arabic listing is currently titled "VPN
   Super Proxy for UAE" — the single most removable line on any listing we
   own. `validate.py` errors on that class. This file leads on **public Wi-Fi
   and travel**, which is the honest reason most people in that region buy a
   VPN and the one that carries no jurisdictional risk.

⚠️ Everything in `appstore/rostam/en-US.md` about the live description leak
applies here unchanged: the live text publishes the cipher suite and it is the
worst §0 exposure on either store.

## Name
<!-- max 30 -->
```
RostamVPN: Fast Free VPN
```

## Subtitle
<!-- max 30. Unlike en-US this does NOT spend the field on the brand line.
     The en-US subtitle is the owner's tagline and stays his call; en-GB is a
     new localization with no history to protect, so it takes the indexed term
     instead. If both should read the same, copy the en-US line here. -->
```
Private Wi-Fi, one tap
```

## Promotional text
<!-- max 170. Editable on a LIVE listing with no review and no new build.
     Apple states plainly that promotional text does NOT affect search
     ranking, so this field is for the message, never for terms. -->
```
Free to start. Tap once and you are on. No account, no email, nothing to set up. Works the same on hotel Wi-Fi, airport Wi-Fi and mobile data.
```

## Keywords
<!-- max 100, comma separated, NO spaces after commas — Apple splits on the
     comma alone and a space costs a character. Nothing here repeats a word
     from the name or the subtitle; Apple indexes all three together.
     Singular only, because Apple stems. No competitor marks (2.3.7). -->
```
proxy,secure,privacy,anonymous,encryption,protect,safe,browsing,hotspot,travel,unlimited,shield
```

## Description
<!-- max 4000 -->
```
Your digital guardian.

Install it, tap once, and you are on. No account to make, no email to give, nothing to configure.

ONE TAP
Tap connect. RostamVPN finds a route that works on the network you are actually on, and holds it. If it drops, it comes back on its own. You do not have to choose anything or understand anything.

ON SOMEONE ELSE'S WI-FI
Hotel Wi-Fi. Airport Wi-Fi. The cafe downstairs. A conference network. These are networks you did not set up and cannot see inside, and everything your phone sends across them is visible to whoever runs them. RostamVPN carries your traffic over an encrypted connection instead, so the network you are attached to cannot read it.

Same one tap every time, at home or abroad.

BUILT FOR DIFFICULT NETWORKS
Some networks block, filter and throttle. This app was built for exactly those. When one route stops working it looks for another by itself, without asking you.

FREE TO START
Every install starts free, at full speed, with an allowance you can see at any time on the usage screen. It renews every month.

When it runs out you are not disconnected and not locked out. The connection keeps working at a lower speed for as long as you want it. Premium has no cap and never slows down.

In some regions the app is free in full, paid for by donations. No payment screen appears there, nothing is metered, and every region is open. The app works that out on its own. You do not have to ask for it or prove anything to anyone.

NOTHING TO HAND OVER
No account. No email. No phone number. No name. We do not ask for any of it, so there is nothing of that kind for us to know, hand over or lose. Deleting the app deletes everything.

WHAT IS IN IT
- Connect in one tap, with the region chosen for you
- Or choose a region yourself and change it whenever you like
- Works on iPhone and iPad
- Reconnects by itself when the network changes or drops
- Usage figures, so you always know what you have used
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
<!-- max 4000. ⚠️ DRAFT, same as every other locale here. Written from recent
     iOS commits and from the bugs this audit found, NOT from a real changelog.
     What's New is metadata and a reviewer reads it. Rewrite from the actual
     diff before submitting. -->
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

## Before this goes up

Everything blocking `en-US` blocks this too, and one thing is specific here.

- **There is no edit slot open on RostamVPN.** 4.1.3 is
  `READY_FOR_DISTRIBUTION` and no `PREPARE_FOR_SUBMISSION` version exists. One
  has to be created before any description changes, and creating it is the
  owner's call, not this directory's. **None was created.**
- **Adding a localization adds a screenshot set.** `en-GB` will need its own
  iPhone captures, or confirmation in Console that Apple inherits the primary
  locale's. Do not assume inheritance; the README records five RedVPN locales
  sitting with `appScreenshotSets` empty.
- **The subscription products are `READY_TO_SUBMIT`, not `APPROVED`**, so
  StoreKit returns nothing for them in production today. The prices above are
  real (read from the API) but the paywall is empty until they go up attached
  to a version.

## Screenshot brief

Identical to `en-US`, with one addition. Capture with the region set to
**Automatic** and the region sheet closed. Beyond the §0 rule that no exit
country may appear, a flag shown to a Gulf audience invites the reader to
decide what the app is for, which is the framing this whole locale is written
to avoid.
