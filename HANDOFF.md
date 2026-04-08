# Handover — 2026-04-08

**Head commit:** `598d5d6` — docs: session wrap 2026-04-08 — Drools GOAP TacticsTask

## What Changed This Session

- **DroolsTacticsTask** — third R&D integration complete. Drools fires once/tick as action compiler (classifies units → groups, emits action names). Java A* (`GoapPlanner`) finds cheapest plan per group. 152 tests passing.
- **Issue tracking live** — `mdproctor/starcraft` on GitHub. Epic #1 closed. All commits reference issues going forward.
- **CLAUDE.md** — Co-Authored-By suppressed; `plugin/tactics/` added to code org; integration tests updated.
- **Garden** — GE-0105 (Drools action compiler pattern), GE-0109 (DataStore inter-phase signalling).

## Key Technical Findings (Drools Rule Units)

- Phase 2 rules silently don't fire if they depend on a `List<String>` mutated by Phase 1 — Drools has no hook into plain Java collections. Fix: Phase 1 inserts into a `DataStore<String>`; Phase 2 pattern-matches on that. (GE-0109)
- Drools as action compiler: fire once, produce `GoapAction` records, run A* in pure Java. Decoupled at `GoapAction` boundary. (GE-0105)
- `WorldState` record needs compact constructor (`Map.copyOf`) — external map mutation otherwise corrupts internals silently.

## Immediate Next Step

**ScoutingTask** — fourth and final plugin seam. Currently a stub. Before starting: create GitHub issue + epic, then brainstorm. See `docs/library-research.md` for any evaluated scouting libraries.

## Open Questions / Blockers

- Flow per-tick instance overhead — needs profiling against real SC2 at 500ms/tick
- Budget arbitration in `FlowEconomicsTask` — each consume step sees original budget (not decremented); SC2 rejects unaffordable commands so no breakage, but design assumption was wrong
- SC2Engine.tick() ownership, ReplayEngine profile, 7 unparseable AI Arena replays — open since Phase 1
- GOAP goal assignment policy hot-reload — not yet exercised in practice; DRL enables it

## References

| Context | Where |
|---|---|
| Design snapshot | `docs/design-snapshots/2026-04-08-drools-goap-tactics-integration.md` |
| Spec | `docs/superpowers/specs/2026-04-08-drools-goap-tactics-design.md` |
| Plan | `docs/superpowers/plans/2026-04-08-drools-goap-tactics-task.md` |
| Blog | `docs/blog/2026-04-08-mdp01-tactics-gets-a-brain.md` |
| GitHub issues | mdproctor/starcraft — #1 (epic, closed), #2–5 (closed) |
| Garden | `~/claude/knowledge-garden/GARDEN.md` |
| Library research | `docs/library-research.md` |

## Environment

- **Build:** `mvn test` — 152 tests, all pass
- **CaseHub:** install from `/Users/mdproctor/claude/alpha` before building
- **Replay libs:** `cd /Users/mdproctor/claude/scelight && ./scripts/publish-replay-libs.sh`
