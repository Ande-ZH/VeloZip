# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] — 2026-08-24

First public release. Phase 1 supports exactly one combination: **Velocity
3.4.0 ↔ Purpur 26.1.2**.

### Added
- VeloZip transport protocol v1: `0x00 0x5A` magic framing with RAW/Zstd-1
  payloads, length-validated before any allocation, decompression-bomb guarded.
- PLAY-state handshake over the `velozip:negotiate` plugin message (nonce +
  HMAC-SHA256 optional shared secret); atomic event-loop pipeline swap on
  both ends with a 5 s timeout / REFUSE fallback and `require-velozip` denial.
- Packet batching (64 KiB / 500 µs window) with RAW bypass below
  `raw-threshold` or when compression does not pay off.
- `/velozip status|stats` on both platforms (byte counters, frame counts,
  average batch size, compress/decompress P50/P95/P99 via HdrHistogram).
- `velozip-common` / `velozip-velocity` / `velozip-backend` /
  `velozip-benchmark` / `velozip-itest` Gradle modules; `./gradlew build`
  produces `VeloZip-Velocity-0.1.0.jar` and `VeloZip-Backend-0.1.0.jar`.
- Source-verified network analysis (`docs/ANALYSIS.md`) and JMH results
  (`docs/BENCHMARK.md`) for the 32 vs 64 KiB default decision.

### Verified
- 34 + 6 unit tests green under Netty `PARANOID` leak detection.
- Live E2E on real Velocity 3.4.0 build 566 ↔ Purpur 26.1.2 build 2592
  (Java 25): transport activates on both ends; `/velozip stats` reports
  83.0% bandwidth reduction (30.46 KiB → 5.19 KiB), compress P50 78 µs.
- Backend stubs verified against the real patched Purpur server jar with
  `javap` (Connection.channel, Varint21FrameDecoder constructor,
  CraftPlayer→ServerPlayer→connection chain, GlobalConfiguration nesting,
  HandlerNames constants).

### Notes
- Velocity 3.4.0 (Jan 2026) predates Minecraft 26.1 (Mar 2026) and only
  registers protocol up to 1.21.11, so connecting a 26.1 client through the
  proxy requires ViaVersion 5.11.0 on both the proxy and backend (plus
  ViaBackwards on the backend). This is orthogonal to VeloZip, which
  operates below the protocol layer and never touches the player channel.