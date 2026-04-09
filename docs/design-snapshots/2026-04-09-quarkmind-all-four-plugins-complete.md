# QuarkusMind — Design Snapshot
**Date:** 2026-04-09
**Topic:** All four R&D plugin seams complete — project renamed QuarkusMind
**Superseded by:** [2026-04-09-sc2-command-dispatch-complete](2026-04-09-sc2-command-dispatch-complete.md)

---

## Where We Are

QuarkusMind (formerly "starcraft") is a Quarkus-based StarCraft II agent
platform built as a living R&D testbed for Drools, Quarkus Flow, and CaseHub.
All four plugin seams are implemented and tested: StrategyTask, EconomicsTask,
TacticsTask, and ScoutingTask — each using a different R&D framework. 173 tests
pass. The project has been renamed, repackaged (`io.quarkmind`), and moved to
`mdproctor/quarkmind` on GitHub.

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Orchestration framework | CaseHub (Blackboard/CMMN) | Provides case file, task lifecycle, reactive control loop | Custom scheduler, Kogito |
| StrategyTask R&D integration | Drools 10 forward-chaining rules | Declarative rules, hot-reload, native-safe Executable Model | Jadex BDI (GPL-3.0), Jason (no Maven Central) |
| EconomicsTask R&D integration | Quarkus Flow (CNCF Serverless Workflow) | Stateful workflow, Quarkus-native, LangChain4j bridge | SmallRye reactive pipeline, Temporal |
| TacticsTask R&D integration | Drools + custom Java GOAP planner | Drools classifies unit groups; A* finds cheapest action plan. Decoupled at GoapAction boundary | gdx-ai behaviour trees (JVM-only, no GraalVM metadata) |
| ScoutingTask R&D integration | Drools rule units + Java-managed CEP buffers | Fresh RuleUnitInstance per tick from Java Deque buffers avoids Drools Fusion STREAM mode incompatibility with Quarkus rule unit model | Drools Fusion window:time() (requires KieSession + kmodule.xml, conflicts with drools-quarkus extension) |
| Drools inter-phase signalling | DataStore<String> (not List<String>) | Drools has no hook into plain Java collections; DataStore insertion triggers RETE re-evaluation | Plain List (silent rule non-fire — GE-0109) |
| Plugin deactivation | @Alternative on inactive CDI bean | Quarkus Arc deactivates @Alternative beans without beans.xml or @Priority on the replacement | Deleting old implementation |
| Project naming | QuarkusMind | Combines Quarkus (full name) + Mind (intelligence, CaseHub blackboard, SC2 OverMind reference). Race-agnostic. | QuarkMind, FlowMind, QuarkCraft |
| Java package root | io.quarkmind | Clean new namespace signals standalone project | org.acme.starcraft (placeholder), org.quarkmind |

## Where We're Going

The four plugin seams provide the R&D scaffolding. The next focus areas are
real SC2 integration and deeper intelligence quality.

**Next steps:**
- Phase 1 real SC2 connection — ocraft-s2client integration, GraalVM native
  image tracing agent, SmallRye Fault Tolerance on connection path
- Scouting threshold calibration — CEP build-order detection thresholds (roach
  count, marine count) need tuning against IEM10 / AI Arena replay datasets
- LangChain4j experimental StrategyTask — LLM-guided strategy as fifth R&D
  integration (Phase 4+, Ollama local model)

**Open questions:**
- FlowEconomicsTask budget arbitration — each consume step sees the original
  budget (not decremented); SC2 rejects unaffordable commands so no live
  breakage, but the design assumption was wrong. Real fix requires per-step
  budget tracking in the Flow.
- Quarkus Flow per-tick instance overhead — unknown at 500ms/tick against real
  SC2. Needs profiling before Phase 1 go-live.
- GOAP goal assignment hot-reload — DRL enables it but has never been exercised
  in practice. Untested path.
- Expansion detection heuristic — ScoutingTask uses "enemy unit > 50 tiles from
  estimated main base" as expansion proxy. Accuracy against real SC2 unknown.
- SC2Engine.tick() ownership — who owns the tick loop when real SC2 is
  connected? Open since Phase 0.

## Linked ADRs

*(No ADRs created yet — candidates: CaseHub as orchestrator, Drools+GOAP for
TacticsTask, Java-managed CEP buffers for ScoutingTask.)*

## Context Links

- Design spec (scouting): `docs/superpowers/specs/2026-04-08-drools-cep-scouting-design.md`
- Design spec (tactics): `docs/superpowers/specs/2026-04-08-drools-goap-tactics-design.md`
- Library research: `docs/library-research.md`
- Native compatibility: `NATIVE.md`
- GitHub: https://github.com/mdproctor/quarkmind
