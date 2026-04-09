# SC2 Emulation Engine — Phase E1: Scaffold + Probe Harvesting
**Date:** 2026-04-09
**Epic:** TBD — SC2 Emulation Engine
**Supersedes:** *(none)*

---

## Context

Without a live SC2 binary available for development, the existing `MockEngine` /
`SimulatedGame` pair is the only way to exercise the full agent loop. `SimulatedGame`
serves as a scripted test oracle — deterministic, lightweight, never changing its
contract — but its mineral model is a flat +5/tick regardless of probe count. That is
too crude to validate economics or strategy decisions against realistic game conditions.

The emulation engine is a separate physics simulation that grows incrementally through
phases (E1 → E6), each producing a demonstrable QuarkMind behaviour. Phase E1 creates
the infrastructure and implements the first real mechanic: probe-driven mineral harvesting.

---

## Scope (Phase E1 only)

**In scope:**
- Extract shared game constants into `SC2Data` (domain layer)
- `EmulatedGame` — physics engine skeleton with probe harvest
- `EmulatedEngine` — `SC2Engine` implementation on `%emulated` profile
- `EmulatedGameTest` — plain JUnit coverage of harvest model

**Explicitly out of scope:**
- Cost deduction on intent execution
- Gas / assimilators
- Supply tracking
- Enemy units or AI
- Combat
- Unit movement

---

## Architecture

### New files

```
src/main/java/io/quarkmind/
  domain/SC2Data.java             — shared constants, pure Java, no CDI
  sc2/emulated/EmulatedGame.java  — physics engine (Phase E1: harvest only)
  sc2/emulated/EmulatedEngine.java — SC2Engine impl, %emulated profile

src/test/java/io/quarkmind/
  sc2/emulated/EmulatedGameTest.java
```

### `domain/SC2Data`

Pure Java class — no CDI, no framework dependencies. Single source of truth for all
SC2 game constants used by both `SimulatedGame` and `EmulatedGame`.

Phase E1 contents (extracted from `SimulatedGame`'s private switch tables):
- `trainTimeInTicks(UnitType)` — build time per unit type
- `buildTimeInTicks(BuildingType)` — build time per building type
- `supplyCost(UnitType)` — supply consumed per unit
- `supplyBonus(BuildingType)` — supply provided per building
- `maxHealth(UnitType)` — base HP per unit type
- `maxBuildingHealth(BuildingType)` — base HP per building type
- `MINERALS_PER_PROBE_PER_TICK` — harvest rate constant (see below)
- `INITIAL_MINERALS` — 50
- `INITIAL_PROBES` — 12

`SimulatedGame` delegates to `SC2Data` for all of the above. No behaviour change;
no test changes.

### Probe Harvest Model

Real SC2 at Faster speed runs at 22.4 game loops/second. A single probe harvesting
from a mineral patch generates approximately 50 minerals/minute under optimal
conditions (two probes per patch). This gives:

```
MINERALS_PER_PROBE_PER_TICK = 50.0 / 60.0 / 22.4 ≈ 0.0372
```

`EmulatedGame` state:
- `int miningProbes` — initialised to 12 (all probes on minerals at game start)
- `double mineralAccumulator` — accumulates fractional minerals per tick

Each `tick()`:
```java
mineralAccumulator += miningProbes * SC2Data.MINERALS_PER_PROBE_PER_TICK;
```

`snapshot()` exposes `(int) mineralAccumulator` as the minerals field. The
accumulator is never reset — truncation happens only at snapshot time, preserving
sub-mineral precision across ticks.

### `EmulatedGame`

Package: `io.quarkmind.sc2.emulated`. Not a CDI bean — owned by `EmulatedEngine`.
Does not extend `SimulatedGame`. No inheritance relationship between the two.

Phase E1 responsibilities:
- `reset()` — initialise from `SC2Data` constants (12 probes, nexus, 50 minerals)
- `tick()` — advance mineralAccumulator
- `snapshot()` — return immutable `GameState`
- `applyIntent(Intent)` — no-op stub; logged but not applied

Initial game state mirrors `SimulatedGame.reset()`:
- 12 probes at positions near nexus
- 1 nexus at (8, 8)
- 2 geysers at standard positions (inactive until Phase E2+)
- 50 minerals, 0 gas, supply 15, supplyUsed 12

### `EmulatedEngine`

Package: `io.quarkmind.sc2.emulated`. Implements `SC2Engine`. CDI bean active on
`%emulated` profile via `@IfBuildProfile("emulated")`.

Mirrors `MockEngine` in structure:
```java
connect()    — sets connected = true
joinGame()   — calls game.reset()
leaveGame()  — sets connected = false
isConnected() — returns connected
tick()       — delegates to game.tick()
observe()    — calls game.snapshot(), notifies frameListeners
dispatch()   — drains IntentQueue, calls game.applyIntent() (no-op in E1)
addFrameListener() — adds to listener list
```

### Quarkus Profile

New profile `%emulated` in `application.properties`. Run with:
```
mvn quarkus:dev -Dquarkus.profile=emulated
```

**CDI guard changes required on existing classes:**

| Class | Current guard | Change |
|---|---|---|
| `MockEngine` | `@UnlessBuildProfile(anyOf = {"sc2", "replay"})` | Add `"emulated"` |
| `SimulatedGame` | `@UnlessBuildProfile("sc2")` | Add `"emulated"` |

Without these, both `MockEngine` and `EmulatedEngine` would be active in `%emulated`,
causing CDI ambiguity on the `SC2Engine` injection point.

`MockStartupBean` is already guarded by `@UnlessBuildProfile(anyOf = {"sc2", "replay",
"test", "prod"})` — `"emulated"` is not in that list, so it fires in `%emulated` by
design. It injects `AgentOrchestrator`, not `MockEngine` directly, so it works with
either engine implementation. No change needed.

---

## Testing

### `EmulatedGameTest` (plain JUnit, no Quarkus)

| Test | Assertion |
|---|---|
| `mineralAccumulatesOverTicks` | After 100 ticks, minerals ≈ 50 + (12 × 0.0372 × 100) ± 1 |
| `zeroProbesNoAccumulation` | Set miningProbes=0; after N ticks, minerals unchanged |
| `snapshotIsImmutable` | Mutating returned GameState has no effect on EmulatedGame |
| `resetRestoresInitialState` | reset() after ticks returns minerals to 50 |

Tolerance on mineral assertions: ±1 (integer truncation).

### `SimulatedGameTest`

No changes.

---

## Phase Roadmap

| Phase | Mechanic added | Demo |
|---|---|---|
| **E1** | Probe harvest, scaffold | Economics sees real mineral income |
| E2 | Unit positions, scripted enemy, vector movement | Scouting detects enemy build |
| E3 | Combat (DPS, range, death) | Tactics micromanages fights |
| E4 | Enemy active AI (economy + attack wave) | Full game loop |
| E5 | Pathfinding + terrain | Choke control, expansion logic |
| E6 | Headless SC2 Docker | Real SC2 in CI |

---

## Context Links

- Phase overview discussion: this session (2026-04-09)
- `SimulatedGame`: `src/main/java/io/quarkmind/sc2/mock/SimulatedGame.java`
- `MockEngine`: `src/main/java/io/quarkmind/sc2/mock/MockEngine.java`
- Library research (Docker SC2, sc2-data): `docs/library-research.md`
