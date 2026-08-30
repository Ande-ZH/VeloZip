# VeloZip

**English** | [中文](README.zh-CN.md)

High-performance Zstd (Level 1) transport compression between **Velocity 3.4.0–4.1.1** and **Purpur 26.1.2 / 26.2** backend servers — with **zero changes** to the client ↔ proxy link.

## What is VeloZip?

Minecraft proxies such as Velocity compress their connection to backend servers with the vanilla zlib codec. VeloZip replaces **only that backend link's** compression and framing with a purpose-built transport: packet batching + Zstandard Level 1 (via `zstd-jni` native), with a RAW bypass for tiny or incompressible batches.

- **No client mod required.** The client ↔ Velocity connection (compression threshold, zlib, encryption, framing, ViaVersion path) is completely untouched.
- **No second TCP tunnel.** VeloZip negotiates on the existing Minecraft backend connection (PLAY-state plugin message) and switches its transport framing in place.
- **No double compression.** The vanilla backend zlib handlers are removed on both ends when VeloZip activates; uncompressed Minecraft data is batched and compressed exactly once.

See [docs/ANALYSIS.md](docs/ANALYSIS.md) for the full source-verified network analysis (real Netty pipelines of both platforms, hook points, and the protocol-switch safety argument).

## How it works

```
                Minecraft Client
                       │  vanilla protocol — untouched
                       ▼
           Velocity 3.4.0 … 4.1.1
              VeloZip-Velocity.jar
                       │  existing backend TCP connection
                       │  VeloZip handshake (plugin message, PLAY state)
                       │  → protocol switch → VeloZip framing
                       │  batch (≤64 KiB / 500 µs) → Zstd level 1
                       ▼
           Purpur 26.1.2 or 26.2
              VeloZip-Backend.jar
```

1. After a player connects to a backend, VeloZip-Velocity sends a negotiation message on `velozip:negotiate` (protocol version, algorithm, level, nonce + HMAC-SHA256 if a shared secret is configured).
2. VeloZip-Backend validates it and both sides atomically swap the transport-level handlers on that connection's event loop (frame decoder → dual-mode VeloZip decoder; vanilla compression handlers removed; batch+Zstd encoder installed).
3. If either side lacks VeloZip, negotiation times out after 5 s and the connection stays 100% vanilla (or is refused when `require-velozip: true`).

## Requirements

| Component | Requirement |
|---|---|
| Proxy | Velocity 3.4.0–4.1.1 (one plugin jar for the whole range) |
| Backend | Purpur 26.1.2 or 26.2 |
| Java (proxy) | 17+ for Velocity 3.x, 21+ for 3.5.x, 25 for Velocity 4.x |
| Java (backend) | 25 (required by Purpur 26.1.2 / 26.2) |
| Client | Whatever your proxy accepts — nothing to install |

> **Client version range = your Velocity's protocol registry.** VeloZip never inspects client
> protocol versions; it compresses the proxy ↔ backend link below the packet layer. With the
> recommended **Velocity 4.1.1**, clients **1.7.2 through 26.2** connect natively — no
> ViaVersion needed anywhere. With Velocity 3.4.0 (registers only up to 1.21.11), bridging
> 26.1+ clients requires [ViaVersion](https://github.com/ViaVersion/ViaVersion) on the proxy
> and backend (plus ViaBackwards on the backend); that path is orthogonal to VeloZip and was
> re-verified in v0.2.0 (see Compatibility below).

## Installation

1. Drop `VeloZip-Velocity-x.x.x.jar` into the Velocity `plugins/` directory.
2. Drop `VeloZip-Backend-x.x.x.jar` into the Purpur `plugins/` directory.
3. Restart. Both sides log their version and platform at startup; per-connection activation is logged as `VeloZip transport enabled for <server>`. Unknown/unverified platform versions log a warning but negotiation is still attempted (fail-safe).

## Configuration

Both plugins generate `plugins/velozip/config.yml` (identical schema, plus a per-server section on the Velocity side). Key settings:

```yaml
enabled: true
compression:
  algorithm: zstd      # only zstd; anything else fails startup
  level: 1             # only 1
  raw-threshold: 128   # batches smaller than this are sent as RAW frames
batch:
  max-size: 65536          # emit batch at 64 KiB
  max-delay-micros: 500    # ... or after 500 µs
limits:
  max-frame-size: 1048576
  max-uncompressed-size: 2097152
authentication:
  secret: "CHANGE_ME"  # empty = no auth; must match on both sides
require-velozip: false # true = refuse transfer to enabled backends that fail negotiation
debug: false
```

Velocity side additionally supports per-server opt-out (`servers: { lobby: { enabled: false } }`).

## Compatibility

Verified combinations (live E2E with a protocol bot, v0.2.0):

| # | Proxy | Backend | Via | Bot | Result |
|---|---|---|---|---|---|
| A | Velocity 4.1.1 | Purpur 26.1.2 | none | 26.1 | ✅ transport on both ends, **82.1%** |
| B | Velocity 3.4.0 | Purpur 26.1.2 | 5.11.0 ×3 | 26.1 | ✅ transport on both ends, **82.6%** (bot later kicked by a known upstream Via 5.11.0 translation bug — not VeloZip) |
| C | Velocity 4.1.1 | Purpur 26.2 | 5.12.0-SNAPSHOT ×2 | 26.1 | ✅ 9/10 sessions, **79.2%** |
| D | Velocity 4.1.1 | Purpur 26.1.2 | 5.11.0 ×2 | **1.21.11** | ✅ transport on both ends, **83.4%** (bot-side packet decode error via the Via bridge — not VeloZip) |

Full Velocity range **3.4.0 → 4.1.1** and backend range **26.1.2 / 26.2** are supported by one
plugin jar on each side: the network internals VeloZip hooks (pipeline handler names,
frame-decoder classes, plugin-message API) are identical across that range — verified against
Velocity commit `6b1ea78` (3.4.0) and `db0a17e` (4.1.1), Paper `ver/26.1.2` and `main` (26.2)
(see [docs/ANALYSIS.md](docs/ANALYSIS.md) appendix). Older-but-unsupported Velocity versions
(3.4.0 is UNSUPPORTED by PaperMC since 2026-08-24) still work; 4.1.1 is recommended because it
natively registers protocols up to Minecraft 26.2.

Platform versions outside the verified set (e.g. a future Velocity 4.2) log a clear warning at
startup and negotiation is still attempted; the existing type checks keep the connection vanilla
if the pipeline layout ever diverges.

## Commands

`/velozip status` — build/protocol info and per-server negotiation state.
`/velozip stats` — original/transferred/saved bytes, compression ratio, RAW/ZSTD frame counts, average batch size, compression/decompression latency P50/P95/P99, throughput.

## Performance

- **Live E2E, v0.2.0 matrix** (see Compatibility): **79.2–83.4% bandwidth reduction** across all four combinations.
- **Live E2E, v0.1.0 stack** (Velocity 3.4.0 build 566 ↔ Purpur 26.1.2 build 2592, Java 25): **83.0% bandwidth reduction**, compress P50 78 µs.
- **JMH** (MC_LIKE dataset): compress-only ~22 µs @32 KiB, ~51 µs @64 KiB — both well under 1 ms.
- **Tests**: 43 unit tests green under Netty `PARANOID` leak detection.

See [docs/BENCHMARK.md](docs/BENCHMARK.md) for the JMH methodology, full result tables, and the 32 vs 64 KiB batch-size decision data.

## Building from source

```bash
./gradlew build
```

Produces `velozip-velocity/build/libs/VeloZip-Velocity-0.2.0.jar` and `velozip-backend/build/libs/VeloZip-Backend-0.2.0.jar`. Requires a JDK 25 toolchain (auto-provisioned by Gradle if missing); shipped bytecode targets Java 17.

## Troubleshooting

- **`no zstd-jni in java.library.path`** — the temp dir may be mounted `noexec`. Set `-DZstdTempFolder=<writable+executable dir>` or install the native library system-wide.
- **`VeloZip transport not enabled`** logs — check `authentication.secret` equality, `enabled: true` on both sides, and matching `velozip:negotiate` channel reachability.
- Negotiation never happens for a server → it is probably disabled in `servers:` or the backend plugin is missing; with `require-velozip: false` the connection stays vanilla (safe fallback).
- **`has NOT been verified with this plugin`** warning — the platform version is newer (or older) than the verified set. VeloZip still negotiates; if the pipeline layout changed incompatibly, connections fall back to vanilla automatically.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for ground rules (never guess Netty/platform internals — verify against real source and cite evidence), the Conventional Commits workflow, and the compatibility policy for adding new platform versions.

## License

[GPL-3.0](LICENSE). Bundles [zstd-jni](https://github.com/luben/zstd-jni) (BSD-2-Clause + native dual BSD-3/GPL2, **not relocated** — the JNI binding requires original class names) and [HdrHistogram](https://github.com/HdrHistogram/HdrHistogram) (CC0/Public Domain, relocated).