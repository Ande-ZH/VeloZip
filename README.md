# VeloZip

**English** | [中文](README.zh-CN.md)

[![build](https://github.com/Ande-ZH/VeloZip/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Ande-ZH/VeloZip/actions/workflows/build.yml) [![Release](https://img.shields.io/badge/release-v0.3.0-blue)](https://github.com/Ande-ZH/VeloZip/releases)

High-performance Zstd (Level 1) transport compression between **Velocity 3.4.0–4.1.1** and **Paper/Purpur 1.21.11 / 26.1.2** backend servers — with **zero changes** to the client ↔ proxy link.

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
           Paper/Purpur 1.21.11 or 26.1.2
              VeloZip-Backend.jar
```

1. After a player connects to a backend, VeloZip-Velocity sends a negotiation message on `velozip:negotiate` (protocol version, algorithm, level, nonce + HMAC-SHA256 if a shared secret is configured).
2. VeloZip-Backend validates it and both sides atomically swap the transport-level handlers on that connection's event loop (frame decoder → dual-mode VeloZip decoder; vanilla compression handlers removed; batch+Zstd encoder installed).
3. If either side lacks VeloZip, negotiation times out after 5 s and the connection stays 100% vanilla (or is refused when `require-velozip: true`).

## Requirements

| Component | Requirement |
|---|---|
| Proxy | Velocity 3.4.0–4.1.1 (one plugin jar for the whole range) |
| Backend | Paper/Purpur 1.21.11 or 26.1.2 |
| Java (proxy) | 17+ for Velocity 3.x, 21+ for 3.5.x, 25 for Velocity 4.x |
| Java (backend) | 21 for 1.21.11; 25 for 26.x |
| Client | Whatever your proxy accepts — nothing to install |

> Client and backend protocol versions must match, or an independently compatible translator is required. Proxy protocol support alone does not translate between backend versions.

## Installation

1. Download `VeloZip-Velocity-0.3.0.jar` and `VeloZip-Backend-0.3.0.jar` from [GitHub Releases](https://github.com/Ande-ZH/VeloZip/releases) (each release ships both jars plus a SHA-256 `checksums.txt`), or build them from source.
2. Drop `VeloZip-Velocity-x.x.x.jar` into the Velocity `plugins/` directory.
3. Drop `VeloZip-Backend-x.x.x.jar` into the Purpur `plugins/` directory.
4. Restart. Both sides log their version and platform at startup; per-connection activation is logged as `VeloZip transport enabled for <server>`. Unknown/unverified platform versions log a warning but negotiation is still attempted (fail-safe).

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

Fresh v0.3.0 isolated smoke tests, all with Velocity 4.1.1 build 24 (not a guarantee for entire version families):

| Backend | Backend Java | Result |
|---|---|---|
| Purpur 26.1.2 build 2592 | 25 | Login, hold, bilateral activation passed |
| Paper 26.1.2 build 74 | 25 | Passed |
| Paper 1.21.11 build 132 | 21 and 25 | Passed |
| Purpur 1.21.11 build 2568 | 21 | Passed |
| Paper 26.2 build 121 | 25 | Passed with backend ViaVersion + ViaBackwards 5.12.0-SNAPSHOT; native 26.1 bot correctly rejected |

Historical results remain in CHANGELOG. Untested builds are not verified. See [v0.3.0 notes](docs/RELEASE-0.3.0.md).

## Commands

`/velozip config` shows effective configuration, never secrets; restart after changes. Proxy adds `/velozip servers` immutable last-attempt snapshots and `/velozip retry <server>` to clear failed cooldown before reconnecting. Cooldown lasts at most 30 seconds. TX/RX counters are separate; average batch uses TX bytes only; compression attempts include RAW fallback. YAML rejects unknown/duplicate keys, wrong types, fractions, overflow and explicit null. Server overrides accept nested enabled or boolean shorthand.

`/velozip status` — build/protocol info and per-server negotiation state.
`/velozip stats` — original/transferred/saved bytes, compression ratio, RAW/ZSTD frame counts, average batch size, compression/decompression latency P50/P95/P99, throughput.

## Performance

- **Live E2E, v0.2.0 matrix** (see Compatibility): **79.2–83.4% bandwidth reduction** across all four combinations.
- **Live E2E, v0.1.0 stack** (Velocity 3.4.0 build 566 ↔ Purpur 26.1.2 build 2592, Java 25): **83.0% bandwidth reduction**, compress P50 78 µs.
- **JMH** (MC_LIKE dataset): compress-only ~22 µs @32 KiB, ~51 µs @64 KiB — both well under 1 ms.
- **Tests**: 49 unit tests green under Netty `PARANOID` leak detection.

See [docs/BENCHMARK.md](docs/BENCHMARK.md) for the JMH methodology, full result tables, and the 32 vs 64 KiB batch-size decision data.

## Building from source

```bash
./gradlew build
```

Produces `velozip-velocity/build/libs/VeloZip-Velocity-0.3.0.jar` and `velozip-backend/build/libs/VeloZip-Backend-0.3.0.jar`. Requires a JDK 25 toolchain (auto-provisioned by Gradle if missing); backend bytecode targets Java 21; common/proxy target Java 17. The backend API is pinned to 1.21.11 and declares Mojang mappings.

## Project structure

| Module | Purpose |
|---|---|
| `velozip-common` | Platform-agnostic transport core: `0x00 0x5A` framing, dual-mode decode, Zstd compress/decompress, batching, negotiation, metrics |
| `velozip-velocity` | Velocity proxy plugin — negotiation, pipeline injection, config, `/velozip` commands |
| `velozip-backend` | Purpur backend plugin — request validation, pipeline injection, config, `/velozip` commands |
| `velozip-benchmark` | JMH microbenchmarks (see [docs/BENCHMARK.md](docs/BENCHMARK.md)) |
| `velozip-itest` | MCProtocolLib E2E bots — `bot` (26.1 clients) and `botLegacy` (1.21.11 clients) |

New platform versions are added by implementing a `NetworkAdapter` (one per side); the compression core never changes. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Known limitations

- When bridging client/server version gaps with ViaVersion, known **upstream** (non-VeloZip) issues exist — see the Compatibility matrix and the [CHANGELOG](CHANGELOG.md) for details.

## Troubleshooting

- **`no zstd-jni in java.library.path`** — the temp dir may be mounted `noexec`. Set `-DZstdTempFolder=<writable+executable dir>` or install the native library system-wide.
- **`VeloZip transport not enabled`** logs — check `authentication.secret` equality, `enabled: true` on both sides, and matching `velozip:negotiate` channel reachability.
- Negotiation never happens for a server → it is probably disabled in `servers:` or the backend plugin is missing; with `require-velozip: false` the connection stays vanilla (safe fallback).
- **`has NOT been verified with this plugin`** warning — the platform version is newer (or older) than the verified set. VeloZip still negotiates; if the pipeline layout changed incompatibly, connections fall back to vanilla automatically.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for ground rules (never guess Netty/platform internals — verify against real source and cite evidence), the Conventional Commits workflow, and the compatibility policy for adding new platform versions.

## License

[GPL-3.0](LICENSE). Bundles [zstd-jni](https://github.com/luben/zstd-jni) (BSD-2-Clause + native dual BSD-3/GPL2, **not relocated** — the JNI binding requires original class names) and [HdrHistogram](https://github.com/HdrHistogram/HdrHistogram) (CC0/Public Domain, relocated).