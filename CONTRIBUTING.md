# Contributing to VeloZip

Thanks for your interest!

## Ground rules

- **Never guess internals.** Any change touching Netty pipelines, reflection, or platform internals must be verified against the actual source of the supported combination (currently Velocity 3.4.0 / Purpur 26.1.2) and cite the evidence in the PR description.
- The client ↔ proxy link is **off-limits**. Backend channels only.
- Correctness > stability > latency > CPU > bandwidth > features. Optimize in that order.
- Netty reference counting: every `retain` needs a matching `release` on every path, including error paths. Tests run with `PARANOID` leak detection — keep them green.

## Workflow

1. Fork, create a feature branch.
2. `./gradlew build` must pass (tests + leak detection).
3. Conventional Commits (`feat(velocity): ...`, `fix(common): ...`, `docs: ...`).
4. PRs that change protocol behavior must bump `TransportProtocol` or document backward compatibility.

## Compatibility policy

Phase 1 supports exactly Velocity 3.4.0 ↔ Purpur 26.1.2. New platform support goes through new `NetworkAdapter` implementations — the compression core (`velozip-common`) should not need platform-specific code.
