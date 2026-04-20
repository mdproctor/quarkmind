# E13 — Scouting CEP Calibration Design

**Date:** 2026-04-20  
**Issue:** #16 — Calibrate scouting CEP thresholds against IEM10 / AI Arena replay data  
**Status:** Approved

---

## Overview

The `DroolsScoutingTask` build-order detection thresholds were set as R&D estimates. This
epic calibrates them against real replay data by building a complete multi-format replay
runner and a data-collection harness that produces the evidence table for threshold decisions.

---

## Components

### 1. `UnitType` Enum Extension

Extend the existing enum in `domain/` with full Terran and Zerg rosters. Plain Java — no
framework imports, no CDI. Existing Protoss values unchanged.

**Terran additions:** `MARINE`, `MARAUDER`, `MEDIVAC`, `SIEGE_TANK`, `THOR`, `VIKING`,
`GHOST`, `RAVEN`, `BANSHEE`, `BATTLECRUISER`

**Zerg additions:** `ZERGLING`, `ROACH`, `HYDRALISK`, `MUTALISK`, `ULTRALISK`,
`BROOD_LORD`, `CORRUPTOR`, `INFESTOR`, `SWARM_HOST`, `VIPER`, `QUEEN`, `RAVAGER`

`toUnitType()` mappings are updated in both `ReplaySimulatedGame` and the new
`IEM10JsonSimulatedGame`.

---

### 2. `ReplaySimulatedGame` — `toUnitType()` Extension

Add Terran and Zerg cases to the existing switch in `ReplaySimulatedGame`. No structural
change — switch cases only.

---

### 3. `IEM10JsonSimulatedGame extends SimulatedGame`

**Location:** `src/main/java/io/quarkmind/sc2/mock/IEM10JsonSimulatedGame.java`

Mirrors `ReplaySimulatedGame` in contract and tick model (22 loops/tick, `applyIntent()`
no-op) but reads SC2EGSet pre-processed JSON instead of binary `.SC2Replay` files.

**Construction:**
```java
// Constructed from pre-parsed JSON bytes (loaded by the enumeration factory)
// watchedPlayerId auto-detected from ToonPlayerDescMap (first Protoss player; PvP → player 1)
new IEM10JsonSimulatedGame(byte[] jsonBytes)
```

Static factory for the calibration harness:
```java
// Streams outer ZIP → inner *_data.zip → *.SC2Replay.json, returns one instance per replay
List<IEM10JsonSimulatedGame> games = IEM10JsonSimulatedGame.enumerate(Path outerZip);
```

Each instance also exposes:
```java
String matchup()   // "PvZ", "PvT", "PvP" — derived from ToonPlayerDescMap races
String replayName() // "<hash>.SC2Replay.json" for logging
```

**Nested ZIP reading:** `ZipInputStream` on outer ZIP → scan for `*_data.zip` entries →
nested `ZipInputStream` → scan for `*.SC2Replay.json`. Jackson reads the stream directly
— no temp file extraction.

**Player auto-detection:** `ToonPlayerDescMap` entries have `m_race` (`"Prot"`, `"Terr"`,
`"Zerg"`). Constructor picks `watchedPlayerId` as the first Protoss player found. For PvP
games (both Protoss) defaults to player 1.

**Event mapping:**

| JSON `_event` | Fields used |
|---|---|
| `NNet.Replay.Tracker.SUnitBornEvent` | `m_unitTypeName`, `m_controlPlayerId`, `m_unitTagIndex`, `m_unitTagRecycle`, `m_x`, `m_y` |
| `NNet.Replay.Tracker.SPlayerStatsEvent` | `m_playerId`, `m_stats.m_scoreValueMineralsCurrent`, `m_stats.m_scoreValueVespeneCurrent`, `m_stats.m_scoreValueFoodUsed`, `m_stats.m_scoreValueFoodMade` |
| `NNet.Replay.Tracker.SUnitDiedEvent` | `m_unitTagIndex`, `m_unitTagRecycle` |
| `NNet.Replay.Tracker.SUnitInitEvent` | same as born |
| `NNet.Replay.Tracker.SUnitDoneEvent` | `m_unitTagIndex`, `m_unitTagRecycle` |

Food values in stats events are fixed-point ×4096 (same as Scelight binary).

`isComplete()` returns true when all events have been drained.

---

### 4. `ScoutingCalibrationTest @Tag("benchmark")`

**Location:** `src/test/java/io/quarkmind/plugin/scouting/ScoutingCalibrationTest.java`

Run via `mvn test -Pbenchmark`. Excluded from default surefire run.

**Per replay:**
1. Construct the appropriate game (`ReplaySimulatedGame` for AI Arena `.SC2Replay`,
   `IEM10JsonSimulatedGame` for IEM10 JSON)
2. Tick to the **3-minute mark** (183 ticks × 22 loops = 4026 loops)
3. Read `gameState.enemyUnits()`, tally by `UnitType`
4. Classify matchup: PvZ, PvT, PvP (from watched player race vs enemy race)

**Output format:**
```
=== Scouting CEP Calibration — enemy unit counts at 3-min mark ===

PvZ (N games): Zerg enemies
  ROACH      min=X  max=Y  mean=Z.Z   ← ZERG_ROACH_RUSH threshold (current: 6)
  ZERGLING   min=X  max=Y  mean=Z.Z

PvT (N games): Terran enemies
  MARINE     min=X  max=Y  mean=Z.Z   ← TERRAN_3RAX threshold (current: 12)

PvP (N games): Protoss enemies
  STALKER    min=X  max=Y  mean=Z.Z
  ZEALOT     min=X  max=Y  mean=Z.Z
  combined   min=X  max=Y  mean=Z.Z   ← PROTOSS_4GATE threshold (current: 8)
```

---

### 5. DRL + Test Updates (post-data)

After running the harness and reading the output table:

- Update thresholds in `DroolsScoutingTask.drl`
- Add a comment block above each rule:

```
// Calibration: IEM10 + AI Arena replays (N games)
// threshold=X | observed mean=Y | observed range=[min, max]
```

- Update `DroolsScoutingRulesTest` assertions to match new thresholds
- Verify `DroolsScoutingTaskIT` still passes

---

## Data Sources

| Dataset | Format | Games | Matchups available |
|---|---|---|---|
| AI Arena bot replays | Binary `.SC2Replay` | 22 parseable | PvP only |
| IEM10 Taipei 2016 | SC2EGSet JSON (nested ZIP) | 30 | PvZ, PvT, PvP |

IEM10 path: `replays/2016_IEM_10_Taipei.zip`  
AI Arena path: `replays/aiarena_protoss/*.SC2Replay`

---

## Acceptance Criteria

- [ ] `UnitType` contains full Terran + Zerg rosters
- [ ] `IEM10JsonSimulatedGame` reads nested ZIP without temp file extraction
- [ ] `ScoutingCalibrationTest` runs all 22 AI Arena + all IEM10 replays and prints statistics table
- [ ] DRL thresholds updated with data-backed values; summary comment in DRL
- [ ] `DroolsScoutingRulesTest` reflects new thresholds
- [ ] No regression in `DroolsScoutingTaskIT`
- [ ] All tests pass (`mvn test`)

---

## Out of Scope

- IEM10 replay data for game-loop-accurate combat simulation (unit positions are
  approximate in tracker events)
- Persistent storage of calibration results (printed table is the artifact)
- Any changes to `ScoutingSessionManager` or the CEP window logic
