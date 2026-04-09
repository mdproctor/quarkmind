# Handover — 2026-04-09

**Head commit:** `c9d76e8` — docs: session wrap 2026-04-09 — QuarkusMind rename + all four plugins

## What Changed This Session

- **DroolsScoutingTask** — fourth and final R&D plugin integration complete. Drools rule units + Java-managed temporal buffers (3-min build-order window, 10-sec army alert). Detects ZERG_ROACH_RUSH, TERRAN_3RAX, PROTOSS_4GATE, timing attacks, expansion posture. 173 tests passing.
- **Project renamed to QuarkusMind** — `org.acme.starcraft` → `io.quarkmind`, `StarCraftCaseFile` → `QuarkMindCaseFile`, GitHub `mdproctor/starcraft` → `mdproctor/quarkmind`, local folder → `/Users/mdproctor/claude/quarkmind`. SC2-specific code deliberately kept SC2 references.
- **Design snapshot** — first snapshot created: `docs/design-snapshots/2026-04-09-quarkmind-all-four-plugins-complete.md`
- **Blog** — first entry: `docs/blog/2026-04-09-mdp01-scouting-gets-a-mind.md`
- **Garden** — GE-0121 (folder rename breaks shell cwd), GE-0122 (Java sub-package scope), GE-0123 (Drools CEP via Java buffers)

## Key Technical Findings (DroolsScoutingTask)

- `drools-quarkus` STREAM mode + `window:time()` operators conflict with the rule unit model's KieBase compilation — cannot use Drools Fusion temporal operators with Quarkus rule units. Fix: Java-managed `Deque<Event>` buffers with explicit eviction; fresh `RuleUnitInstance` per tick.
- `ENEMY_BUILD_ORDER` must always be written to CaseFile (use "UNKNOWN" fallback) to match `producedKeys()` contract — conditional write omits it when no build detected, inconsistent with `ENEMY_POSTURE` pattern.
- Folder rename (`mv`) breaks the Bash tool's shell cwd silently — do renames last and use absolute paths afterward.

## Immediate Next Step

**Phase 1 — Real SC2 connection.** All four plugin seams are done. Before starting: create GitHub epic + child issues, then brainstorm. Key concerns: ocraft-s2client integration, GraalVM native image tracing agent, SmallRye Fault Tolerance on connection path.

## Open Questions / Blockers

- FlowEconomicsTask budget arbitration — each consume step sees original budget (not decremented); design assumption was wrong, real fix requires per-step tracking
- Quarkus Flow per-tick instance overhead — needs profiling against real SC2 at 500ms/tick
- Scouting CEP thresholds (≥6 ROACH, ≥12 MARINE, ≥8 STALKER/ZEALOT) — R&D estimates, need calibration against replay data
- Expansion detection heuristic (enemy unit > 50 tiles from estimated main) — accuracy against real SC2 unknown
- SC2Engine.tick() ownership, ReplayEngine profile, 7 unparseable AI Arena replays — open since Phase 0
- GOAP goal assignment hot-reload — never exercised in practice

## References

| Context | Where |
|---|---|
| Design snapshot | `docs/design-snapshots/2026-04-09-quarkmind-all-four-plugins-complete.md` |
| Scouting spec | `docs/superpowers/specs/2026-04-08-drools-cep-scouting-design.md` |
| Blog | `docs/blog/2026-04-09-mdp01-scouting-gets-a-mind.md` |
| GitHub | mdproctor/quarkmind |
| Library research | `docs/library-research.md` |
| Garden | `~/claude/knowledge-garden/` (GE-0121, GE-0122, GE-0123 submitted) |

## Environment

- **Project root:** `/Users/mdproctor/claude/quarkmind` (renamed from `starcraft` this session)
- **Build:** `mvn test` — 173 tests, all pass
- **CaseHub:** install from `/Users/mdproctor/claude/alpha` before building
- **Replay libs:** `cd /Users/mdproctor/claude/scelight && ./scripts/publish-replay-libs.sh`
