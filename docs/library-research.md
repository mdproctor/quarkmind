# Library & Ecosystem Research

**Date:** 2026-04-06  
**Purpose:** Comprehensive survey of existing libraries, frameworks, and tools relevant to the StarCraft II Quarkus agent. To be referenced at each phase as we decide what to build vs. reuse.

**Principle: prefer reuse over writing. Always check this document before implementing something from scratch.**

---

## Table of Contents

1. [SC2 Java API & Bot Frameworks](#1-sc2-java-api--bot-frameworks)
2. [Java Agent & AI Frameworks](#2-java-agent--ai-frameworks)
3. [SC2 Map Analysis, Pathfinding & Game Knowledge](#3-sc2-map-analysis-pathfinding--game-knowledge)
4. [Quarkus Extensions for the Agent Platform](#4-quarkus-extensions-for-the-agent-platform)
5. [Native Image Compatibility](#5-native-image-compatibility)
6. [What We Built That Cannot Be Replaced](#6-what-we-built-that-cannot-be-replaced)
7. [Phase-by-Phase Recommendations](#7-phase-by-phase-recommendations)
8. [Full Library Reference Table](#8-full-library-reference-table)

---

## 1. SC2 Java API & Bot Frameworks

### 1.1 ocraft-s2client — The Only JVM Option

| | |
|---|---|
| **GitHub** | https://github.com/ocraft/ocraft-s2client |
| **Maven** | `com.github.ocraft:ocraft-s2client-bot:0.4.21` |
| **Last release** | December 2024 |
| **Stars** | ~57 |
| **License** | MIT |
| **Maintainer** | Single developer (Piotr Picheta) |

This is **the only JVM-native SC2 API client**. The SC2AI wiki and AI Arena both list it as the sole Java option. There are no Kotlin or Scala SC2 bot libraries — they could use ocraft via JVM interop.

**What it provides via ocraft:**
- `S2Agent` with `observation()` and `actions()` APIs
- `Observation` snapshots: unit lists (friendly + enemy), resource counts, build queues
- `StartRaw`: walkability bitmap (`PathingGrid`), buildability bitmap (`PlacementGrid`), terrain height
- `UnitTypeData`: HP, shields, armor, weapons (DPS, range, bonus damage), costs, food
- Replay observer mode (`S2ReplayObserver`) — plays replays through the same event pipeline as live games (requires live SC2 binary)

**Known issues:**
- Blizzard's 10th-anniversary patch introduced `PROTOSS_ASSIMILATOR_RICH` / `ZERG_EXTRACTOR_RICH` unit types that broke enum stability — issue reportedly unresolved
- Single maintainer — risk of patches going unaddressed

**Native image:** 🔴 **Blocker.** RxJava + Netty + Protobuf stack has zero GraalVM reachability metadata. The SC2 protobuf schema has hundreds of message types that all need `reflect-config.json` entries. See [Section 5](#5-native-image-compatibility) for the tracing agent approach.

**Verdict: Stay with it.** It is the only option. Prepare for the native image challenge in advance.

---

### 1.2 Java Reference Bot — Supalosa

| | |
|---|---|
| **Template** | https://github.com/Supalosa/testbot (1 star, minimal Gradle template) |
| **Full bot** | https://github.com/Supalosa/supabot (more complete, not fully documented) |

The only community Java bot worth studying. Very low star count — the Java SC2 ecosystem is roughly 5 years behind Python in tooling maturity.

---

### 1.3 Docker Headless SC2 — For CI Integration Tests

| | |
|---|---|
| **Blizzard official** | https://github.com/Blizzard/s2client-docker |
| **Community** | https://github.com/cpp-sc2/docker-sc2 |

A containerised headless SC2 binary that ocraft can connect to via WebSocket. Enables full pipeline integration tests in CI without a GUI SC2 install.

**How it works:** SC2 binary starts in the container, ocraft connects via WebSocket on port 12000, tests run headlessly. This is how AI Arena itself operates bots.

**Constraints:** ~2GB Docker image, requires agreeing to Blizzard's EULA, substantial RAM requirements. Appropriate as a CI gate layer above the mock-based unit tests — not for fast unit-level feedback.

**Use with:** `@QuarkusTestResource` + Testcontainers to start the container during integration tests.

---

### 1.4 SC2 Replay Parsing — No Java Maven Artifact

The only Java SC2 replay parser is **Scelight** (https://github.com/icza/scelight, ~127 stars, Apache 2.0), but it is embedded in a Swing desktop application — no standalone Maven artifact exists.

**Practical alternative:** Use Python's `sc2reader` (PyPI) or `spawningtool` to parse replays offline and export JSON fixtures consumable by Java tests. This enables realistic test scenarios without hand-crafting them.

Best Python replay tools:
- `sc2reader` (PyPI): https://pypi.org/project/sc2reader/ — highest-level analysis
- `spawningtool` (PyPI): https://pypi.org/project/spawningtool/ — build order extraction from replays
- `s2protocol` (Blizzard, official): https://github.com/Blizzard/s2protocol
- `s2prot` (Go): https://github.com/icza/s2prot — self-contained CLI + library

---

## 2. Java Agent & AI Frameworks

### 2.1 Drools 10.1.0 (Apache KIE) — HIGH PRIORITY

| | |
|---|---|
| **GitHub** | https://github.com/apache/incubator-kie-drools |
| **Maven** | `org.drools:drools-quarkus-ruleunits:10.1.0` |
| **BOM** | `org.drools:drools-bom:10.1.0` |
| **License** | Apache 2.0 |
| **Native image** | ✅ Yes — Executable Model compiles rules at build time (no runtime bytecode gen) |
| **Quarkus** | ✅ Full Quarkus 3.x support |

> ⚠️ **Important:** Drools has moved from Red Hat/JBoss to the Apache Software Foundation as **Apache KIE Incubating**. Use `org.drools:drools-quarkus-ruleunits`, NOT `org.kie:kie-*` (old coordinates). Use **raw Drools, not Kogito** — Kogito adds an orchestration layer we don't need (CaseHub is our orchestrator).

**Key Drools features for SC2:**

- **Forward chaining:** Insert `EnemyUnit(type=MARINE, x=45, y=67)` → rules fire immediately
- **Logical insertion / truth maintenance:** Insert derived facts (e.g. `UnderAttack`) that auto-retract when conditions cease — no manual state cleanup
- **Rule salience/priority:** `flee > focus-fire > patrol` expressed cleanly
- **Stateful sessions:** Persist game state between ticks; only changed facts trigger re-evaluation (RETE)
- **Drools Fusion (CEP):** Temporal operators — `before`, `after`, sliding time windows — for build order timing, enemy timing detection

**Example DRL:**
```drools
rule "Initiate Timing Attack"
  salience 100
when
  $s : GameState(minerals >= 400, gas >= 200, armyStrength > 30)
  not AttackInProgress()
then
  insert(new AttackOrder("TIMING_ATTACK", $s.getEnemyBaseLocation()));
end
```

**Example CEP rule:**
```drools
declare UnitProduced
  @role(event)
  @timestamp(gameTime)
end

rule "Detect 3-Rax Timing"
when
  $r : UnitProduced(unitType == MARINE) over window:time(4m)
  accumulate($r; $count : count($r); $count >= 24)
then
  insert(new ThreatAlert("3RAX_TIMING"));
end
```

**Integration pattern with CaseHub:** Drools is a plugin inside `StrategyTask.execute(CaseFile)`. CaseHub owns the lifecycle; Drools does the reasoning.

```java
// Inside StrategyTask.execute(CaseFile caseFile)
try (KieSession ks = kieContainer.newKieSession()) {
    ks.insert(gameState);
    ks.insert(economicSnapshot);
    ks.fireAllRules();
    List<StrategicDecision> decisions = new ArrayList<>(
        ks.getObjects(StrategicDecision.class));
}
```

**Phase to introduce:** Phase 3 (first real plugin)

---

### 2.2 gdx-ai (libGDX AI Framework) — JVM mode

| | |
|---|---|
| **GitHub** | https://github.com/libgdx/gdx-ai |
| **Maven** | `com.badlogicgames.gdx:gdx-ai:1.8.2` via Sonatype OSS |
| **Repository** | `https://oss.sonatype.org/content/repositories/releases` |
| **Stars** | 1,300+ |
| **License** | Apache 2.0 |
| **Native image** | ⚠️ JVM mode only — no GraalVM reachability metadata |

**What it provides:**
- **A* and Dijkstra pathfinding** on arbitrary graphs — feed it the `PathingGrid` bitmap from ocraft's `StartRaw`
- **Indexed A*** — optimised for large grids (SC2 maps are large)
- **Behavior Trees** — `Selector`, `Sequence`, `Parallel`, `Decorator`, **Dynamic Guard Selector** (re-evaluates guards each tick, enabling state-machine-like behavior)
- **External `.tree` format** — serializable, hot-reloadable behavior trees
- **Steering behaviors** — flocking, seek, flee, arrive — for unit micromanagement

**Important:** Usable without libGDX rendering, but requires `gdx` core JAR as a transitive dependency. Must declare the Sonatype OSS repo in `pom.xml`.

**Use cases in our architecture:**
- `TacticsTask`: Behavior tree for unit micro (attack, retreat, ability use, kite)
- `EconomicsTask`: Behavior tree for build order execution
- Pathfinding in `GameMap` domain model (requires ocraft terrain data)

**Phase to introduce:** Phase 3–4 spike in JVM mode

---

### 2.3 GOAP (Goal-Oriented Action Planning) — Implement Yourself

**Status: CHOSEN — Phase 3 implementation complete.**
Implemented as `DroolsTacticsTask`: Drools fires once per tick to classify unit groups
and emit an action library; Java A* (`GoapPlanner`) finds the cheapest action sequence
per group. See `docs/superpowers/specs/2026-04-08-drools-goap-tactics-design.md`.

gdx-ai (§2.2) deferred — JVM-only dependency, no GraalVM reachability metadata.
Revisit for Phase 4 pathfinding (`GameMap` / `PathingGrid`).

| | |
|---|---|
| **Java libs** | `fdefelici/jgoap`, `ph1387/JavaGOAP` — unmaintained, not on Maven Central |
| **Recommendation** | **Implement directly (~300 LOC)** — the algorithm is small and simple |

**What it is:** Defines WorldState (boolean key-value conditions), Actions (Preconditions + Effects + Cost), and uses A* to find the cheapest action sequence from current state to goal state. Used in F.E.A.R., Halo, The Sims 4.

**SC2 application:** `TacticsTask` — dynamically sequences unit actions:
- WorldState: `{enemyVisible: true, inRange: false, lowHealth: false}`
- Actions: `Move (cost=1)`, `Attack (cost=2, pre=inRange)`, `Retreat (cost=1, pre=lowHealth)`
- Goal: `{enemyDead: true}`

Unlike a fixed behavior tree, GOAP replans when the world changes. Write a clean `GoapPlanner` class inside the `TacticsTask` plugin — it integrates naturally with the CaseHub blackboard (WorldState = CaseFile snapshot).

**Phase to introduce:** Phase 3–4

---

### 2.4 Jadex BDI — JVM-mode Spike Worth Considering

| | |
|---|---|
| **GitHub** | https://github.com/actoron/jadex |
| **Maven** | `org.activecomponents.jadex:jadex-distribution-minimal:4.0.241` (Maven Central) |
| **License** | GPL-3.0 ⚠️ |
| **Native image** | ❌ No GraalVM path |

**What it is:** Annotation-based BDI reasoning. Agents have `@Belief` fields (world model), `@Goal` inner classes (objectives), and `@Plan` methods (how to achieve goals). No XML or separate DSL — pure Java.

**SC2 application:** A Jadex `@BDIAgent` as a `StrategyTask` plugin, with beliefs that track game state and goals that select strategy archetypes (rush, macro, tech switch).

**Blockers:** GPL-3.0 license (check if acceptable to the project), no native image support — JVM mode only.

**Phase to introduce:** Evaluate in Phase 3–4 as an R&D spike

---

### 2.5 BDI Frameworks to Skip

| Framework | Why Skip |
|---|---|
| **Jason** | No Maven Central artifact (Gradle only), reflection-heavy, no GraalVM path |
| **JaCaMo** | Gradle-only, JVM-only, three bundled runtimes — over-engineered |
| **JADE** | Not on Maven Central (tilab repo only), original team retired 2021, architecturally wrong (enterprise multi-agent middleware, not game loop) |
| **Esper CEP** | GPL-2.0, runtime bytecode gen via Janino = GraalVM incompatible; Drools Fusion covers the same use case |
| **Flink CEP** | Distributed cluster — wrong scale for an embedded bot |

---

### 2.6 LangChain4j via Quarkus — LLM-Guided StrategyTask

| | |
|---|---|
| **GitHub** | https://github.com/quarkiverse/quarkus-langchain4j |
| **Maven (Ollama)** | `io.quarkiverse.langchain4j:quarkus-langchain4j-ollama:1.8.4` |
| **Maven (OpenAI)** | `io.quarkiverse.langchain4j:quarkus-langchain4j-openai:1.8.4` |
| **Native image** | ✅ Native compatible (build-time wiring) |

**Concept:** An `@AiService`-annotated interface as a `StrategyTask` that takes a serialized game observation and returns a strategic directive as plain text or a structured response. Ollama with a local model (Llama 3, Mistral) avoids network dependency during live games.

**Why interesting:** Lets you use an LLM as one of the interchangeable plugin implementations — directly tests how well current LLMs can reason about game state. The MCP support added recently means you could expose game state as an MCP tool.

**Phase to introduce:** Experimental spike, Phase 4+

---

## 3. SC2 Map Analysis, Pathfinding & Game Knowledge

### 3.1 What ocraft Already Provides (Use This First)

Via `observation().getGameInfo().getStartRaw()` at game start:

| Data | API | Format |
|---|---|---|
| Walkable tiles | `getPathingGrid()` | `ImageData` — bit-encoded bitmap |
| Buildable tiles | `getPlacementGrid()` | `ImageData` — bit-encoded bitmap |
| Terrain height | `getTerrainHeight()` | `ImageData` — byte-encoded |
| Start locations | `getStartLocations()` | `List<Point2d>` |

Decode: `index = x + y * width; bit = (data[index/8] >> (7 - index%8)) & 1`

Via `observation().getUnitTypeData(false)` at game start (authoritative, reflects current patch):
- HP, shields, armor, weapons (DPS, range, attack type, bonus damage by attribute)
- Mineral/gas cost, food used/provided, build time, tech requirements

**No built-in chokepoint detection, ramp finding, or expansion scoring in ocraft — must be implemented on top.**

---

### 3.2 SC2MapAnalysis — Python Sidecar for Terrain Intelligence

| | |
|---|---|
| **GitHub** | https://github.com/spudde123/SC2MapAnalysis (maintained fork) |
| **Language** | Python |
| **Integration** | FastAPI microservice (localhost REST) |

The leading SC2 map analysis library. Features: influence maps, pathfinding, ramp/choke detection, expansion location enumeration, vision blockers, region segmentation.

**Java integration pattern:** Wrap in a FastAPI microservice, call once at game start from `GameStateTranslator` or `AgentOrchestrator`. Results cached — one-time startup cost per game.

**What to get from it:**
- Expansion locations (ranked by distance from start)
- Ramp positions (natural choke points)
- Region boundaries (for influence map plugins)

**Phase to introduce:** Phase 3+

---

### 3.3 Pathfinding for Java

**gdx-ai** (see Section 2.2) is the recommended Java pathfinding library. Feed it the walkability bitmap from ocraft `PathingGrid`.

No SC2-specific Java pathfinding library exists. Jump Point Search (JPS) — faster than A* on uniform grids like SC2 maps — has no off-the-shelf Java Maven artifact. Worth implementing for Phase 3+.

Alternative: **sc2-pathlib** (Rust/Python) via the Python sidecar pattern.

---

### 3.4 Combat Simulation

**No maintained JVM combat simulator for SC2 exists.** Options:

| Option | Notes |
|---|---|
| **Custom Java estimator** | Use `observation().getUnitTypeData()` for unit stats. Basic "sum DPS / EHP" fight/flee logic is fast and adequate for most decisions. Start here. |
| **sc2-libvoxelbot** (C++) | https://github.com/HalfVoxel/sc2-libvoxelbot — most accurate SC2 combat simulator, accounts for Immortal/Colossus damage bonuses, shield battery healing, etc. Call via `ProcessBuilder` as a subprocess with JSON I/O. High effort, high accuracy. |
| **JarCraft / JavaBWAPI/ASS** | SC1 only — not applicable |

**Phase to introduce:** Custom estimator in Phase 2–3; sc2-libvoxelbot subprocess in Phase 4+

---

### 3.5 Build Order Databases

**No structured, machine-readable, freely queryable build order database with a public API exists.**

Best available options:
- **Spawning Tool** (https://lotv.spawningtool.com/): largest database, no documented REST API. Use `spawningtool` Python package to parse replays offline.
- **Hardcode templates**: Small library of Protoss build orders as Java records or YAML files bundled with the bot. Most practical for Phase 2.

---

## 4. Quarkus Extensions for the Agent Platform

### 4.1 SmallRye Fault Tolerance — Add Before Phase 1

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-fault-tolerance</artifactId>
</dependency>
```

| | |
|---|---|
| **Native image** | ✅ Stable |
| **Version** | 3.32.4 (March 2026) |

SC2 connection failures are inevitable (game restarts, map transitions, crashes). Apply `@CircuitBreaker` + `@Retry` + `@Fallback` to the ocraft connection initialization. Zero architecture change — wraps existing `StarCraftClient.connect()` and `joinGame()` methods.

**Priority: Add before Phase 1 (real SC2 integration)**

---

### 4.2 Micrometer + Prometheus — Add Now

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>
```

| | |
|---|---|
| **Native image** | ✅ Stable |
| **Metrics endpoint** | `GET /q/metrics` |

Custom metrics for the SC2 agent:

```java
Counter frameCounter = registry.counter("sc2.frames.processed");
Timer strategyTimer  = registry.timer("sc2.strategy.decision.time");
Gauge supplyGauge    = Gauge.builder("sc2.supply.used", ...).register(registry);
Counter winCounter   = registry.counter("sc2.games.won");
Counter lossCounter  = registry.counter("sc2.games.lost");
```

No existing SC2 bot Grafana dashboards. Extend [JVM Quarkus Micrometer dashboard (ID: 14370)](https://grafana.com/grafana/dashboards/14370-jvm-quarkus-micrometer-metrics/) with custom panels.

**Priority: Add now — needed for R&D feedback**

---

### 4.3 OpenTelemetry Tracing — Add Now

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-opentelemetry</artifactId>
</dependency>
```

| | |
|---|---|
| **Tracing** | ✅ Stable in native |
| **Metrics** | ⚠️ Tech preview (opt-in) |
| **Logging** | ⚠️ Tech preview (opt-in) |

Each game frame becomes a root span. Child spans per CaseHub task execution reveal exactly where the decision pipeline is slow. Coexists with Micrometer — they address different observability needs.

**Priority: Add now alongside Micrometer**

---

### 4.4 Quarkus Flow — Complement to CaseHub

| | |
|---|---|
| **GitHub** | https://github.com/quarkiverse/quarkus-flow |
| **Docs** | https://docs.quarkiverse.io/quarkus-flow/dev/index.html |
| **Released** | October 2025 (Quarkiverse) |
| **Native image** | ✅ Compatible |
| **Based on** | CNCF Serverless Workflow Specification |

**Key features:**
- Java DSL with agentic AI support (`@SequenceAgent`, `@ParallelAgent`)
- LangChain4j integration built in
- SmallRye Messaging bridge for CloudEvents over Kafka
- Built-in Micrometer monitoring for workflow instances
- Mermaid.js diagram auto-generation in Dev UI

**Relationship to CaseHub:** CaseHub (CMMN) handles real-time in-game reactive decisions. Quarkus Flow (Serverless Workflow) could handle the match lifecycle: game start → loop → game end → post-game analysis. The two are complementary, not competing. This is the primary R&D integration target for Quarkus Flow within this project.

**Priority: Evaluate in Phase 3+**

---

### 4.5 SmallRye Reactive Messaging — Potential Game Loop Upgrade

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-messaging</artifactId>
</dependency>
```

| | |
|---|---|
| **Native image** | ✅ Full support |
| **Note** | Renamed from `quarkus-smallrye-reactive-messaging` in Quarkus 3.9 |

**Concept:** Replace the `@Scheduled` game tick with a backpressure-aware reactive stream. A `Multi.createFrom().ticks().every(...)` source, with downstream `@Incoming` handlers per plugin. Benefits:
- Stage-level observability through named channels
- Natural backpressure when frame processing exceeds tick interval
- Fan-out game state to multiple concurrent consumers

**Caveat:** More complex than `@Scheduled` + `SKIP`. Validate whether backpressure semantics fit the game loop before switching.

**Priority: Spike in Phase 3–4**

---

### 4.6 WebSockets Next — Live Dashboard

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-websockets-next</artifactId>
</dependency>
```

| | |
|---|---|
| **Native image** | ⚠️ Preview status — use `quarkus-websockets` (legacy) for native builds |

Push game state (CaseFile snapshot, unit counts, resource curve) to a browser dashboard in real time. `@WebSocket` with server-initiated broadcasts over `/ws/gamestate`.

**Priority: Phase 4+ (nice to have for demos and debugging)**

---

### 4.7 Temporal for Quarkus — Defer

```xml
<dependency>
    <groupId>io.quarkiverse.temporal</groupId>
    <artifactId>quarkus-temporal</artifactId>
</dependency>
```

| | |
|---|---|
| **Native image** | ✅ Via `quarkus-temporal-native-runtime` |
| **First release** | August 2024 |

Durable execution with replay semantics, built-in retry, cron scheduling. Requires a Temporal server. Appropriate for durable post-game analysis or ladder scheduling — **not** for in-game real-time decisions (too much RPC latency).

**Priority: Defer until durable workflow guarantees become a requirement**

---

## 5. Native Image Compatibility

### 5.1 ocraft-s2client — The Critical Blocker

ocraft-s2client's dependency chain creates three separate native image challenges:

| Dependency | Native Challenge |
|---|---|
| **Protobuf** | Every `.proto`-generated SC2 API class needs `reflect-config.json` entry. The SC2 schema has hundreds of message types. |
| **Netty** | Mostly ships its own metadata, but shaded Netty variants and `PlatformDependent0` have known issues. |
| **RxJava 3** | No GraalVM reachability metadata contributed to the community repo. |

**The only practical approach: GraalVM Native Image Tracing Agent**

```bash
# Run during a real game session to capture all reflection paths
$JAVA_HOME/bin/java \
  -agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image \
  -jar target/sc2agent-runner.jar

# Automate via Maven native plugin during test phase
```

Run across multiple game scenarios (different maps, races, opponents) using `config-merge-dir` to accumulate coverage. Commit the generated configs under `src/main/resources/META-INF/native-image/org.acme/starcraft/`.

**When to do this:** Phase 1 (when real SC2 is first connected). The ocraft native work is a dedicated task in the Phase 1 plan.

### 5.2 Native Compatibility Table

| Dependency | Version | Native Status | Notes |
|---|---|---|---|
| `ocraft-s2client-bot` | 0.4.21 | 🔴 Blocker | Needs tracing agent run against live SC2 |
| `casehub-core` | 1.0.0-SNAPSHOT | 🔲 Unverified | Verify before native build |
| `drools-quarkus-ruleunits` | 10.1.0 | ✅ Supported | Executable Model = AOT rule compilation |
| `gdx-ai` | 1.8.2 | ⚠️ JVM only | No GraalVM reachability metadata |
| `quarkus-smallrye-fault-tolerance` | 3.34.x | ✅ Stable | |
| `quarkus-opentelemetry` (tracing) | 3.34.x | ✅ Stable | Metrics = tech preview |
| `quarkus-micrometer-registry-prometheus` | 3.34.x | ✅ Stable | |
| `quarkus-langchain4j-ollama` | 1.8.4 | ✅ Native | Build-time service wiring |
| `quarkus-websockets-next` | 3.34.x | ⚠️ Preview | Use `quarkus-websockets` for native |
| `quarkus-temporal` | latest | ✅ Via native runtime artifact | Requires Temporal server |

---

## 6. What We Built That Cannot Be Replaced

### 6.1 SimulatedGame / MockGameObserver / MockCommandDispatcher

**No pure-JVM offline SC2 state simulator exists anywhere in the ecosystem.** Every alternative (ocraft replay mode, Python simulators, C++ simulators) requires a live SC2 binary. Our mock stack fills a genuine gap — it is the only way to have fast, deterministic, SC2-free unit tests for the intelligence layer.

The living-specification principle (updating `SimulatedGame` when real SC2 surprises us) is unique to our architecture.

### 6.2 Four-Level Plugin Architecture

No SC2 bot framework — in any language — provides this level of composable plugin hooks:
- Level 1: Frame Observer (raw SC2 frame)
- Level 2: CaseFile Reactor (blackboard change events)
- Level 3: PlanningStrategy (CaseEngine control plane)
- Level 4: TaskDefinition / Worker (data-driven execution)

This is novel and enables swapping between Drools, Quarkus Flow, LangChain4j, GOAP, and Behavior Trees as interchangeable plugin implementations within the same running system.

### 6.3 ScenarioLibrary

The named scenario approach (`spawn-enemy-attack`, `supply-almost-capped`, etc.) has no equivalent in the SC2 testing ecosystem. The dual-runner design (mock + SC2 Debug API) is our own.

---

## 7. Phase-by-Phase Recommendations

### Phase 1 — SC2 Bootstrap

- Add `quarkus-smallrye-fault-tolerance` before connecting real SC2
- Run GraalVM native image tracing agent during first real SC2 session
- Add `Blizzard/s2client-docker` CI container for integration tests
- Implement `SC2DebugScenarioRunner` using ocraft's debug API

### Phase 2 — Survive

- Add `quarkus-micrometer-registry-prometheus` and `quarkus-opentelemetry`
- Use `observation().getUnitTypeData()` for authoritative unit stats — no hardcoded values
- Decode ocraft `PathingGrid` into a `boolean[][]` for future pathfinding
- Implement basic combat estimator (DPS sum / EHP) for fight/flee in `TacticsTask`

### Phase 3 — Fight (First Real Plugin)

- **Drools 10.1.0** as the first real `StrategyTask` implementation
  - Use `drools-quarkus-ruleunits`, not Kogito
  - Include Drools Fusion for timing-attack CEP rules
- **gdx-ai** spike for pathfinding + behavior trees (JVM mode)
- **GOAP planner** (~300 LOC) inside `TacticsTask`
- **Python SC2MapAnalysis sidecar** (FastAPI) for terrain analysis (expansion locations, choke points)

### Phase 4+ — Evolve

- **Quarkus Flow** integration for match-lifecycle orchestration
- **Jadex BDI** spike (JVM mode, check GPL-3.0 license)
- **LangChain4j Ollama** as an experimental `StrategyTask`
- **sc2-libvoxelbot** subprocess for accurate combat simulation
- **Docker headless SC2** in CI for acceptance tests
- **Reactive Messaging** game loop upgrade (backpressure-aware)
- **WebSockets Next** live dashboard

---

## 8. Full Library Reference Table

| Library | Coordinates | License | Native | Phase | Priority |
|---|---|---|---|---|---|
| ocraft-s2client-bot | `com.github.ocraft:ocraft-s2client-bot:0.4.21` | MIT | 🔴 | Now | Required |
| drools-quarkus-ruleunits | `org.drools:drools-quarkus-ruleunits:10.1.0` | Apache 2.0 | ✅ | 3 | High |
| quarkus-smallrye-fault-tolerance | `io.quarkus:quarkus-smallrye-fault-tolerance` | Apache 2.0 | ✅ | 1 | High |
| quarkus-micrometer-registry-prometheus | `io.quarkus:quarkus-micrometer-registry-prometheus` | Apache 2.0 | ✅ | 2 | High |
| quarkus-opentelemetry | `io.quarkus:quarkus-opentelemetry` | Apache 2.0 | ✅ | 2 | High |
| gdx-ai | `com.badlogicgames.gdx:gdx-ai:1.8.2` | Apache 2.0 | ⚠️ | 3 | Medium |
| quarkus-langchain4j-ollama | `io.quarkiverse.langchain4j:quarkus-langchain4j-ollama:1.8.4` | Apache 2.0 | ✅ | 4+ | Medium |
| quarkus-websockets-next | `io.quarkus:quarkus-websockets-next` | Apache 2.0 | ⚠️ | 4+ | Low |
| quarkus-temporal | `io.quarkiverse.temporal:quarkus-temporal` | Apache 2.0 | ✅ | Defer | Low |
| Jadex | `org.activecomponents.jadex:jadex-distribution-minimal:4.0.241` | GPL-3.0 ⚠️ | ❌ | 4+ | Research |
| Jason | Gradle only, no Maven Central | LGPL-3.0 | ❌ | — | Skip |
| JADE | Not on Maven Central | LGPL | ❌ | — | Skip |
| Esper CEP | `com.espertech:esper-runtime:9.0.0` | GPL-2.0 ⚠️ | ❌ | — | Skip |
| sc2-libvoxelbot | C++ binary, subprocess only | MIT | N/A | 4+ | Research |
| Python SC2MapAnalysis | PyPI (sidecar) | MIT | N/A | 3 | Medium |
| Blizzard s2client-docker | Docker image | EULA | N/A | 1 | CI only |

---

*Last updated: 2026-04-06. Update this document when new libraries are evaluated or when the status of any entry changes.*
