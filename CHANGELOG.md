# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] — 待发布

### 新增
- 后端支持扩展到 Paper/Purpur 1.18（包含 1.18 本体），单个 Java 17 插件包兼容旧版 Spigot 与新版 Mojang 运行时映射。
- 兼容 `paper.yml`（1.18）和 `paper-global.yml`（1.19+）的已加载转发配置；配置不存在或读取失败时拒绝信任直连。
- 后端回归测试覆盖原生资源生命周期、在途原版包、拆包合包、管道替换、反射和 YAML 隔离。
- 原生 1.18–1.20 测试客户端、可移植 JSON fixture、Java 17/25 与 Netty 4.1/4.2 CI 矩阵及成品包检查。

### 调整
- 后端固定编译 API 为 Paper 1.18，描述文件声明 `api-version: '1.18'`；所有插件字节码目标为 Java 17，构建 JDK 保持 25。移除旧后端 NMS 编译占位类。
- 按已核验的唯一字段类型查找后端连接，兼容继承字段和旧版混淆名称。
- 两端不再打包 Netty，隔离 SnakeYAML / HdrHistogram，保留 zstd-jni 原包名。
- 发布校验和与上传仅包含两个可部署 jar，排除 thin jar。

### 修复
- 在首个入站 VeloZip 帧到达前保留旧 Paper 共用的原生压缩资源；新的出站帧跳过原版处理器，避免双重压缩。
- 同一次 TCP 读取包含原版和 VeloZip 帧时，先交付原版包再移除解压处理器。
- 修正 HdrHistogram 重定位包名的大小写。
- REQ 前注册协商通道，确保旧版 Bukkit 可以发送 REFUSE；代理使用原地回退，避免重复添加不可共享的 Netty 解码器。

### 验证
- 64 项单元测试覆盖 Java 17 / Netty 4.1.68 和 Java 25 / Netty 4.2.7；检查实际成品包的 Java 17 字节码与依赖隔离。
- 源码及接口依据见[兼容说明](docs/COMPATIBILITY-1.0.0.md)，精确实测用例见 [E2E 报告](docs/E2E-1.0.0.zh-CN.md)。
- 最终 13 个真实 E2E 用例全部通过，保留包含初轮失败在内的 26 次脱敏尝试记录及校验和。
- 传输协议保持 1，握手与帧格式不变；代码更新本身不创建 Release 或版本标签。

## [0.3.0] — Unreleased

### Fixed
- Count outbound RAW/ZSTD frames, separate immutable TX/RX metric views, and use TX bytes for average outgoing batch size. Count/time every compression attempt, including RAW fallback.
- Reject malformed YAML sections, unknown/duplicate keys, wrong scalar types, fractional/overflow integers and explicit null values. Honor nested `servers.name.enabled` and boolean shorthand. Suppress secret-bearing parser causes.
- Velocity command output now contains real line breaks. Both platforms expose redacted effective configuration, including when disabled.
- Replace permanent failure blocking with a 30-second cooldown and `/velozip retry <server>`; expose immutable last-attempt snapshots via `/velozip servers`. Cancel close/restore timers, guard stale callbacks and null REFUSE, and preflight required encoder anchors.
- E2E bot exits nonzero on failed login or premature disconnect.

### Changed
- Backend targets Java 21, pins Paper API `1.21.11-R0.1-20260511.115010-91`, lowers descriptor API to 1.21.11, and declares Mojang mappings. Common/proxy retain Java 17 bytecode; build toolchain remains Java 25. Protocol remains v1.
- Add isolated loopback, fresh-world E2E harness with owned-process cleanup and pre-resolved bot classpaths.

### Evidence
- Pristine baseline: 43 tests passed. Fresh wrapper 9.7.1 regression suite and candidate runtime evidence are detailed in [release notes](docs/RELEASE-0.3.0.md).
- Live smoke passes with Velocity 4.1.1 build 24: Purpur 26.1.2/2592, Paper 26.1.2/74, Paper 1.21.11/132, Purpur 1.21.11/2568; Paper 26.2/121 passes with backend ViaVersion/ViaBackwards 5.12.0-SNAPSHOT. Exact runtime ABI descriptors checked; no blanket version-family verification claim.
- No release has been published. Historical performance numbers below were not re-benchmarked for this version.

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
