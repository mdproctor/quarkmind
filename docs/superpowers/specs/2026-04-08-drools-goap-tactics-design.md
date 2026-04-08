# Design Spec: Drools-backed GOAP TacticsTask

**Date:** 2026-04-08
**Status:** Approved
**Issue:** #3 (Implement TacticsTask behind CDI seam)

---

## Overview

Replaces `BasicTacticsTask` with a `DroolsTacticsTask` that implements Goal-Oriented Action
Planning (GOAP) using Drools as the action library compiler and a Java A* search as the
planner. Drools fires once per tick to classify units into groups and emit applicable actions;
A* finds the cheapest action sequence per group; intents are dispatched for the first action.

This is the third R&D plugin integration (after DroolsStrategyTask and FlowEconomicsTask) and
the second Drools integration, stress-testing whether Drools can serve both strategic and
tactical roles simultaneously with different planning horizons.

---

## Architecture

```
DroolsTacticsTask.execute(CaseFile)
  │
  ├─ 1. Extract: army, enemies, nearestThreat, strategy from CaseFile
  │
  ├─ 2. Fire TacticsRuleUnit
  │     Phase 1 (salience 200+): classify units → group+goal strings
  │     Phase 2 (salience 100+): per group → applicable action strings
  │
  ├─ 3. Parse Drools output → List<UnitGroup>
  │     each group: unitTags, goal, WorldState, List<GoapAction>
  │
  ├─ 4. GoapPlanner.plan(worldState, goal, actions) per group → List<GoapAction>
  │
  └─ 5. Dispatch first action per group as Intent
```

---

## Components

### `TacticsRuleUnit` (`plugin/drools/`)

Same pattern as `StrategyRuleUnit`. Per GE-0053: application types must not appear as plain
field types — only JDK types and `DataStore<T>` (generic type erased at runtime) are safe.

```java
DataStore<Unit>   army          // player units
DataStore<Unit>   enemies       // visible enemy units
DataStore<String> inRangeTags   // unit tags within Stalker range — pre-computed in Java
String            strategyGoal  // ATTACK / DEFEND / MACRO (JDK type — safe)
List<String>      groupDecisions  // Phase 1 output
List<String>      actionDecisions // Phase 2 output
```

### `WorldState` (`plugin/tactics/`)

Immutable plain Java record — designed for cheap cloning at each A* node.

```java
record WorldState(Map<String, Boolean> conditions) {
    WorldState with(String key, boolean value); // returns copy with one condition changed
    boolean satisfies(String goalCondition);
}
```

Spike conditions: `inRange`, `lowHealth`, `enemyVisible`, `unitSafe`, `enemyEliminated`.

### `GoapAction` (`plugin/tactics/`)

```java
record GoapAction(
    String name,
    String groupId,
    Map<String, Boolean> preconditions,
    Map<String, Boolean> effects,
    int cost
)
```

### `GoapPlanner` (`plugin/tactics/`)

Pure Java, no CDI. A* over `WorldState` nodes.

```java
List<GoapAction> plan(WorldState initial, String goalCondition, List<GoapAction> actions)
```

- **Heuristic:** number of goal conditions not yet satisfied
- **Node expansion:** for each applicable action (preconditions met), apply effects to current
  `WorldState` via `with()` to produce a new node
- **Returns:** ordered action sequence (cheapest path to goal), or `[]` if goal unreachable or
  already satisfied

### `DroolsTacticsTask` (`plugin/`)

Active CDI bean (`@ApplicationScoped @CaseType("starcraft-game")`). Replaces `BasicTacticsTask`.

Orchestrates: extract game state → compute in-range unit tags (Euclidean distance in Java,
not DRL) → build `TacticsRuleUnit` → fire → parse group + action strings → run `GoapPlanner`
per group → dispatch first action per group as `AttackIntent` or `MoveIntent`.

Range check (Stalker effective range ≈ 6 tiles) is computed in Java before rule firing and
inserted as `inRangeTags` (`DataStore<String>`). This avoids floating-point arithmetic in DRL
and keeps rules declarative.

**DEFEND strategy bypasses GOAP** for the spike — `DroolsTacticsTask` emits `MoveIntent` to
Nexus directly for all units, same as `BasicTacticsTask`. DEFEND is too simple to warrant
planning in the spike; it can be folded into GOAP in a later phase with a `MoveToBase` action.

`MoveIntent` covers both `MoveToEngage` and `Retreat` for the spike — no new intent type needed.

---

## DRL Structure — `tactics.drl`

### Phase 1: Group Classification (salience 200+)

Output string format: `"groupId:GOAL:unitTag"`

| Rule | Condition | Output |
|---|---|---|
| `Group: low health` | `health < maxHealth * 0.3` | `"low-health:UNIT_SAFE:<tag>"` |
| `Group: in range` | unit tag in `inRangeTags` DataStore | `"in-range:ENEMY_ELIMINATED:<tag>"` |
| `Group: out of range` | enemy visible, not in range, not low health | `"out-of-range:ENEMY_ELIMINATED:<tag>"` |
| `Group: MACRO hold` | `strategyGoal == "MACRO"` | no groups emitted |

Low-health check takes priority over range check via salience ordering.

### Phase 2: Action Emission (salience 100+)

Output string format: `"groupId:ACTION_NAME:cost"`

| Rule | Fires when | Output |
|---|---|---|
| `Action: Retreat` | `low-health` group exists in groupDecisions | `"low-health:RETREAT:1"` |
| `Action: Attack` | `in-range` group exists in groupDecisions | `"in-range:ATTACK:2"` |
| `Action: MoveToEngage` | `out-of-range` group exists in groupDecisions | `"out-of-range:MOVE_TO_ENGAGE:1"` |

---

## Testing

Tests serve as the living specification — each scenario documents intended behaviour.

### `GoapPlannerTest` (plain JUnit)

| Scenario | Asserts |
|---|---|
| Out-of-range unit, goal `ENEMY_ELIMINATED` | plan = `[MoveToEngage, Attack]` |
| In-range unit, goal `ENEMY_ELIMINATED` | plan = `[Attack]` |
| Low-health unit, goal `UNIT_SAFE` | plan = `[Retreat]` |
| Goal already satisfied in initial WorldState | plan = `[]` |
| Goal unreachable (no applicable actions) | plan = `[]` |
| Two paths to goal, different costs | A* returns cheaper path |
| Multiple goal conditions, partially satisfied | plan covers only unsatisfied conditions |

### `TacticsRuleUnitTest` (plain JUnit)

| Scenario | Asserts |
|---|---|
| Unit health < 30% | classified as `low-health`, goal `UNIT_SAFE` |
| Unit within range of enemy | classified as `in-range`, goal `ENEMY_ELIMINATED` |
| Healthy unit, enemy visible, not in range | classified as `out-of-range`, goal `ENEMY_ELIMINATED` |
| Mixed army (all three states) | three groups emitted correctly |
| No enemies visible | no groups emitted |
| Empty army | no groups emitted |
| `low-health` group exists | `RETREAT` action emitted |
| `in-range` group exists | `ATTACK` action emitted |
| `out-of-range` group exists | `MOVE_TO_ENGAGE` action emitted |
| MACRO strategy | no groups emitted (hold position) |

### `DroolsTacticsTaskIT` (`@QuarkusTest`)

| Scenario | Asserts |
|---|---|
| ATTACK, all units in range | `AttackIntent` per unit toward nearest threat |
| ATTACK, all units out of range | `MoveIntent` per unit toward nearest threat |
| ATTACK, all units low health | `MoveIntent` (retreat) per unit toward Nexus |
| ATTACK, mixed army | correct intent type per unit group |
| DEFEND strategy | all units move to Nexus regardless of range |
| MACRO strategy | no intents emitted |
| No army | no intents emitted |
| No enemies | no intents emitted |

---

## Alternatives Considered

### Goal Assignment Strategy

Three approaches were evaluated for mapping strategic posture (ATTACK/DEFEND/MACRO) to
per-group GOAP goals. **Option C (two-level decomposition) was chosen.**

**A. Direct translation**
ATTACK → `EnemyEliminated` for all units; DEFEND → `BaseSafe`. Simple and predictable but
rigid — a low-health unit still charges in under ATTACK. Doesn't exploit GOAP's per-unit
context awareness. Good starting point if two-level proves too complex.

**B. Bottom-up, independent goals**
Each unit's goal is assigned purely from its situation (low health → `UnitSafe`, in range →
`EnemyEliminated`). True emergent GOAP behaviour but may contradict strategic intent — army
retreats when StrategyTask says attack. No inter-group coordination.

**C. Two-level decomposition** *(chosen)*
Strategy sets army-level direction; Drools Phase 1 rules decompose it into per-group
sub-goals. Strategic intent is preserved AND individual unit situations are respected. Goal
assignment policy lives in DRL — hot-reloadable without restarting, making it a tunable
experimental parameter for live testing across different strategic styles.

### Drools Role in the Planning Loop

Three positions for Drools relative to A* were evaluated. **Action compiler was chosen.**

**Drools as forward-chaining planner**
Insert WorldState + goal as facts; rules fire to achieve the goal; fired rule sequence is
the plan. Elegant but greedy (salience-ordered, not cost-optimal). Breaks down when multiple
paths exist with different costs.

**Drools as per-node oracle**
A* calls into a Drools session at each search node to check preconditions. Architecturally
clean but requires cloning Drools session state per node — expensive and complex.

**Drools as action compiler** *(chosen)*
Drools fires once on the real game state and produces `GoapAction` objects. Java A* operates
entirely on plain Java `WorldState` clones — no Drools involvement during search. Decoupled
at the `GoapAction` boundary. One session per tick.

### Unit Granularity

**Per-unit planning** — each unit gets its own WorldState and plan. True micro but N planner
invocations per tick.

**Per-army planning** — one WorldState, one plan, all units execute the same action. Simplest
but no per-unit context.

**Hybrid** *(chosen)* — units grouped by shared tactical state; one plan per group. Balances
coordination with individual context awareness.

---

## Scope: Spike Only

This spec covers the spike (Phase 3 R&D) — three actions: `MoveToEngage`, `Attack`, `Retreat`.

Full action vocabulary for future phases:

| Action | Notes |
|---|---|
| `Kite` | Maintain max range while attacking — requires `hasRangeAdvantage` condition |
| `Focus` | Concentrate fire on lowest-health enemy — requires enemy health tracking |
| `FlankLeft` / `FlankRight` | Positional flanking — requires map geometry |

---

## NATIVE.md Impact

If gdx-ai is not used, no new JVM-only dependency is introduced. `GoapPlanner` is pure Java.
Drools is already tracked in `NATIVE.md` as `casehub-core` dependency. No new entry required.
