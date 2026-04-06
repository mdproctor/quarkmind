# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Type

**Type:** java

## Repository Purpose

A Quarkus-based StarCraft II agent platform. The primary purpose is R&D — it is a living testbed for Drools, Quarkus Flow, and CaseHub (a Blackboard/CMMN framework). Intelligence is provided by swappable plugins; the platform provides scaffolding, SC2 connection, and the CaseHub control loop.

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
# Then trigger the mock game loop (once, in a second terminal):
curl -X POST http://localhost:8080/sc2/start
```

**Run (real SC2):**
```bash
mvn quarkus:dev -Dquarkus.profile=sc2
```

## Quarkus Profiles

| Profile | SC2 needed | Purpose |
|---|---|---|
| `%mock` (default) | No | Development and unit testing against SimulatedGame |
| `%sc2` | Yes | Real SC2 integration |
| `%test` | No | @QuarkusTest — scheduler disabled |
| `%prod` | — | Production — QA endpoints stripped |

## Testing Patterns

**Unit tests** (no Quarkus, fast):
- Instantiate classes directly via `new` — no CDI
- Tests: `SimulatedGameTest`, `IntentQueueTest`, `MockPipelineTest`, `ScenarioLibraryTest`, `GameStateTranslatorTest`, `GameStateTest`

**Integration tests** (`@QuarkusTest`, full CDI context):
- Use `@Inject` to get beans; scheduler is disabled — call `orchestrator.gameTick()` directly
- Tests: `QaEndpointsTest`, `FullMockPipelineIT`

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
src/main/java/org/acme/starcraft/
  domain/              Plain Java records — no framework deps, always native-safe
  sc2/                 CDI interfaces (SC2Client, GameObserver, CommandDispatcher, ScenarioRunner)
  sc2/intent/          Intent types (BuildIntent, TrainIntent, AttackIntent, MoveIntent)
  sc2/mock/            Mock SC2 implementation — SimulatedGame, MockGameObserver, MockCommandDispatcher
  sc2/mock/scenario/   ScenarioLibrary — living specification of SC2 behaviour
  agent/               CaseHub intelligence layer — StarCraftCaseFile keys, GameStateTranslator, AgentOrchestrator
  agent/plugin/        Plugin seam interfaces (StrategyTask, EconomicsTask, TacticsTask, ScoutingTask)
  plugin/              Default (dummy) plugin implementations — PassThrough*Task
  qa/                  QA REST endpoints — dev/test only (@UnlessBuildProfile("prod"))
```

## Plugin Architecture

Each plugin seam (`StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask`) is a CDI interface extending CaseHub's `TaskDefinition`. Swap an implementation by providing a new `@ApplicationScoped` `@CaseType("starcraft-game")` bean — no wiring changes elsewhere.

Four plugin levels exist (Frame → CaseFile → PlanningStrategy → TaskDefinition). See the design spec for full details.

## CaseHub Dependency

CaseHub (`io.casehub:casehub-core:1.0.0-SNAPSHOT`) must be installed to the local Maven repo before building:

```bash
cd /Users/mdproctor/claude/alpha && mvn install -DskipTests
```

CaseHub will move to GitHub Packages then Maven Central as it matures.

## Replay Library Dependency

The SC2 replay parser (`scelight-mpq` + `scelight-s2protocol`) is built from the Scelight fork:

```bash
cd /Users/mdproctor/claude/scelight && ./scripts/publish-replay-libs.sh
```

Run this when setting up a new environment or after any change to the `feature/standalone-modules` branch. Takes ~10 seconds.

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.

## Key Conventions

- **Domain model** (`domain/`) must remain plain Java — no CDI, no Quarkus imports, no framework dependencies. Records and enums only.
- **SC2 interfaces** (`sc2/`) are contracts only — no implementation logic.
- **QA endpoints** (`qa/`) carry `@UnlessBuildProfile("prod")` — they must never appear in production.
- **`SimulatedGame`** is the living specification of SC2 behaviour. When real SC2 surprises us, update `SimulatedGame` to replicate the quirk and write a test.
- **`StarCraftCaseFile`** holds all CaseFile key constants. Never use raw string keys elsewhere.
- **CaseFile key namespaces:** `game.*` for SC2 observation state, `agent.*` for plugin-written reasoning state.

## Blog Resources

### SC2 Image Index
`docs/sc2-image-index.md` is a living index of SC2 image URLs and assets.

**Check it before searching the web for any SC2 image.** It contains direct Liquipedia URLs for all Protoss units and buildings in our domain model, race icons, wallpaper collection links, and already-downloaded assets in `docs/blog/assets/`.

**When a new image is found** (during blog writing, research, or web fetches) — add it to the index with URL, style notes, and relevance. Keep it growing.

### Replay Index
`replays/replay-index.md` is a living index of SC2 replay datasets for testing and `ReplaySimulatedGame`. Two datasets available: IEM10 Taipei 2016 (30 games, pre-processed JSON) and AI Arena bot replays (29 `.SC2Replay` files, 22 parseable).

**Check it before downloading new replays.** When a new dataset is added, update the index with metadata, labels, and scenario recommendations.
