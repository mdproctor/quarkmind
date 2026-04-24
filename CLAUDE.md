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

**NEVER redirect Quarkus server stdout to a file without size limits.**
The game loop logs every tick; an overnight run fills the disk.
- Wrong: `mvn quarkus:dev ... > /tmp/server.log 2>&1 &`
- Right: `mvn quarkus:dev ...` (console only — Ctrl+C to stop)
- Right for background: `mvn quarkus:dev ... > /dev/null 2>&1 &`
- If you need logs in background: use the profile's `quarkus.log.file.*` config (already rotation-capped at 4G globally).

**Run (emulated physics, no SC2 needed):**
```bash
mvn quarkus:dev -Dquarkus.profile=emulated
# Opens visualizer at http://localhost:8080/visualizer.html
# Logs to /tmp/quarkmind-emulated.log (rotation configured — max 20M, 3 backups)
```

**Stopping emulated mode / cleaning log files:**
```bash
# Always kill Java BEFORE deleting log files.
# Deleting /tmp/quarkmind-emulated.log while Java has it open leaves an invisible
# open file descriptor — disk space is not freed until the JVM exits.
# Symptoms: du shows nothing, but df shows disk full. Visible via: lsof -c java | grep deleted
pkill -f 'quarkus:dev' && sleep 2 && rm -f /tmp/quarkmind-emulated.log*
```

**Run (real SC2):**
```bash
mvn quarkus:dev -Dquarkus.profile=sc2
```

**If `quarkus:dev` fails with `ClassTooLargeException`:** run `mvn clean` first. Occurs after large additions to enums or switch statements cause the Quarkus-generated startup class to exceed JVM bytecode limits. Clean removes the stale augmentation cache.

## Quarkus Profiles

| Profile | SC2 needed | Purpose |
|---|---|---|
| `%mock` (default) | No | Development and unit testing against SimulatedGame |
| `%emulated` | No | Physics simulation — EmulatedGame with real mechanics (movement, combat, enemy active AI) |
| `%replay` | No | Agent loop against a real `.SC2Replay` — observe-only |
| `%sc2` | Yes | Real SC2 integration |
| `%test` | No | @QuarkusTest — scheduler disabled |
| `%prod` | — | Production — QA endpoints stripped |

## Testing Patterns

**Unit tests** (no Quarkus, fast):
- Instantiate classes directly via `new` — no CDI
- Tests: `SimulatedGameTest`, `ReplaySimulatedGameTest`, `IEM10JsonSimulatedGameTest`, `ReplaySimulatedGameUnitTypeTest`, `ReplayEngineTest`, `BasicEconomicsTaskTest`, `BasicStrategyTaskTest`, `IntentQueueTest`, `MockPipelineTest`, `ScenarioLibraryTest`, `GameStateTranslatorTest`, `GameStateTest`, `DroolsTacticsTaskTest`, `DroolsScoutingTaskTest`, `BlinkMechanicsTest`, `GameStateInvariantTest`
- Package-private static methods on CDI beans (e.g. `DroolsTacticsTask.computeInRangeTags`, `computeOnCooldownTags`) are tested from the same package without CDI — make them `static` (not `private`) to enable this. Strategy classes (`DirectKiteStrategy`, `LowestHpFocusFireStrategy`) follow the same pattern

**Integration tests** (`@QuarkusTest`, full CDI context):
- Use `@Inject` to get beans; scheduler is disabled — call `orchestrator.gameTick()` directly
- Tests: `QaEndpointsTest`, `FullMockPipelineIT`, `DroolsStrategyTaskTest`, `EconomicsFlowTest`, `DroolsTacticsRuleUnitTest`, `DroolsTacticsTaskIT`, `DroolsScoutingRulesTest`, `DroolsScoutingTaskIT`
- Flow integration tests emit to a SmallRye channel and assert after `Thread.sleep(300)` — the flow processes asynchronously

**Playwright render tests** (`@QuarkusTest` + `@Tag("browser")`, excluded from default surefire run — need Chromium installed):
- `VisualizerRenderTest` — asserts sprite counts, positions, HUD text, on-screen position projection via `window.__test` API (Three.js renderer; `window.__test` exposes: `threeReady()`, `terrainReady()`, `wsConnected()`, `hudText()`, `unitCount()`, `enemyCount()`, `buildingCount()`, `stagingCount()`, `geyserCount()`, `fogOpacity(x,z)`, `worldToScreen(wx,wz)`, `unitMatsKeys()`, `allEnemyWorldY()`, `smokeTestDrawFn(name,dir,teamColor)`)
  - `unitMatsKeys()` — array of all registered UNIT_MATS keys (e.g. `'MARINE_F'`, `'MARINE_E'`)
  - `allEnemyWorldY()` — array of Three.js world Y positions for all current enemy sprites
  - `smokeTestDrawFn(name, dir, teamColor)` — invokes named draw fn on a temp 128×128 canvas, returns centre pixel alpha (0–255), or -1 if function not found
  - `allSceneObjectsAreWithinMapBounds` — traverses the full Three.js scene graph and asserts no mesh/sprite is outside map bounds (|x/z| ≤ 23, y ≤ 5); catches tile-position overflows and stale meshes without visual inspection
- `VisualizerFogRenderTest` — asserts Three.js fog plane state via `window.__test.fogOpacity(x,z)` and correct `GameStateBroadcast` envelope parsing (HUD shows minerals, not undefined)
- Install Chromium once: `mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"`
- Run with: `mvn test -Pplaywright` (profile configured in pom.xml, runs `@Tag("browser")` tests)
- Excluded from default surefire run via `excludedGroups=benchmark,browser`

**WebSocket integration tests** (`@QuarkusTest`, run in normal suite):
- `GameStateWebSocketTest` — connects via `java.net.http.WebSocket`, calls `engine.observe()` directly (not `gameTick()`) to avoid triggering async Flow economics which pollutes IntentQueue across tests

**EmulatedGame test helpers** (package-private, for combat tests in `EmulatedGameTest`):
- `spawnEnemyForTesting(UnitType, Point2d)` — places an enemy unit at a specific position
- `spawnFriendlyUnitForTesting(UnitType, Point2d)` — adds a friendly unit directly to `myUnits` (for blink and per-unit-type combat tests)
- `setHealthForTesting(String tag, int health)` — sets a friendly unit's HP directly
- `setShieldsForTesting(String tag, int shields)` — sets a friendly unit's shields directly
- `setEnemyStrategy(EnemyStrategy)` — sets the active enemy AI strategy for economy/attack tests
- `enemyMinerals()` — returns current enemy mineral accumulator (int) for economy assertions
- `enemyStagingSize()` — returns count of staged enemy units waiting to attack
- `setTerrainGrid(TerrainGrid)` — activate terrain for tests that verify wall enforcement or high-ground miss-chance mechanics (default null = no terrain effects)
- `setRandomForTesting(Random)` — inject a predictable Random for miss-chance tests (always-miss: return 0.0; always-hit: return 1.0)
- `addStagedUnitForTesting(UnitType, Point2d)` — inject a unit into `enemyStagingArea` (for fog-of-war visibility tests where staging area filtering is under test)

**SimulatedGame test helpers** (public, usable from any test including `VisualizerRenderTest`):
- `setUnitHealth(String tag, int health)` — inject low-health state for visualiser E2E tests
- `removeUnit(String tag)` — simulate unit death for visualiser disappearance tests
- `addStagedUnitForTesting(UnitType, Point2d)` — inject a staged enemy unit into `enemyStagingArea` snapshot (for VisualizerRenderTest)
- `clearStagedUnitsForTesting()` — clear the staging area; also called by `reset()`
- `spawnEnemyUnit(UnitType, Point2d)` — add an enemy to `enemyUnits` (for Playwright render tests)
- `spawnFriendlyUnitForTesting(UnitType, Point2d)` — add a friendly unit to `myUnits`; use in ShowcaseResource to scatter observer units that provide fog-of-war coverage across a large map
- `spawnBuildingForTesting(BuildingType, Point2d)` — add a complete (isComplete=true) building directly to `myBuildings`; use in ShowcaseResource and VisualizerRenderTest

**Showcase seeding pattern:** Use `simulatedGame.reset()` directly (not `orchestrator.startGame()`) when seeding dev/QA demo state. `reset()` clears game state without firing `GameStarted`, so the AI scheduler never activates and the showcased state stays static. `engine.observe()` still pushes the seeded state to connected browser sessions.

**Showcase must run in mock mode (default profile).** `ShowcaseResource` seeds `SimulatedGame`. In emulated mode, `EmulatedEngine` broadcasts `EmulatedGame`'s state — a completely separate object — so seeded units never appear. Always start with `mvn quarkus:dev` (no profile flag) for showcases.

**Showcase validation (mandatory before showing the user):** After any change to `ShowcaseResource` or unit Y positioning in `visualizer.js`:

1. **Automated** — run `mvn test -Pplaywright -Dtest=VisualizerRenderTest#showcaseRendersAllUnitsAboveTerrainSurface`: asserts all 65 units render, 49 buildings render (`buildingCount()==49`), every sprite Y > `TERRAIN_SURFACE_Y`, no objects outside map bounds.
2. **Manual** — start `mvn quarkus:dev`, open visualizer, seed with `curl -X POST http://localhost:8080/sc2/showcase`, then verify:
   - All 65 units visible in the camera's default view — no missing units
   - All 49 buildings visible across 6 rows at z=22..34 (Protoss z=22,24; Terran z=26,28; Zerg z=32,34)
   - No units or buildings sunk into terrain (all use `TERRAIN_SURFACE_Y` as base)
   - Both 2D sprites and 3D sphere models render correctly for all unit types
   - Camera panning and rotation is smooth with no frame-rate drop

**Building layout constraint:** Building showcase rows must use positive tile z values only. The visualizer maps `worldZ = tileZ * TILE − HALF_H`; for a 64×64 grid, tile z=0 maps to worldZ=−22.4 and negative tile z values exceed the ±23 world-unit map boundary. Use z ≥ 0 (practically z ≥ 22 to avoid the unit grid at z=2..20).

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
  sc2/                 SC2Engine seam — IntentQueue, GameStarted/GameStopped events, sealed Intent interface, TerrainProvider (CDI bean routing terrain from engine to tactics)
  sc2/real/            Live SC2 implementation — RealSC2Engine, SC2BotAgent, ObservationTranslator, ActionTranslator
  sc2/intent/          Intent types (BuildIntent, TrainIntent, AttackIntent, MoveIntent)
  sc2/mock/            Mock SC2 implementation — SimulatedGame, MockGameObserver, MockCommandDispatcher
  sc2/mock/scenario/   ScenarioLibrary — living specification of SC2 behaviour
  agent/               CaseHub intelligence layer — QuarkMindCaseFile keys, GameStateTranslator, AgentOrchestrator
  agent/plugin/        Plugin seam interfaces (StrategyTask, EconomicsTask, TacticsTask, ScoutingTask)
  plugin/              Active plugin implementations (DroolsStrategyTask, FlowEconomicsTask, DroolsTacticsTask, BasicScoutingTask)
  plugin/scouting/     Drools CEP scouting — DroolsScoutingTask, ScoutingSessionManager, event records
  plugin/tactics/      GOAP planning + CDI strategy interfaces — WorldState, GoapAction, GoapPlanner; KiteStrategy, FocusFireStrategy and @Named implementations (DirectKiteStrategy, TerrainAwareKiteStrategy, LowestHpFocusFireStrategy, OverkillRedirectFocusFireStrategy)
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
cd /Users/mdproctor/dev/scelight && ./scripts/publish-replay-libs.sh
```

Run this when setting up a new environment or after any change to the `feature/standalone-modules` branch. Takes ~10 seconds.

## Blog

**Blog directory:** `docs/_posts/`

Blog posts are Jekyll posts published at `mdproctor.github.io/quarkmind/blog/`. Each post needs frontmatter: `layout: post`, `title`, `date`. Images go in `docs/blog/assets/` and are referenced as `/quarkmind/blog/assets/filename`.

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.

## Architecture Decision Records

`docs/adr/` holds ADRs for significant architectural choices. Reference ADR-0001
(Quarkus Flow placement) when deciding where new framework integrations belong.

## Performance Benchmarking

Two benchmark tests run via `mvn test -Pbenchmark`:
- `GameLoopBenchmarkTest` — per-phase tick timings across the full plugin chain. Run before/after any change that could affect game loop latency; paste results into `docs/benchmarks/`.
- `ScoutingCalibrationTest` — runs all replay datasets to 3-min mark and prints enemy unit count statistics per matchup. Re-run after adding new replay data or changing scouting CEP windows.

**When to run `GameLoopBenchmarkTest`:**
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

`GameLoopBenchmarkTest` output is a timing table (mean/p95/max per phase). Paste it into `docs/benchmarks/YYYY-MM-DD-<context>.md` when recording a snapshot. The assertion thresholds are generous guards against catastrophic regressions — the real signal is the table, not a passing test.

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
`replays/replay-index.md` is a living index of SC2 replay datasets. Two datasets available: IEM10 Taipei 2016 (30 games, pre-processed JSON — use `IEM10JsonSimulatedGame`) and AI Arena bot replays (29 `.SC2Replay` files, 22 parseable — use `ReplaySimulatedGame`).

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
