# Retrospective Issue Mapping — quarkmind

**Date:** 2026-04-14
**Scope:** All commits not already linked to GitHub issues
**Already covered:** #1–#5 (TacticsTask), #6–#10 (ScoutingTask), #11–#12 (ActionTranslator), #13–#16 (open issues), #17–#25 (E4)

---

## Epics

---

### Epic A: Project Foundation — Domain Model, Mock Pipeline, Plugin Architecture

**Covers:** 2026-04-06 (early)
**Purpose:** The entire scaffolding: domain records, SC2 interface seams, intent system, SimulatedGame mock, CaseHub integration, QA endpoints, plugin seam interfaces.

#### #27: Domain model, SC2 interface seams, intent system, IntentQueue

**Commits:**
- `feat: project scaffold — Quarkus 3.34.2 + CaseHub, package structure, NATIVE.md`
- `fix: remove non-standard packaging element, update NATIVE.md versions`
- `feat: domain model — GameState, Unit, Building, UnitType, BuildingType, Point2d`
- `fix: defensive list copying in GameState/GameMap compact constructors`
- `feat: SC2 interfaces (SC2Client, GameObserver, CommandDispatcher, ScenarioRunner) and intent system`
- `test: add recentlyDispatched and ring buffer cap tests to IntentQueueTest`

#### #28: SimulatedGame + CaseHub integration + AgentOrchestrator + GameStateTranslator

**Commits:**
- `feat: SimulatedGame — stateful Protoss mock with tick, intent application, enemy spawning`
- `fix: synchronize reset(), snapshot(), and spawnEnemyUnit() for thread safety`
- `feat: ScenarioLibrary with 4 initial Protoss scenarios + MockScenarioRunner`
- `feat: mock SC2 adapter — MockSC2Client, MockGameObserver, MockCommandDispatcher`
- `feat: CaseHub integration — StarCraftCaseFile keys, GameStateTranslator, AgentOrchestrator game loop`
- `feat: mock SC2 adapter + MockStartupBean`
- `feat(mock): auto-start game loop on boot via MockStartupBean`
- `feat(mock): realistic build times in SimulatedGame`

#### #29: QA REST endpoints, plugin seam interfaces, mock pipeline test

**Commits:**
- `feat: plugin seam interfaces (StrategyTask, EconomicsTask, TacticsTask, ScoutingTask) + pass-through dummy implementations`
- `feat: QA REST endpoints — /sc2/casefile, /sc2/frame, /sc2/intents, /sc2/debug/scenario`
- `fix: guard QA endpoints with @UnlessBuildProfile(prod)`
- `feat: full mock pipeline integration test — CaseEngine cycles, all scenarios, 4 dummy plugins verified`
- `feat(plugin): complete immediate plugin work — scouting, tactics, assimilator`
- `feat(plugin): add BasicStrategyTask — gateway opener, Stalker training, strategy assessment`
- `feat(plugin): add BasicEconomicsTask — probe production and pylon supply management`
- `feat(plugin): add active scouting to BasicScoutingTask`
- `feat(agent): add ResourceBudget to prevent cross-plugin double-spend`

---

### Epic B: Real SC2 + Replay Integration

**Covers:** 2026-04-06 (later)
**Purpose:** Live SC2 connection via ocraft, observation translation, replay engine for offline evaluation.

#### #31: Real SC2 profile — ocraft, SC2BotAgent, RealSC2Client, RealGameObserver

**Commits:**
- `feat: add ocraft + fault-tolerance deps; profile-guard mock beans with @UnlessBuildProfile(sc2)`
- `feat: use Instance<SimulatedGame> in AgentOrchestrator for sc2/mock dual-profile compatibility`
- `feat: SC2BotAgent — ocraft S2Agent storing observations atomically and draining IntentQueue each frame`
- `feat: RealGameObserver (reads SC2BotAgent) and RealCommandDispatcher (no-op) for %sc2 profile`
- `feat: RealSC2Client — wraps S2Coordinator, auto-launches SC2, starts game loop in background`
- `fix: auto-start SC2 on startup in %sc2 profile; add POST /sc2/start and /sc2/stop QA endpoints`
- `fix: QA endpoints inject GameObserver instead of SimulatedGame`
- `fix: add build/generate-code goals to quarkus-maven-plugin to enable quarkus:dev`

#### #32: ObservationTranslator — ocraft Observation to GameState

**Commits:**
- `feat: ObservationTranslator — ocraft Observation to GameState with Protoss unit/building type mapping`
- `feat: SC2DebugScenarioRunner — same 4 scenario names triggered via SC2 debug API`
- `docs: add package-info to sc2.real documenting two-loop architecture and %sc2 profile requirement`

#### #33: SC2Engine abstraction + ReplayEngine + ReplaySimulatedGame

**Commits:**
- `refactor(sc2): merge three engine seams into single SC2Engine interface`
- `feat(sc2): add ReplayEngine — observe-only SC2Engine for replay-driven agent evaluation`
- `feat(mock): add ReplaySimulatedGame — replay-driven game state from tracker events`
- `fix(replay): auto-start, null-guard observe(), drop UNKNOWN unit types`
- `feat: add scelight-mpq and scelight-s2protocol dependencies for SC2 replay parsing`
- `docs: add Scelight standalone modules plan — scelight-mpq and scelight-s2protocol extraction`
- `feat: add SC2 replay index — IEM10 Taipei dataset (30 replays, 21 Protoss games)`
- `feat: add AI Arena bot replay dataset (22 parseable games) to replay index`
- `docs: add SC2 engine architecture roadmap`
- `docs: add SC2 engine architecture roadmap`

---

### Epic C: SC2 Emulation Engine — E1, E2, E3

**Covers:** 2026-04-09
**Purpose:** Progressively realistic simulation for offline agent testing: mineral model → movement + waves → full combat.

#### #35: E1 — SC2Data extraction, EmulatedGame mineral model, EmulatedEngine + %emulated profile

**Commits:**
- `refactor(domain): extract SC2Data — shared game constants for SimulatedGame and EmulatedGame`
- `refactor(domain): complete SC2Data extraction — remove magic HP literals and delegation wrappers`
- `feat(emulated): add EmulatedGame with probe-driven mineral harvesting`
- `fix(emulated): track vespene/supply/supplyUsed as live fields in EmulatedGame`
- `feat(emulated): add EmulatedEngine and %emulated Quarkus profile`
- `fix(emulated): document MockStartupBean dual-profile role`
- `fix: update domain/ convention to allow static utility classes`
- `test(emulated): add EmulatedGameTest — 6 tests for probe harvest model`
- `feat(benchmark): add game loop smoke benchmark with per-phase timing and pre-E2 baseline`

#### #36: E2 — Movement, scripted enemy waves, EmulatedConfig live override, full intent handling

**Commits:**
- `feat(domain): add mineral/gas costs to SC2Data for E2 intent handling`
- `feat(emulated): add EnemyWave record for scripted enemy spawning`
- `feat(emulated): add EmulatedConfig with @ConfigProperty defaults and live REST override`
- `feat(emulated): E2 — movement, enemy waves, full intent handling with cost deduction`
- `fix(emulated): add thread safety and supply reservation comments to EmulatedGame`
- `feat(emulated): wire EmulatedConfig into EmulatedEngine — live speed, wave config on join`

#### #37: E3 — Shields, two-pass combat resolution, unit death

**Commits:**
- `feat(domain): add shields/maxShields to Unit record — pervasive compile-driven refactor`
- `feat(domain): add SC2Data combat constants — damagePerTick, attackRange`
- `feat(emulated): E3 combat — shields, damage, simultaneous resolution, unit death`
- `docs(emulated): clarify attackingUnits design decision and fix misleading test comment`
- `docs(emulated): clarify attackingUnits persistence and replay shield limitation`
- `docs(emulated): clarify EmulatedConfig profile scope and TrainIntent unitTag semantics`

---

### Epic D: SC2 Visualizer — PixiJS, WebSocket, Electron, Playwright

**Covers:** 2026-04-09
**Purpose:** Live game visualizer using PixiJS 8 over WebSocket, with Electron for standalone packaging and Playwright for canvas testing.

#### #39: WebSocket broadcast + sprite proxy endpoint

**Commits:**
- `build: add quarkus-websockets-next for live game state visualizer`
- `feat(visualizer): add WebSocket broadcast of GameState each tick`
- `feat(visualizer): add sprite proxy endpoint — serves Liquipedia images, solves WebGL CORS`
- `fix(visualizer): make SpriteProxyResource @ApplicationScoped so image cache persists`
- `feat(visualizer): add PixiJS bundle and HTML shell`

#### #40: PixiJS game scene — grid, sprites, units, buildings, health tinting, config panel

**Commits:**
- `feat(visualizer): add PixiJS visualizer with WebSocket live update and SC2 sprites`
- `docs(visualizer): document grid intentionally covers 32x32 game area only`
- `feat(visualizer): health tinting — sprites shift yellow→red as HP drops`
- `fix(visualizer): circular unit sprites via PixiJS 8 Container mask approach (GE-0144)`
- `feat(visualizer): add live config panel — wave timing, unit type, speed slider`
- `fix(visualizer): apply health tint to both Container and inner Sprite for consistency`

#### #41: Electron wrapper — Quarkus subprocess management for packaged app

**Commits:**
- `feat(electron): add Electron wrapper — dev mode + Quarkus subprocess for packaged mode`
- `fix(electron): log health poll errors, kill Quarkus on startup timeout`

#### #42: Playwright E2E render tests + WebSocket integration tests

**Commits:**
- `fix(visualizer): CopyOnWriteArrayList in ReplayEngine, sprite fetch timeouts, gitignore lock file`
- `fix(visualizer): remove PixiJS 8 mask bug, add WebSocket integration tests`
- `test(visualizer): add Playwright render tests — sprite counts, positions, mask regression, pixel sampling`
- `test(visualizer): verify shields/maxShields present in WebSocket JSON`
- `test(visualizer): E2E combat tests — health tinting, unit disappearance`

---

## Standalones

### #43: FlowEconomicsTask — Quarkus Flow economics workflow

**Covers:** 2026-04-07
**Commits:**
- `feat(deps): add quarkus-flow 0.7.1 and quarkus-messaging`
- `feat(domain): add GameStateTick and EconomicsContext records`
- `feat(lifecycle): add GameStarted/GameStopped events; AgentOrchestrator fires them`
- `feat(economics): TDD EconomicsDecisionService — supply, probes, gas, expansion`
- `feat(plugin): FlowEconomicsTask — CaseHub shim emitting GameStateTick on economics-ticks channel`
- `feat(lifecycle): EconomicsLifecycle logs game events; configure economics-ticks in-memory channel`
- `feat(flow): EconomicsFlow — multi-tick economics workflow processing tick events`
- `test(flow): EconomicsFlowTest — full pipeline integration for economics workflow`
- `refactor(plugin): demote BasicEconomicsTask; move assimilator ownership to FlowEconomicsTask`

### #44: DroolsStrategyTask — first Drools R&D strategy plugin

**Covers:** 2026-04-07
**Commits:**
- `feat(plugin): add DroolsStrategyTask — first R&D integration`

### #45: GitHub issue tracking setup and Work Tracking configuration

**Covers:** 2026-04-07
**Commits:**
- `feat(work-tracking): enable GitHub issue tracking`
- `chore: add .worktrees/ to .gitignore`

### #46: Jekyll site publication — 13 blog posts rendered as GitHub Pages

**Covers:** 2026-04-12
**Commits:**
- `feat(site): publish Jekyll site — 13 blog posts now rendered as pages`
- `fix(site): add highlight.js for syntax colouring on blog posts`
- `fix(site): add json+properties packs; tag application.properties block`

### #47: DESIGN.md consolidation — 8 design snapshots merged into living document

**Covers:** 2026-04-10
**Commits:**
- `docs: consolidate 8 design snapshots into DESIGN.md`
- `docs: apply 8-point review fixes to DESIGN.md`

### #48: QuarkusMind rename

**Covers:** 2026-04-08
**Commits:**
- `refactor: rename project from starcraft to QuarkusMind`

---

## Excluded Commits

| Commit | Reason |
|---|---|
| All `docs: session handover *` | Session management artifact, not a feature |
| All `docs: session wrap *` | Session management artifact |
| All `docs: add blog entry *` | Grouped with feature issues (listed in Notes) |
| All `docs: design snapshot *` | Documentation artifact |
| All `docs(claude): *` | CLAUDE.md maintenance |
| `docs: add SC2 image index *` | Supporting asset, not a feature |
| `docs: add README, plugin-guide, and running reference` | Documentation |
| `docs: add SC2 unit portraits to blog entries` | Blog asset |
| `docs: add screenshots to blog entries` | Blog asset |
| `docs(emulated): clarify *` | Grouped with E3 |
| All `docs: add implementation plan *` | Grouped with feature issues |
| All `docs: add design spec *` | Grouped with feature issues |
| `refactor(blog): add author initials prefix` | Blog maintenance |
| `docs: session handover 2026-04-14` (today) | Session management |
| `docs: E4 design spec — enemy active AI` | Linked to #17 |
| `docs: E4 enemy AI design spec + post-E3 benchmark` | Linked to #17 |
| `docs: E4 enemy AI implementation plan` | Design artifact |
| `docs: add blog entry 2026-04-10 — The Enemy Gets a Brief` | Blog entry |
| `docs: ActionTranslator implementation plan` | Linked to #12 |
| `docs: ActionTranslator design spec` | Linked to #12 |
| `docs: update CaseHub install command` | Linked to #12 |

