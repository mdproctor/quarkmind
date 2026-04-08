# Running the Agent

This document covers all run modes, configuration options, the QA REST API, and available test scenarios.

---

## Prerequisites

Before running for the first time:

```bash
# 1. Install CaseHub to local Maven repo
cd /path/to/casehub && mvn install -DskipTests

# 2. Install Scelight replay parser libs
cd /path/to/scelight && ./scripts/publish-replay-libs.sh

# 3. Build
cd /path/to/quarkmind && mvn compile
```

---

## Run Modes

### Mock mode (default — no SC2 needed)

Drives the agent against a hand-crafted `SimulatedGame`. The game does not auto-start; trigger it manually once the server is up.

```bash
mvn quarkus:dev
```

The game loop starts automatically on boot (`MockStartupBean`). The agent ticks every 500ms against `SimulatedGame`. Use [scenarios](#scenarios) to inject interesting situations.

To stop:

```bash
curl -X POST http://localhost:8080/sc2/stop
```

### Replay mode (no SC2 needed)

Drives the agent against real `.SC2Replay` tracker events. The game auto-starts on boot. The agent observes, runs plugins, and records intents — but intents are **not** applied back to the replay (the replay is immutable).

```bash
mvn quarkus:dev -Dquarkus.profile=replay
```

**Default replay:** `replays/aiarena_protoss/Nothing_4720936.SC2Replay` (8m21s PvZ, Protoss wins)

**Override:**

```bash
mvn quarkus:dev -Dquarkus.profile=replay \
  -Dstarcraft.replay.file=replays/aiarena_protoss/ArgoBot_4721229.SC2Replay \
  -Dstarcraft.replay.player=1
```

`starcraft.replay.player` is the 1-indexed player ID to track as "our" player (default: 1).

See [replays/replay-index.md](../replays/replay-index.md) for all available replays.

### Real SC2 mode

Connects to a live StarCraft II process via ocraft-s2client. SC2 must be installed and the API port accessible.

```bash
mvn quarkus:dev -Dquarkus.profile=sc2
```

SC2 connection config in `application.properties`:

```properties
%sc2.starcraft.sc2.host=127.0.0.1
%sc2.starcraft.sc2.port=8168
%sc2.starcraft.sc2.map=Simple128
%sc2.starcraft.sc2.difficulty=VERY_EASY
```

The game auto-starts on boot. If the connection fails, retry with:

```bash
curl -X POST http://localhost:8080/sc2/start
```

---

## Configuration Reference

| Property | Default | Description |
|---|---|---|
| `starcraft.tick.interval` | `500ms` | How often the agent observes and decides |
| `starcraft.replay.file` | *(required in %replay)* | Path to `.SC2Replay` file |
| `starcraft.replay.player` | `1` | 1-indexed player to track as "our" player |
| `starcraft.sc2.host` | `127.0.0.1` | SC2 API host |
| `starcraft.sc2.port` | `8168` | SC2 API port |
| `starcraft.sc2.map` | `Simple128` | Map name for SC2 games |
| `starcraft.sc2.difficulty` | `VERY_EASY` | AI opponent difficulty |

---

## QA REST API

All endpoints are available in mock, replay, and sc2 profiles. Stripped in prod.

### Game state

#### `GET /sc2/casefile`

Returns the current observed `GameState` as JSON.

```bash
curl http://localhost:8080/sc2/casefile
```

```json
{
  "minerals": 350,
  "vespene": 0,
  "supply": 23,
  "supplyUsed": 18,
  "myUnits": [
    { "tag": "r-226-1", "type": "PROBE", "position": {"x": 127.0, "y": 45.0}, "health": 45, "maxHealth": 45 }
  ],
  "myBuildings": [
    { "tag": "r-225-1", "type": "NEXUS", "position": {"x": 124.0, "y": 48.0}, "health": 1500, "maxHealth": 1500, "isComplete": true }
  ],
  "enemyUnits": [],
  "gameFrame": 42
}
```

#### `GET /sc2/frame`

Returns frame count, connection status, and pending intent count.

```bash
curl http://localhost:8080/sc2/frame
```

```json
{ "gameFrame": 42, "connected": true, "pendingIntents": 0 }
```

### Intents

#### `GET /sc2/intents/pending`

Returns intents queued this tick (not yet dispatched).

```bash
curl http://localhost:8080/sc2/intents/pending
```

#### `GET /sc2/intents/dispatched`

Returns the last 100 dispatched intents (rolling buffer).

```bash
curl http://localhost:8080/sc2/intents/dispatched
```

### Lifecycle

#### `POST /sc2/start`

Connects and starts the game. Required in mock mode; auto-called in replay and sc2 modes.

```bash
curl -X POST http://localhost:8080/sc2/start
```

#### `POST /sc2/stop`

Disconnects and stops the game.

```bash
curl -X POST http://localhost:8080/sc2/stop
```

---

## Scenarios

Named scenarios inject a specific game situation. Available in all run modes via the REST API. In mock mode they mutate `SimulatedGame` directly; in sc2 mode they use the SC2 Debug API.

#### `POST /sc2/debug/scenario/{name}`

```bash
# Spawn enemy Zealots and a Stalker near your base
curl -X POST http://localhost:8080/sc2/debug/scenario/spawn-enemy-attack

# Set minerals to 500
curl -X POST http://localhost:8080/sc2/debug/scenario/set-resources-500

# Push supply close to the cap
curl -X POST http://localhost:8080/sc2/debug/scenario/supply-almost-capped

# Spawn an enemy Probe at the expansion location
curl -X POST http://localhost:8080/sc2/debug/scenario/enemy-expands
```

Returns `204 No Content` on success, `400 Bad Request` for unknown scenario names.

Available scenarios:

| Name | Effect |
|---|---|
| `spawn-enemy-attack` | 2× Zealot + 1× Stalker spawned near player base |
| `set-resources-500` | Minerals set to 500, vespene to 200 |
| `supply-almost-capped` | 8 Probes spawned to push supply near cap |
| `enemy-expands` | Enemy Probe spawned at expansion position |

---

## Testing

```bash
# All tests
mvn test

# Single test class
mvn test -Dtest=BasicStrategyTaskTest -q

# Integration tests only (requires Quarkus boot — slower)
mvn test -Dtest=FullMockPipelineIT
```

Tests run against the `%test` profile — same as mock but with the scheduler disabled. Integration tests call `orchestrator.gameTick()` directly.

---

## Profiles Summary

| Profile | Command flag | Auto-start | Intents applied | QA endpoints |
|---|---|---|---|---|
| `%mock` | *(default)* | No | Yes (SimulatedGame) | Yes |
| `%replay` | `-Dquarkus.profile=replay` | Yes | No (recorded only) | Yes |
| `%sc2` | `-Dquarkus.profile=sc2` | Yes | Yes (real SC2) | Yes |
| `%test` | *(auto in `mvn test`)* | No | Yes (SimulatedGame) | Yes |
| `%prod` | `-Dquarkus.profile=prod` | — | — | No |
