# StarCraft II Quarkus Agent

A Quarkus application that plays StarCraft II (Protoss) as a **plugin platform**.

The agent provides scaffolding, SC2 connection, and a [CaseHub](https://github.com/casehub) blackboard control loop. Intelligence is provided by swappable plugins behind CDI seams — swap a plugin by dropping in a new `@ApplicationScoped` bean, no wiring changes needed.

Primary purpose is R&D: a living testbed for [Drools](https://www.drools.org/), Quarkus Flow, and CaseHub (a Blackboard/CMMN framework).

---

## Quick Start

### Prerequisites

- Java 21+, Maven 3.9+
- CaseHub installed to local Maven repo:
  ```bash
  cd /path/to/casehub && mvn install -DskipTests
  ```
- Scelight replay libs installed:
  ```bash
  cd /path/to/scelight && ./scripts/publish-replay-libs.sh
  ```

### Run (no SC2 needed — mock mode)

```bash
mvn quarkus:dev
```

Then start the game loop in a second terminal:

```bash
curl -X POST http://localhost:8080/sc2/start
```

### Run against a real replay (no SC2 needed)

```bash
mvn quarkus:dev -Dquarkus.profile=replay
# Defaults to replays/aiarena_protoss/Nothing_4720936.SC2Replay
# Override: add -Dstarcraft.replay.file=replays/aiarena_protoss/ArgoBot_4721229.SC2Replay
```

The agent auto-starts and ticks every 500ms through the replay. Query it while it runs — see [Observe the Agent](#observe-the-agent) below.

### Run against real StarCraft II

```bash
mvn quarkus:dev -Dquarkus.profile=sc2
```

---

## Observe the Agent

While running, query the agent state via the QA REST API (available in all non-prod profiles):

```bash
# Current game state — minerals, supply, units, buildings
curl http://localhost:8080/sc2/casefile

# What the agent decided this tick
curl http://localhost:8080/sc2/intents/dispatched

# Pending intents in the queue
curl http://localhost:8080/sc2/intents/pending

# Frame counter and connection status
curl http://localhost:8080/sc2/frame

# Trigger a named test scenario
curl -X POST http://localhost:8080/sc2/debug/scenario/spawn-enemy-attack
curl -X POST http://localhost:8080/sc2/debug/scenario/set-resources-500
curl -X POST http://localhost:8080/sc2/debug/scenario/supply-almost-capped
```

---

## How Plugins Work

Each concern is a CDI interface extending CaseHub's `TaskDefinition`:

| Seam | Interface | Current implementation |
|---|---|---|
| Economics | `EconomicsTask` | `BasicEconomicsTask` — probe production + pylon supply |
| Strategy | `StrategyTask` | `BasicStrategyTask` — gateway opener, Stalker training, strategy assessment |
| Tactics | `TacticsTask` | `PassThroughTacticsTask` — stub |
| Scouting | `ScoutingTask` | `PassThroughScoutingTask` — stub |

To replace a plugin, implement the interface and annotate it — the platform picks it up automatically:

```java
@ApplicationScoped
@CaseType("starcraft-game")
public class MyStrategyTask implements StrategyTask {
    @Override public String getId() { return "strategy.mine"; }
    // read from CaseFile, write intents to IntentQueue
}
```

See **[docs/plugin-guide.md](docs/plugin-guide.md)** for the full plugin developer guide.

---

## Build and Test

```bash
mvn compile     # build
mvn test        # run all tests
```

---

## Running Modes

| Mode | Command | SC2 needed | Notes |
|---|---|---|---|
| Mock | `mvn quarkus:dev` | No | Hand-crafted simulation; POST /sc2/start to begin |
| Replay | `mvn quarkus:dev -Dquarkus.profile=replay` | No | Real replay data; intents recorded, not applied |
| Real SC2 | `mvn quarkus:dev -Dquarkus.profile=sc2` | Yes | Full closed loop via ocraft-s2client |

---

## Documentation

| Document | Purpose |
|---|---|
| [docs/DESIGN.md](docs/DESIGN.md) | Architecture, domain model, component structure |
| [docs/plugin-guide.md](docs/plugin-guide.md) | Writing and deploying plugins |
| [docs/running.md](docs/running.md) | All run modes, REST API reference, scenarios |
| [docs/roadmap-sc2-engine.md](docs/roadmap-sc2-engine.md) | Engine roadmap (replay, network bridge, emulation) |
| [replays/replay-index.md](replays/replay-index.md) | Available replay datasets |
