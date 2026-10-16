# Notes for `rostam.json`

Moved out of the JSON on 2026-09-08 because they were 62% of a file that ships sealed to every device on a metered connection.

They are kept because each one records something that already cost somebody a debugging session. Edit them here; they are no longer read by anything.

## `api`

**`_note`**

How the app reaches the backend. This block is the BOOTSTRAP: it is the one thing that cannot come from the server, because it is what tells the app where the server is. Everything else in this file is replaced by whatever GET {base}/api/v1/config returns. A newer config may carry a new api block, so bases and the key can be rotated without a store release -- the bundled copy only has to work once.

**`_fallback_note`**

Tried in order when base is unreachable. Alternate names defeat per-name DNS poisoning but not an IP block; a fallback on a different network is the missing piece.

**`_google_note`**

The cashier's WEB OAuth client id. Native Google sign-in returns an ID token whose `aud` must equal this, and the cashier verifies it server-side. It is a web client on purpose -- an Android client id would produce a token the server cannot accept. Per brand: each cashier tenant has its own.

## `(root)`

**`_transport_order_note`**

Protocol priority: which top-level transport is tried first. Listed order = try order; omit one to skip it entirely (region_policies[tz].disabled_transports still overrides per region). Endpoint-level priority/weight live on each entry: lower priority is tried first (SRV semantics, defaults 10 premium / 20 entries / 30 region v4 / 31 region v6 / 40 cdn / 50 named), weight breaks ties with a per-install-stable shuffle, and weight 0 disables an endpoint without removing it.

**`_ipv6_note`**

v6 transit is effectively free for us, so IPv6 literals are tried before IPv4 everywhere EXCEPT the regions listed in ipv4_first_regions. Iran is the exception: its mobile networks are largely v4-only, so a v6 dial is a guaranteed 8s timeout rather than a cheap win. The client's region is resolved from its timezone using freedom_regions below, so the two can never drift. Preference applies only to IP LITERALS — CDN fronts and names still come last, so this never costs extra DNS exposure. If the device has no routable v6 at all, v6 endpoints are dropped entirely (detected with a UDP route lookup that sends nothing).

**`_freedom_regions_note`**

Regions that get the product free (donor-funded), NOT a paid tier. Key = ISO country, matched against SIM/network country; value = device timezones that also count, so a foreign SIM in-country is still free. Adding a region is a config edit — no Kotlin knows any region by name. Keep in sync with the cashier: CASHIER_FREEDOM_REGIONS and app/region.py _TZ_REGION.

**`_health_note`**

Tunnel health watchdog. Active probe down the live data path; failures_before_reconnect consecutive misses trigger a full reconnect. After max_auto_reconnects failures it waits cooldown_secs and retries forever. probe_url MUST remain the approved echo endpoint (project rule: no third-party echo services).

**`_throttle_note`**

Free tier gets full speed up to the region free_quota_mb, then free_kbps instead of a hard cut-off. Freedom regions and paying users are NEVER limited — the app decides and the core only enforces. 0 disables it entirely. NOTE: this is client-side, so a patched APK removes it; real enforcement belongs on the POP.

**`_probe_note`**

Every gate is empty-means-no-restriction. regions:[] and keyboard_langs:[] = probe in ALL regions (was ir + a Persian keyboard, which between them excluded nearly everyone outside Iran). device_types and exclude_emulator stay as quality gates — a TV box or emulator makes a poor exit. sample_pct is the ramp knob: it is sticky per install, so lowering it does NOT retire devices that already rolled in.

**`_brand`**

rostam

**`_note`**

Config for the rostam app ONLY. Published with `publish-config --brand rostam`; the API returns it to clients sending `X-App-Key: rostam`. A brand with nothing published falls back to the shared config, so deleting this file's published keys reverts that app to shared policy without touching the others.

## `transport.redsocket`

**`_mux_note`**

MUX2: carry many SOCKS5 streams over ONE Noise carrier instead of a fresh TCP + HTTP-Upgrade + Noise handshake per destination. A page load costs one handshake instead of thirty, and the server's connection count falls to roughly requests / streams-per-carrier. The fleet already accepts MUX2, so this is a client-only switch. Any per-request failure falls back to the legacy single-stream path, and setting this false reverts every device on its next config fetch with no app release.

**`_premium_entries_note`**

Paid-tier POP pool. Tried FIRST for entitled users; the shared entries above stay as fallback so premium is never worse off. Free users never see these. Empty = premium shares the free pool (today). Same schema as entries: host + port are BOTH required.

**`_upgrade_hosts_note`**

Decoy Host header for direct-IP dials. Each install picks one deterministically (cid+endpoint), so a single grep of the binary no longer yields one string that identifies every client. Must be hostnames a middlebox finds unremarkable. The server reads Host for logging only — it routes on the Upgrade header — so this is NOT a wire change.

## `transport.wgtcp`

**`_comment`**

Per-device keys now come from the provisioning API (POST /api/v1/wgtcp/profile) -- see WgtcpProvisioner. This block carries only the on/off switch and defaults. The shared private key that used to live here shipped identically to every install and was bound to one /32 on the server, so exactly one device could use the tunnel at a time and nothing could be revoked. It is gone; do not reintroduce a key here.

## `probe`

**`_relay_limits_note`**

What a probe client will relay FOR the gateway. The probe exists for lightweight HTTP reachability tests, but nothing in the client enforced that — the gateway named a host and port and the client dialled it, so a compromised or spoofed gateway could turn every enrolled phone into a general-purpose proxy egressing from the user's own IP. allowed_ports: [] means no restriction (escape hatch). max_stream_bytes: 0 means uncapped. Per-region tunable once the config is served remotely.

**`_stealth_note`**

Probe TARGET connections leave the device directly (real IP, outside the tunnel) — that is what makes a reachability test mean anything, but it also means the observable signature is the pattern: a residential handset opening several connections to unrelated hosts, all at once. stream_jitter_ms spreads each dial so streams trickle instead of bursting; max_streams_per_hour is a per-device ceiling so no install ever looks like a scanner even if the gateway asks it to. 0 / [0,0] disables either.

## `region_policies`

**`_default`**

{
 "seeded_winners": [
  "http"
 ],
 "disabled_transports": []
}
