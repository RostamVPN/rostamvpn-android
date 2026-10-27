# netcore

The Rust transport core for the RostamVPN Android client, loaded over JNI as
`libnetcore.so`. The JNI class is `app.netcore.NetCoreBridge`.

Packets from the Android `VpnService` TUN interface reach this core's local
SOCKS5 port through `hev-socks5-tunnel`, which is built separately from its
upstream source and ships as `libhev-socks5-tunnel.so`.

    src/tunnel.rs     the tunnel lifecycle
    src/mux.rs        stream multiplexing
    src/socks5.rs     the local SOCKS5 listener
    src/config.rs     configuration, including the sealed over-the-air format
    src/cfgseal.rs    sealing and unsealing of that format
    src/select.rs     endpoint selection and health
    src/blocklist.rs  destination filtering for Protect Mode
    src/geo.rs        address-to-network lookup
    src/flows.rs      per-flow accounting
    src/protect.rs    socket protection, so core traffic is routed correctly
    src/noise.rs      key exchange
    src/throttle.rs   rate limiting
    src/ctl.rs        the control channel
    src/ffi.rs        the C ABI and JNI surface

## Configuration

Endpoints, regions and keys in this repository are **documentation values**, not
real ones. Addresses come from the ranges reserved for documentation in RFC 5737
and RFC 3849, and hostnames use `.example` names reserved by RFC 2606. A
deployment supplies its own through the over-the-air configuration; see the
placeholder table in the repository README.

Files holding a real endpoint list are not published. Where one exists, a
`.example` file sits beside it showing the format.

## A note on history

Earlier revisions of this core contained a peer relay: a mechanism by which a
device could carry another device's traffic. It has been removed. The modules
that implemented it, their build feature and their configuration are gone, and
the core can no longer be built with them.
