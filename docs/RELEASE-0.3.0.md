# VeloZip 0.3.0 — unreleased validation notes

Transport protocol remains **v1**. No commit, push, tag or release was performed.

## Operational changes

TX and RX count successfully constructed/parsed VeloZip frames separately. Wire bytes include the VeloZip header; original bytes include inner packet length prefixes. TX counts submission, not TCP acknowledgement. Aggregate values combine directions; average outgoing batch size uses TX only. Compression operations count attempts, including incompressible RAW fallback and failed compression; latency records those attempts. Concurrent LongAdder snapshots are immutable but not globally atomic.

Configuration is strict: unknown keys, duplicate keys, wrong types, explicit null values and non-integer/out-of-range numeric values fail. Both `servers: {lobby: false}` and `servers: {lobby: {enabled: false}}` work. YAML exception causes are deliberately suppressed to avoid leaking secrets. `/velozip config` shows redacted effective values; restart is required after editing. Proxy `/velozip servers` shows immutable last-attempt state, not a count of all active connections. `/velozip retry <server>` clears a failed cooldown; reconnect to retry. Failure cooldown is 30 seconds and successful activation replaces the failure state.

Backend output is Java 21, descriptor API 1.21.11, Mojang mappings. Paper API is pinned to `1.21.11-R0.1-20260511.115010-91`. Common/proxy remain Java 17. Compilation uses Java 25; this does not require Java 25 for a 1.21.11 backend.

## Fresh runtime evidence

All tests use Velocity **4.1.1 build 24**, loopback listeners, fresh worlds and isolated runtime copies. No production world, configuration, plugin or service was changed. A pass means login reached PLAY, the bot remained connected through its observation window, and both ends logged transport activation. It is not a load test or complete protocol-transition certification.

| Backend | Java | Evidence directory under `build/` | Result |
|---|---|---|---|
| Purpur 26.1.2 build 2592 | 25 | `e2e-fbKX7u` | Pass |
| Paper 26.1.2 build 74 | 25 | `e2e-db1YgI` | Pass |
| Paper 1.21.11 build 132 | 25 | `e2e-yIADiR` | Pass |
| Paper 1.21.11 build 132 | 21 | `e2e-lmR2wj` | Pass |
| Purpur 1.21.11 build 2568 | 21 | `e2e-r6uGKY` | Pass |
| Paper 26.2 build 121 | 25 | `e2e-qjUAOH` | Pass with backend ViaVersion + ViaBackwards 5.12.0-SNAPSHOT |
| Paper 26.2 build 121 | 25 | `e2e-u2zqi6` | Expected failure: native 26.1 bot is outdated; exit 1 |

The installed MCProtocolLib release metadata provides 26.1-1, not a native 26.2 bot. The translated 26.2 result must not be represented as a native-client test. Earlier attempts include invalid generated forced-host configuration (fixed in harness), slow Java downloads and Gradle contention during live tests (addressed with curl caches and direct bot classpaths). A connection-reset warning can occur during intentional bot shutdown after the successful observation window.

## Exact ABI checks

`build/evidence/*-abi.txt` contains `javap -s` output from the actual patched runtime jars, not source assumptions. The five backend builds above match the load-bearing surface:

- `CraftPlayer.getHandle(): ServerPlayer`.
- `ServerPlayer.connection: ServerGamePacketListenerImpl`.
- Inherited `ServerCommonPacketListenerImpl.connection: Connection`.
- `Connection.channel: io.netty.channel.Channel`.
- `Varint21FrameDecoder(BandwidthDebugMonitor)` and Netty decoder inheritance.
- `GlobalConfiguration.get()`, `proxies: GlobalConfiguration$Proxies`, `velocity: GlobalConfiguration$Proxies$Velocity`, `enabled: boolean`.

Velocity 4.1.1 runtime descriptors were captured for ConnectedPlayer, VelocityServerConnection, MinecraftConnection and MinecraftVarintFrameDecoder. The latter constructor takes `ProtocolUtils$Direction`. Successful live activation exercises the actual pipeline anchors and these linkage paths. These are exact-build checks, not a guarantee for every patch of a version family.

## Reproduction

Use absolute JDK paths. Do not modify shared environment symlinks.

```bash
JAVA_HOME=/env/zulu25 bash /project/Velozip/gradlew -p /project/Velozip \
  test shadowJar :velozip-itest:writeBotClasspaths --no-daemon \
  -Dorg.gradle.java.installations.paths=/env/zulu25
JAVA=/env/zulu25/bin/java \
BACKEND_JAR=/absolute/server.jar PROXY_JAR=/absolute/velocity.jar \
/env/node/bin/node /project/Velozip/scripts/e2e.mjs
```

Optional `BACKEND_JAVA` selects Java 21 independently. `BACKEND_SEED` copies only libraries/cache/versions read-only; never worlds or plugins. `BOT_TASK=botLegacy` selects 1.21.11. `VIA_JARS` is a colon-separated list of absolute translator jar paths copied only into the isolated backend. The harness chooses ephemeral loopback ports (a small allocation-to-bind race remains), retains logs and kills only child processes it created. Run matrix entries sequentially on memory-constrained machines.

The wrapper initially timed out downloading 9.7.1. A private curl download from downloads.gradle.org was verified against its published SHA-256 (`acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`) and placed in the wrapper cache. Gradle 9.5.0 provided the pristine 43-test baseline; subsequent tests/builds used the actual 9.7.1 wrapper.

## Final local validation and artifacts

All **49 tests passed**, zero failures/errors/skips, under the pinned Gradle 9.7.1 wrapper and JDK 25 with Netty PARANOID leak detection. See `build/evidence/final-tests.log` and module XML reports. Final jars were smoke-tested again on Paper 1.21.11/132 with backend Java 21 (`build/e2e-neyvLK`) and Paper 26.2/121 with the documented backend translators (`build/e2e-5aelNj`); both passed. No owned runtime processes remained after cleanup.

Bytecode inspection confirms backend major 65 (Java 21), proxy/common major 61 (Java 17). The backend manifest contains `paperweight-mappings-namespace: mojang`. Neither shaded deliverable includes platform stub packages.

| Artifact | SHA-256 |
|---|---|
| `velozip-backend/build/libs/VeloZip-Backend-0.3.0.jar` | `9c4bfd5e8d53897478b9444d17e034afdc10df294bde7a658a1b9a674b142614` |
| `velozip-velocity/build/libs/VeloZip-Velocity-0.3.0.jar` | `ac6df75fbd9bd38c96f47cff791c1e9aa732fd9c68ddbde1a5e0c93c99962477` |

Checksums are also in `build/checksums.txt`; exact runtime/translator input hashes are in `build/evidence/input-checksums.txt`. Build warnings remain for native access on JDK 25 and Shadow service-file duplicate handling. No new benchmark claims are made.

## Remaining validation scope

No fresh Velocity 3.4/3.5/4.0 live matrix, sustained load benchmark, rapid backend-switch stress, or exhaustive lifecycle race suite was completed. Existing historical compatibility/performance evidence remains historical. Candidate smoke tests were run during implementation; final artifact checksums identify the final local build separately. Native 26.2 client E2E remains untested. Do not infer full production certification from the smoke matrix.
