# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] — 2026-08-30

Client range expansion: one plugin jar per side now covers **Velocity 3.4.0 → 4.1.1**
and **Purpur 26.1.2 / 26.2**. No protocol change — transport protocol stays v1, so
v0.2.0 and v0.1.0 ends interoperate freely.

### Added
- Runtime platform check: each side parses its platform version string at startup
  and logs a clear warning when it is outside the source-verified set
  (Velocity 3.4.x/3.5.x/4.0.x/4.1.x; Purpur 26.1.x/26.2.x). Negotiation is still
  attempted — the existing pipeline type checks fail safe to vanilla.
- `velozip-itest:botLegacy` Gradle task: the E2E bot linked against
  MCProtocolLib 1.21.11-1 (protocol 774) to drive legacy-client E2E.
- `PlatformVersions` version-string parser (unit-tested) shared by both plugins.
- E2E matrix documentation in README (four verified combinations with stats).

### Changed
- Adapters renamed to reflect the real supported range:
  `Velocity340NetworkAdapter` → `VelocityNetworkAdapter`,
  `Purpur2612NetworkAdapter` → `PurpurNetworkAdapter`. No behavior change;
  handler names and hook points are identical across 3.4.0–4.1.1 and
  26.1.2–26.2 (verified against Velocity `6b1ea78`/`db0a17e` and Paper
  `ver/26.1.2`/`main`).
- Plugin version constant centralized (`VeloZipVelocityPlugin.PLUGIN_VERSION`,
  `VeloZipBackendPlugin.PLUGIN_VERSION`).

### Verified (live E2E, protocol bots, Java 25)
- **A — modern stack, no Via:** Velocity 4.1.1 (build 24) ↔ Purpur 26.1.2
  (build 2592), MC 26.1 bot native — transport on both ends, **82.1%**
  bandwidth reduction.
- **B — v0.1.0 stack regression:** Velocity 3.4.0 (build 566) ↔ Purpur 26.1.2,
  ViaVersion 5.11.0 bridging — transport on both ends, **82.6%**.
- **C — Purpur 26.2 backend:** Velocity 4.1.1 ↔ Purpur 26.2 (build 2627),
  ViaVersion/ViaBackwards 5.12.0-SNAPSHOT bridging 775→776 — 9/10 sessions
  clean, **79.2%**.
- **D — legacy client:** Velocity 4.1.1 ↔ Purpur 26.1.2, **MC 1.21.11 bot**
  via Via 5.11.0 — transport on both ends, **83.4%**.
- 43 unit tests green under Netty `PARANOID` leak detection.

### Notes
- With Velocity 3.5.1+ (recommended: **4.1.1**, which registers protocols up to
  Minecraft 26.2), 26.x clients connect natively and **no ViaVersion is needed
  anywhere**. ViaVersion remains necessary only on Velocity 3.4.0 (protocol
  registry ends at 1.21.11) or when bridging client/server version gaps.
- Velocity 3.4.0 is UNSUPPORTED by PaperMC since 2026-08-24; it still works
  with VeloZip but upgrading is recommended.
- Known upstream issues (not VeloZip): Via 5.11.0 26.1→1.21.11
  LEVEL_PARTICLES translation kicks clients shortly after join (observed in B);
  MCProtocolLib 1.21.11 bot fails to decode a ClientboundLevelEventPacket sent
  through the Via bridge (observed in D). In both cases VeloZip framing,
  compression and negotiation were verified working before the upstream error.
- Known cosmetic issue (pre-existing since 0.1.0): the encoder side does not
  count RAW/ZSTD frame types, so `/velozip stats` on the sending end shows
  frame counts of 0; byte counters and ratios are correct. Fix planned for
  0.3.0.
- One non-reproducible disconnect occurred in C (serverbound frame "34 bytes
  extra" immediately after a bot re-connect right after an unclean server
  restart); 9 subsequent sessions including 5 rapid-fire ones were clean. If
  it recurs, suspect the activation timing window around `ServerConnectedEvent`.

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