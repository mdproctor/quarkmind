# QuarkusMind — Design Snapshot
**Date:** 2026-04-09
**Topic:** SC2 command dispatch complete — bot can now act in a live game
**Supersedes:** [2026-04-09-quarkmind-all-four-plugins-complete](2026-04-09-quarkmind-all-four-plugins-complete.md)
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

QuarkusMind can connect to and observe a live StarCraft II process, and as of
this session, can issue real game commands. `ActionTranslator` — a pure static
class mirroring `ObservationTranslator` — converts the `IntentQueue` drain into
`ResolvedCommand` records; `SC2BotAgent.onStep()` applies them via the ocraft
`ActionInterface`. All four plugin seams produce intents with real unit/building
tags, which now reach the game. 187 tests pass. The `Intent` interface is sealed,
so the compiler enforces exhaustiveness when new intent types are added.

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| `translate()` returns `List<ResolvedCommand>` not void | Pure function — testable without mocking | `ActionInterface` has 12+ overloads; returning data eliminates any mocking framework | Call `ActionInterface` directly in translator |
| `ResolvedCommand` package-private | Only `ActionTranslator` + `SC2BotAgent` interact with it | No reason to expose it beyond `sc2.real` | Public record |
| Tag-based dispatch (`ActionInterface.unitCommand(Tag, ...)`) | No `UnitInPool` lookup needed | Dead/stale tags silently ignored by SC2; simpler code | Look up `UnitInPool` via `observation()` |
| Seal `Intent` interface | Compiler enforces switch exhaustiveness | Without sealing, a new intent type silently falls through to a `default` no-op | Leave open with `default` warn-and-skip |
| `casehub-persistence-memory` as runtime dep | CaseHub split into core + persistence modules | `CaseEngine` now injects `TaskRepository` / `CaseFileRepository` from the persistence module | Bundle everything in casehub-core |
| `quarkus.index-dependency` for persistence jar | No Jandex in `casehub-persistence-memory` jar | Quarkus skips CDI scanning of jars without `META-INF/jandex.idx` | Rebuild CaseHub with Jandex plugin |

## Where We're Going

**Next steps:**
- Live SC2 smoke test (#13) — run `%sc2` profile against a real SC2 process; verify observation, plugin firing, and command dispatch end-to-end. Blocked on SC2 being available.
- GraalVM native image tracing (#14) — run tracing agent during a live session to capture ocraft/protobuf `reflect-config.json`. Blocked on #13.
- Intent dispatch quality — probe selection, building availability checks — currently the bot commands whatever tag the plugin supplies; no guard against dead units or incomplete buildings.

**Open questions:**
- Scouting CEP threshold calibration (#16) — ROACH_RUSH ≥6, 3RAX ≥12, 4GATE ≥8/8 are R&D estimates; need replay data to tune.
- `FlowEconomicsTask` budget arbitration bug (#15) — each consume step sees original budget, not decremented; overcommits intents per tick.
- Expansion detection heuristic — "enemy unit > 50 tiles from main" accuracy against real SC2 unknown.
- Geysers in `ObservationTranslator` — currently returns `List.of()`; deferred to Phase 3.
- GOAP goal assignment hot-reload — DRL enables it but has never been exercised in practice.

## Linked ADRs

*(No ADRs created yet — candidates: CaseHub as orchestrator, Drools+GOAP for TacticsTask, Java-managed CEP buffers for ScoutingTask, pure-function translator pattern for ActionTranslator.)*

## Context Links

- Design spec (action translation): `docs/superpowers/specs/2026-04-09-ocraft-action-translation-design.md`
- Implementation plan: `docs/superpowers/plans/2026-04-09-ocraft-action-translation.md`
- GitHub epic: mdproctor/quarkmind#11
- Issues: #12 (Closed), #13, #14, #15, #16
- Previous snapshot: `docs/design-snapshots/2026-04-09-quarkmind-all-four-plugins-complete.md`
