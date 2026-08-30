# Contributing to VeloZip

Thanks for your interest!

## Ground rules

- **Never guess internals.** Any change touching Netty pipelines, reflection, or platform internals must be verified against the actual source of the supported range (currently Velocity 3.4.0–4.1.1 / Purpur 26.1.2–26.2) and cite the evidence in the PR description.
- The client ↔ proxy link is **off-limits**. Backend channels only.
- Correctness > stability > latency > CPU > bandwidth > features. Optimize in that order.
- Netty reference counting: every `retain` needs a matching `release` on every path, including error paths. Tests run with `PARANOID` leak detection — keep them green.

## Workflow

1. Fork, create a feature branch.
2. `./gradlew build` must pass (tests + leak detection).
3. Conventional Commits (`feat(velocity): ...`, `fix(common): ...`, `docs: ...`).
4. PRs that change protocol behavior must bump `TransportProtocol` or document backward compatibility.

## Compatibility policy

The verified platform range is **Velocity 3.4.0 → 4.1.1** and **Purpur 26.1.2 / 26.2**,
covered by one adapter per side (`VelocityNetworkAdapter` / `PurpurNetworkAdapter`) because
the hooked internals (pipeline handler names, frame-decoder classes, plugin-message API) are
identical across that range. Evidence for each version goes into `docs/ANALYSIS.md`
(comparison commits are cited there).

When extending to new versions:

1. Diff the new version's network layer against the last verified one (handler names in
   `Connections`/`HandlerNames`, `MinecraftVarintFrameDecoder`/`Varint21FrameDecoder`
   signatures, the NMS field chain on the backend, `sendPluginMessage` API).
2. If identical, add the family to the adapter's verified set and the runtime
   `checkPlatform` list, and cover it with a live E2E run (see README's compatibility
   matrix).
3. If divergent, add a new `NetworkAdapter` implementation — the compression core
   (`velozip-common`) must not gain platform-specific code.

The plugin is compiled against the **oldest** supported API (velocity-api 3.4.0,
paper-api 26.1.2) and runs on newer platforms — "compile old, run new" is the safe
direction; a recompile against a newer API needs a re-verification pass (e.g. Velocity 4.x
ships Adventure 5, whose 5.2.0 restores binary compatibility for 4.x-compiled plugins,
but only 5.2.0+).
