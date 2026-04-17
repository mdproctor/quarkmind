# E11 Design — Strategy Provider Pattern + Tactics Extensions

**Date:** 2026-04-17  
**Epic:** E11  
**Status:** Approved

---

## Overview

E11 introduces the **Strategy Provider Pattern** — a CDI-based mechanism for selecting sub-behaviors within the tactics plugin via configuration, without hardcoding them as static Java methods. It then applies this pattern to three behavioral extensions: terrain-aware kiting, multi-target focus-fire (overkill redirect), and Stalker Blink micro (conditional on complexity at implementation time).

The pattern is designed with a stable interface that can later support dynamic or Lua-backed implementations behind the same CDI seam (tracked in #74).

---

## Section 1 — Strategy Provider Pattern

### Interfaces

Two new interfaces in `plugin/tactics/`:

```java
interface KiteStrategy {
    Point2d retreatTarget(Unit unit, List<Unit> enemies, TerrainGrid terrain);
}

interface FocusFireStrategy {
    Map<String, Point2d> assignTargets(List<Unit> attackers, List<Unit> enemies);
    // tag → target position
}
```

`TerrainGrid` is nullable in `KiteStrategy` — if null (mock/replay mode), implementations must fall back to terrain-blind behaviour.

### Wiring

Config-driven selection via `application.yaml`:

```yaml
quarkmind.tactics.kite.strategy: direct
quarkmind.tactics.focus-fire.strategy: lowest-hp
```

`DroolsTacticsTask` injects `Instance<KiteStrategy>` and `Instance<FocusFireStrategy>` and selects the named bean at startup via `@ConfigProperty`. Defaults preserve E10 behaviour exactly.

### Extensibility

- Future dynamic delegate: `@Named("dynamic") DynamicKiteStrategy` holds `Instance<KiteStrategy>` and switches at runtime based on game state — zero changes to `DroolsTacticsTask`.
- Future Lua-backed: `@Named("lua") LuaKiteStrategy implements KiteStrategy` reads a script path from config — same interface, different backing.

---

## Section 2 — Timing Fix

`SC2Data.attackCooldownInTicks(STALKER)`: **3 → 1**

Real SC2 Stalker fires every ~0.535s ≈ 1 tick at 500ms/tick. With cooldown=1 the Stalker is on cooldown for only one tick between shots, so kiting triggers far less frequently than under E10's cooldown=3. The E10 physics tests (kiting retains HP) will need threshold adjustments — no logic changes.

---

## Section 3 — Terrain-Aware Kiting

### DirectKiteStrategy (`@Named("direct")`)

Extracts current `kiteRetreatTarget()` logic verbatim. Default config — no behaviour change.

### TerrainAwareKiteStrategy (`@Named("terrain-aware")`)

1. Compute ideal retreat direction (unit position → away from nearest enemy)
2. Step `KITE_STEP` tiles in that direction to get the candidate tile
3. Check `TerrainGrid.isWalkable(x, y)` on the candidate
4. If walkable → return it (identical to direct)
5. If not → angular sweep: try the 8 compass directions at 45° increments, rank by angular deviation from ideal direction, return the first walkable candidate
6. If all 8 blocked → return current position (degenerate fallback; should not occur in practice)

Null-safe: if `TerrainGrid` is null, skips walkability check and behaves as `DirectKiteStrategy`.

---

## Section 4 — Multi-Target Focus-Fire

### LowestHpFocusFireStrategy (`@Named("lowest-hp")`)

Extracts current `selectFocusTarget()` logic. All attackers assigned to the single lowest HP+shields enemy. Default config.

### OverkillRedirectFocusFireStrategy (`@Named("overkill-redirect")`)

1. Sort enemies by HP+shields ascending
2. Primary = lowest HP+shields enemy
3. Walk attackers accumulating `SC2Data.damagePerAttack()` against the primary — stop when accumulated damage ≥ primary HP+shields
4. Those attackers → primary target
5. Remaining attackers → secondary target (next lowest HP+shields), or primary if none exists

Returns `Map<String, Point2d>` (unit tag → target position). The dispatch loop in `DroolsTacticsTask` sends each attacking unit to its individually assigned target, replacing the current single `focusTarget` broadcast.

**Edge cases handled automatically:** single enemy → all attack it; primary survives full volley → all attack primary (same as lowest-hp).

---

## Section 5 — Stalker Blink Micro (Conditional)

Implemented last. Punt to E12 if GOAP planner interactions with the two new WorldState keys produce unexpected behaviour.

### Unit Record

Add `blinkCooldownTicks` as the 9th field. `EmulatedGame.snapshot()` stamps it from a new `blinkCooldowns` map. All other paths (`SimulatedGame`, `ReplaySimulatedGame`) pass `0`.

### SC2Data

```java
blinkRange(STALKER)           → 8.0f  // tiles
blinkCooldownInTicks(STALKER) → 21    // ≈ 10.5s at 500ms/tick
blinkShieldRestore(STALKER)   → 40    // shields restored on blink
```

### EmulatedGame

- `blinkCooldowns: Map<String, Integer>` — decremented each tick alongside `unitCooldowns`
- Blink execution: position jump to `blinkRetreatTarget()` (8-tile terrain-aware step) + shield restore capped at `maxShields` + blink cooldown reset to `blinkCooldownInTicks()`

### Drools Classification

Two new DataStores in `TacticsRuleUnit`: `blinkReadyTags` (Stalkers where `blinkCooldownTicks == 0`) and `shieldsLowTags` (units where `shields < maxShields * 0.25` — i.e. below 25%; for a Stalker: shields < 20). New Drools group `"BLINKING"` classifies units present in both DataStores.

### GOAP

Two new WorldState keys: `shieldsLow`, `blinkReady`.

```
BLINK action:
  preconditions: {shieldsLow: true, blinkReady: true}
  effects:       {shieldsLow: false, inRange: false}
  cost:          1
```

After blink the unit is out of range — existing `APPROACHING` Drools group takes over naturally. No new GOAP action required for re-approach.

### Punt Condition

If wiring `shieldsLow` + `blinkReady` into the GOAP plan produces unexpected planner interactions (silent empty plans, wrong action ordering) that require more than one debugging cycle to resolve — stop and defer to E12.

---

## Testing

**Unit tests (no Quarkus):**
- `KiteStrategyTest`: terrain-aware sweep cases — wall ahead, wall left+ahead, all directions blocked
- `FocusFireStrategyTest`: overkill split — 5 attackers vs 2 enemies, correct assignment; single enemy; primary survives full volley
- `BlinkMechanicsTest` (if implemented): `blinkCooldownTicks` stamping, shield restore cap at `maxShields`, blink cooldown reset

**Physics E2E (`EmulatedGameTest`):**
- Terrain-aware kiting: units near wall-row do not enter wall tiles during kite
- Overkill redirect: two-enemy scenario resolves faster than lowest-hp
- Blink (if implemented): Stalker with low shields blinks, retains more HP than non-blinking Stalker

**Integration test (`@QuarkusTest`):**
- Strategy config selection: verify named bean is loaded per config property

**E10 physics test adjustments:**
- Kiting-retains-HP thresholds recalibrated for STALKER cooldown=1 (no logic changes)

---

## Out of Scope

- `EconomicsDecisionService` strategy pattern — future, after build-order experimentation becomes a priority
- Dynamic / Lua-backed strategy implementations — tracked in #74
- Focus-fire split beyond two targets
- Blink for non-Stalker units
