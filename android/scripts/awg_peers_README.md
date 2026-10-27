# AWG Peer Data Export — 2026-03-03

## Files

| File | Rows | Size | Description |
|------|-----:|-----:|-------------|
| `awg_peers_20260303.csv` | 7,981,195 | 738 MB | **All peers** — includes pre-provisioned (never connected) |
| `awg_peers_20260303_active.csv` | 1,290,872 | 148 MB | **Active only** — peers that have connected at least once (have endpoint IP) |

## CSV Columns

| Column | Type | Example | Description |
|--------|------|---------|-------------|
| `endpoint_ip` | string | `5.238.152.146` | Client's public IP address. Empty = never connected (pre-provisioned peer). |
| `public_key` | string | `/gluc3dL...31E=` | WireGuard/AmneziaWG peer public key (base64, 44 chars). |
| `last_handshake` | string | `2026-02-28 17:43:03 UTC` | Last successful WG handshake timestamp (UTC). `never` = peer was provisioned but has never connected. |
| `container` | string | `axion-awg-quic-fra01-01` | Container name where this peer is configured. |
| `remote` | string | `fra01-01` | Host/remote the container runs on (incus remote or SSH host). |
| `interface` | string | `awg1` | WireGuard/AWG interface name inside the container. |

## Data Sources

Collected from **10 containers** across **8 hosts** (6 incus remotes + 1 legacy LXC host):

### Incus remotes (via axion-master)

| Remote | Container | Total | Active | Type |
|--------|-----------|------:|-------:|------|
| fra01-01 | `axion-awg-quic-fra01-01` | 176,893 | 3,230 | AWG QUIC (new) |
| fra01-01 | `rostam-awg-fra01-01` | 1 | 1 | Rostam AWG |
| fra01-02 | `axion-awg-quic-fra01-02` | 323,777 | 17,564 | AWG QUIC (new) |
| fra01-02 | `rostam-awg-fra01-02` | 1,532,781 | 79,179 | Rostam AWG |
| lax01-01 | `axion-awg-quic-lax01-01` | 323,777 | 88,577 | AWG QUIC (new) |
| lax01-02 | `axion-awg-quic-lax01-02` | 323,777 | 87,212 | AWG QUIC (new) |
| lax01-03 | `axion-awg-quic-lax01-03` | 323,777 | 87,751 | AWG QUIC (new) |
| ams01-01 | `rostamvpn-legacy-rostam-awg30-ams01` | 2,198,164 | 306,040 | Legacy AWG (AMS) |

### Legacy LXC (via red-s-0001)

| Remote | Container | Total | Active | Type |
|--------|-----------|------:|-------:|------|
| red-s-0001 | `redvpn-awg001-lax01` | 580,088 | 164,730 | RedVPN legacy AWG |
| red-s-0001 | `rostam-awg24-lax01` | 2,198,160 | 456,588 | Rostam legacy AWG |

## Key Stats

- **7,981,195** total peers across all containers
- **1,290,872** (16.2%) have connected at least once (have endpoint IP)
- **6,690,323** (83.8%) are pre-provisioned but never connected
- **6,894,738** have `last_handshake = never`

## How the data was collected

```bash
# On axion-master (incus remotes):
incus exec <remote>:<container> -- awg show all dump
# Falls back to: wg show all dump

# On red-s-0001 (legacy LXC):
lxc-attach -n <container> -- awg show all dump
# Falls back to: wg show all dump
```

The `awg show all dump` output format is tab-separated:
- **Interface lines** (20+ fields): `iface, privkey, pubkey, port, fwmark, [AWG obfuscation params...]`
- **Peer lines** (exactly 9 fields): `iface, pubkey, preshared_key, endpoint, allowed_ips, last_handshake_epoch, rx_bytes, tx_bytes, keepalive`

Parser identifies peer lines by `len(fields) == 9`. Endpoint IPs extracted by stripping the port suffix. Handshake epoch `0` = never connected.

## Reproduction

```bash
# Collector script deployed on axion-master:
ssh axion-master "python3 /tmp/collect_awg_peers.py"
```
