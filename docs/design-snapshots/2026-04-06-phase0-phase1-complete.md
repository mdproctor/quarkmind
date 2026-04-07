# StarCraft II Quarkus Agent — Design Snapshot
**Date:** 2026-04-06
**Topic:** End-of-day-one state — Phase 0 and Phase 1 complete, replay infrastructure in place
**Superseded by:** [2026-04-07-full-agent-loop-complete](2026-04-07-full-agent-loop-complete.md)

---

## Where We Are

A Quarkus application that plays StarCraft II as a plugin platform is fully scaffolded through Phase 1. Phase 0 delivered a complete mock-first architecture (27 tests, CaseEngine cycling with 4 dummy plugins, full QA REST harness). Phase 1 wired real SC2 via ocraft-s2client — all `sc2.real` beans implemented, `%sc2` profile active, Task 9 (live SC2 connection smoke test) pending SC2 availability. Replay infrastructure is in place: `scelight-mpq` and `scelight-s2protocol` extracted as standalone Maven modules, 51 replays indexed across two datasets (IEM10 Taipei pre-processed JSON + AI Arena bot `.SC2Replay` files), `RepParserEngine` parsing confirmed on build 75689.

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Primary purpose | R&D testbed for Drools, Quarkus Flow, CaseHub | Real domain forces genuine intelligence problems | Toy demo, contrived benchmark |
| Plugin architecture | Four-level hook system (Frame, CaseFile, PlanningStrategy, TaskDefinition) | Plugins need to observe/direct other plugins, not just execute | Single seam type per concern |
| SC2 race | Protoss | Simplest production model — no Zerg larva, no Terran lift mechanics | Zerg, Terran |
| Mock-first development | Full stateful SimulatedGame before real SC2 | Fast CI, living specification, SC2 not always available | Connect to SC2 from day one |
| SC2 Java API | ocraft-s2client 0.4.21 | Only JVM option | None viable |
| CaseHub dependency | Local Maven install (`mvn install`) | Pre-release; will move to GitHub Packages | Embedded source, git submodule |
| Scelight extraction | Standalone branch in local Scelight fork | Atomic commits for clean future PR; quick rebuild script | Extract into starcraft-agent, use JitPack full artifact |
| Replay datasets | SC2EGSet (pre-processed JSON) + AI Arena bot replays | Different characteristics: esports vs bot regularity | Python sc2reader sidecar only |
| Native Quarkus | End goal, not day-one constraint | Non-native parts acceptable if decoupled and tracked in NATIVE.md | Block all progress until native-compatible |
| Quarkus version | 3.34.2 (not 3.32.2 as planned) | Maven plugin auto-resolved to latest — accepted upgrade | Force 3.32.2 |
| Drools coordinates | `org.drools:drools-quarkus-ruleunits:10.1.0` (Apache KIE) | Moved from Red Hat to ASF; now native-compatible via Executable Model | Old `org.kie:*` coordinates |

## Where We're Going

**Next steps:**
- `ReplaySimulatedGame` — step through tracker events from a real replay instead of hand-crafted economic trickle; Nothing bot's 4 consistent 8-9min games are the baseline
- Phase 2 — `BasicEconomicsTask` with real logic: probe production, pylon supply management, nexus saturation
- Phase 1 Task 9 — live SC2 smoke test when SC2 available on this machine
- Mock auto-start — `SC2StartupBean` equivalent for mock profile so `mvn quarkus:dev` works without `POST /sc2/start`

**Open questions:**
- How does `ReplaySimulatedGame` handle the mismatch between game loop (~22fps) and tracker event granularity (one event per action, not per frame)?
- When Scelight upstream merges the standalone module PR, do we switch to their Maven artifacts or keep our fork? Triggers a one-line pom.xml change.
- ocraft-s2client native image path — requires GraalVM tracing agent run against a live SC2 game to generate `reflect-config.json` for all SC2 protobuf types; 7 AI Arena replays couldn't be parsed (build newer than 81009 not yet in s2protocol .dat files).
- Which plugin implementation comes first in Phase 3: Drools `StrategyTask`, Quarkus Flow `EconomicsTask`, or CaseHub `PlanningStrategy`?

## Linked ADRs

*(No ADRs created yet — all decisions captured in this snapshot and the design spec)*

## Context Links

- Design spec: `docs/superpowers/specs/2026-04-06-starcraft-agent-design.md`
- Library research: `docs/library-research.md`
- Phase 0 plan: `docs/superpowers/plans/2026-04-06-phase0-mock-architecture.md`
- Phase 1 plan: `docs/superpowers/plans/2026-04-06-phase1-sc2-bootstrap.md`
- Scelight fork: `/Users/mdproctor/claude/scelight` branch `feature/standalone-modules`
- Replay index: `replays/replay-index.md`
- NATIVE.md: dependency compatibility tracker at project root
