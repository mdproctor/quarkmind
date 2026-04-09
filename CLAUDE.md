# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Type

**Type:** java

## Repository Purpose

**QuarkusMind** — a Quarkus-based StarCraft II agent platform. The primary purpose is R&D — it is a living testbed for Drools, Quarkus Flow, and CaseHub (a Blackboard/CMMN framework). Intelligence is provided by swappable plugins; the platform provides scaffolding, SC2 connection, and the CaseHub control loop.

See `docs/superpowers/specs/` for the design spec and `docs/library-research.md` for the library evaluation log.

## Development Commands

**Build:**
```bash
mvn compile
```

**Test (all):**
```bash
mvn test
```

**Test (single class):**
```bash
mvn test -Dtest=SimulatedGameTest -q
```

**Run (mock mode, no SC2 needed):**
```bash
mvn quarkus:dev
# Game loop starts automatically on boot (MockStartupBean)
```

**Run (replay mode, no SC2 needed):**
```bash
mvn quarkus:dev -Dquarkus.profile=replay
# Default replay: Nothing_4720936.SC2Replay — override with -Dstarcraft.replay.file=...
```

**Run (real SC2):**
```bash
mvn quarkus:dev -Dquarkus.profile=sc2
```

## Quarkus Profiles

| Profile | SC2 needed | Purpose |
|---|---|---|
| `%mock` (default) | No | Development and unit testing against SimulatedGame |
| `%replay` | No | Agent loop against a real `.SC2Replay` — observe-only |
| `%sc2` | Yes | Real SC2 integration |
| `%test` | No | @QuarkusTest — scheduler disabled |
| `%prod` | — | Production — QA endpoints stripped |

## Testing Patterns

**Unit tests** (no Quarkus, fast):
- Instantiate classes directly via `new` — no CDI
- Tests: `SimulatedGameTest`, `ReplaySimulatedGameTest`, `ReplayEngineTest`, `BasicEconomicsTaskTest`, `BasicStrategyTaskTest`, `IntentQueueTest`, `MockPipelineTest`, `ScenarioLibraryTest`, `GameStateTranslatorTest`, `GameStateTest`

**Integration tests** (`@QuarkusTest`, full CDI context):
- Use `@Inject` to get beans; scheduler is disabled — call `orchestrator.gameTick()` directly
- Tests: `QaEndpointsTest`, `FullMockPipelineIT`, `DroolsStrategyTaskTest`, `EconomicsFlowTest`, `DroolsTacticsRuleUnitTest`, `DroolsTacticsTaskIT`, `DroolsScoutingRulesTest`, `DroolsScoutingTaskIT`
- Flow integration tests emit to a SmallRye channel and assert after `Thread.sleep(300)` — the flow processes asynchronously

**Never use `@QuarkusTest` for tests that can be plain JUnit** — boot cost is significant.

## Native Quarkus — Policy

**Native mode is the end goal, but pragmatism comes first.**

Non-native dependencies and implementations are acceptable at any phase provided they are:
1. **Self-contained** — encapsulated behind a CDI interface or plugin seam
2. **Decoupled** — the rest of the system is unaware of the non-native implementation detail
3. **Tracked** — recorded in `NATIVE.md` with a note on what would be needed to replace them

This means: if `gdx-ai` (behavior trees, JVM-only) is the right tool for `TacticsTask` today, use it. Wire it behind the `TacticsTask` CDI interface. When a native-compatible alternative exists, the swap is local to that plugin.

**Do not block progress on native compatibility. Do block native builds from shipping until `NATIVE.md` shows all critical dependencies resolved.**

See `NATIVE.md` for the per-dependency compatibility tracker.

## Code Organisation

```
src/main/java/io/quarkmind/
  domain/              Plain Java records — no framework deps, always native-safe
  sc2/                 SC2Engine seam — IntentQueue, GameStarted/GameStopped events, sealed Intent interface
  sc2/real/            Live SC2 implementation — RealSC2Engine, SC2BotAgent, ObservationTranslator, ActionTranslator
  sc2/intent/          Intent types (BuildIntent, TrainIntent, AttackIntent, MoveIntent)
  sc2/mock/            Mock SC2 implementation — SimulatedGame, MockGameObserver, MockCommandDispatcher
  sc2/mock/scenario/   ScenarioLibrary — living specification of SC2 behaviour
  agent/               CaseHub intelligence layer — QuarkMindCaseFile keys, GameStateTranslator, AgentOrchestrator
  agent/plugin/        Plugin seam interfaces (StrategyTask, EconomicsTask, TacticsTask, ScoutingTask)
  plugin/              Active plugin implementations (DroolsStrategyTask, FlowEconomicsTask, DroolsTacticsTask, BasicScoutingTask)
  plugin/scouting/     Drools CEP scouting — DroolsScoutingTask, ScoutingSessionManager, event records
  plugin/tactics/      Pure-Java GOAP planning — WorldState, GoapAction, GoapPlanner (no framework deps)
  plugin/flow/         Quarkus Flow integration — EconomicsFlow, EconomicsDecisionService, EconomicsLifecycle
  qa/                  QA REST endpoints — dev/test only (@UnlessBuildProfile("prod"))
```

## Plugin Architecture

Each plugin seam (`StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask`) is a CDI interface extending CaseHub's `TaskDefinition`. Swap an implementation by providing a new `@ApplicationScoped` `@CaseType("starcraft-game")` bean — no wiring changes elsewhere.

Four plugin levels exist (Frame → CaseFile → PlanningStrategy → TaskDefinition). See the design spec for full details.

## CaseHub Dependency

CaseHub (`io.casehub:casehub-core:1.0.0-SNAPSHOT` + `casehub-persistence-memory`) must be installed to the local Maven repo before building:

```bash
cd /Users/mdproctor/claude/casehub && mvn install -DskipTests -Dquarkus.build.skip=true
```

Running from the parent installs all modules including `casehub-persistence-memory` (provides in-memory `TaskRepository` and `CaseFileRepository`). CaseHub will move to GitHub Packages then Maven Central as it matures.

## Replay Library Dependency

The SC2 replay parser (`scelight-mpq` + `scelight-s2protocol`) is built from the Scelight fork:

```bash
cd /Users/mdproctor/claude/scelight && ./scripts/publish-replay-libs.sh
```

Run this when setting up a new environment or after any change to the `feature/standalone-modules` branch. Takes ~10 seconds.

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.

## Architecture Decision Records

`docs/adr/` holds ADRs for significant architectural choices. Reference ADR-0001
(Quarkus Flow placement) when deciding where new framework integrations belong.

## Performance Benchmarking

A game loop benchmark (`@Tag("benchmark")`) measures per-phase tick timings across the full plugin chain. Run it **before and after** any change that could affect game loop latency, then paste the result into `docs/benchmarks/` with a date and commit reference.

**When to run:**
- Adding or modifying a plugin (`StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask`)
- Changing `AgentOrchestrator.gameTick()` or `caseEngine.createAndSolve()` timeout
- Adding new Drools rules or growing the fact base significantly
- Any change to `EmulatedGame.tick()` physics (new movement, combat, wave logic)
- After a dependency upgrade (Drools, Quarkus Flow, CaseHub)
- Whenever a tick feels sluggish in the visualizer

**How to run:**
```bash
mvn test -Pbenchmark
```

Output is a timing table (mean/p95/max per phase). Paste it into `docs/benchmarks/YYYY-MM-DD-<context>.md` when recording a snapshot. The assertion thresholds are generous guards against catastrophic regressions — the real signal is the table, not a passing test.

## Key Conventions

- **Domain model** (`domain/`) must remain plain Java — no CDI, no Quarkus imports, no framework dependencies. Records, enums, and static utility classes (e.g. `SC2Data`) are all acceptable; framework annotations are not.
- **SC2 interfaces** (`sc2/`) are contracts only — no implementation logic.
- **QA endpoints** (`qa/`) carry `@UnlessBuildProfile("prod")` — they must never appear in production.
- **`SimulatedGame`** is the living specification of SC2 behaviour. When real SC2 surprises us, update `SimulatedGame` to replicate the quirk and write a test.
- **`QuarkMindCaseFile`** holds all CaseFile key constants. Never use raw string keys elsewhere.
- **CaseFile key namespaces:** `game.*` for SC2 observation state, `agent.*` for plugin-written reasoning state.
- **Commit attribution:** Do not add `Co-Authored-By` trailers to commits.

## Blog Resources

### SC2 Image Index
`docs/sc2-image-index.md` is a living index of SC2 image URLs and assets.

**Check it before searching the web for any SC2 image.** It contains direct Liquipedia URLs for all Protoss units and buildings in our domain model, race icons, wallpaper collection links, and already-downloaded assets in `docs/blog/assets/`.

**When a new image is found** (during blog writing, research, or web fetches) — add it to the index with URL, style notes, and relevance. Keep it growing.

### Replay Index
`replays/replay-index.md` is a living index of SC2 replay datasets for testing and `ReplaySimulatedGame`. Two datasets available: IEM10 Taipei 2016 (30 games, pre-processed JSON) and AI Arena bot replays (29 `.SC2Replay` files, 22 parseable).

**Check it before downloading new replays.** When a new dataset is added, update the index with metadata, labels, and scenario recommendations.

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** mdproctor/quarkmind
**Changelog:** GitHub Releases (run `gh release create --generate-notes` at milestones)

**Automatic behaviours (Claude follows these at all times in this project):**
- **Before implementation begins** — when the user says "implement", "start coding",
  "execute the plan", "let's build", or similar: check if an active issue or epic
  exists. If not, run issue-workflow Phase 1 to create one **before writing any code**.
- **Before writing any code** — check if an issue exists for what's about to be
  implemented. If not, draft one and assess epic placement (issue-workflow Phase 2)
  before starting. Also check if the work spans multiple concerns.
- **Before any commit** — run issue-workflow Phase 3 (via git-commit) to confirm
  issue linkage and check for split candidates. This is a fallback — the issue
  should already exist from before implementation began.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done).
  If the user explicitly says to skip ("commit as is", "no issue"), ask once to confirm
  before proceeding — it must be a deliberate choice, not a default.
