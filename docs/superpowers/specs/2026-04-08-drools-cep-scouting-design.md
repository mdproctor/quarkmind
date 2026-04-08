# Design Spec — DroolsScoutingTask (Drools Fusion CEP)

**Date:** 2026-04-08  
**Status:** Approved  
**Phase:** 4 — ScoutingTask R&D integration  
**R&D angle:** Drools Fusion CEP — temporal pattern detection on scouting observations

---

## 1. Context

`BasicScoutingTask` is already fully implemented. It produces per-tick passive intel
(`ENEMY_ARMY_SIZE`, `NEAREST_THREAT`) and dispatches an active probe scout after a
delay. It is kept as the reference implementation.

`DroolsScoutingTask` is the Phase 4 R&D plugin. It replaces `BasicScoutingTask` as
the active `ScoutingTask` CDI bean and adds Drools Fusion CEP on top: a stateful
`KieSession` accumulates enemy unit observation events across game ticks, and
temporal rules detect enemy build orders, timing attacks, and expansion decisions.

The four plugin seams and their R&D integrations:

| Seam | Implementation | R&D angle |
|---|---|---|
| StrategyTask | DroolsStrategyTask | Drools forward-chaining rules |
| EconomicsTask | FlowEconomicsTask | Quarkus Flow |
| TacticsTask | DroolsTacticsTask | Drools + GOAP planning |
| **ScoutingTask** | **DroolsScoutingTask** | **Drools Fusion CEP** |

---

## 2. Architecture

### 2.1 Session lifetime

One `KieSession` per game, configured in Drools **STREAM event processing mode**
with a **`PseudoClock`**. The clock is driven by game frame number, not wall time:

```
clockAdvanceMs = frameDelta × (1000.0 / 22.4)
```

This makes all temporal rule evaluation deterministic and testable without real
time passing. Unit tests advance the clock manually to simulate minutes of game time.

`ScoutingSessionManager` (`@ApplicationScoped`) owns the session:
- Creates a fresh session + clock when the game starts (`READY` key set)
- Disposes the session at game end
- If READY fires while a session already exists (game restart in mock loop),
  disposes the old session and creates a new one; clears `seenTags`

`DroolsScoutingTask.execute()` is a no-op if the session is not yet initialised.

### 2.2 Per-tick execution

```
1. Advance PseudoClock by frame delta
2. Insert new event facts (units first-seen, army near base, expansions)
3. session.fireAllRules()
4. Read derived facts → write to CaseFile (CEP intelligence keys)
5. Compute ENEMY_ARMY_SIZE and NEAREST_THREAT directly (plain Java, no rules)
6. Run probe dispatch logic (plain Java, same as BasicScoutingTask)
```

### 2.3 Unit deduplication — `seenTags`

SC2 observation snapshots show currently-visible units, not production events.
The same unit tag appears every tick it is visible. A Java-side `Set<String> seenTags`
tracks which enemy unit tags have already fired an event. A `EnemyUnitFirstSeen`
event is inserted only on first observation — giving a proxy for "unit produced".
`seenTags` is cleared on session reset (game restart).

---

## 3. Event Types

All three are plain Java records. Drools event role and timestamp are declared in
the DRL file, not via Java annotations, keeping the records free of Drools imports.

| Event | Inserted when | Fields |
|---|---|---|
| `EnemyUnitFirstSeen` | Enemy unit tag not yet in `seenTags` | `unitType: UnitType`, `gameTimeMs: long` |
| `EnemyExpansionSeen` | Enemy nexus/hatchery/CC observed, tag not yet seen | `position: Point2d`, `gameTimeMs: long` |
| `EnemyArmyNearBase` | Visible enemy count ≥ threshold AND within distance of our Nexus | `armySize: int`, `gameTimeMs: long` |

---

## 4. Rules

All rules live in `src/main/resources/rules/DroolsScoutingTask.drl`.
Thresholds are declared as constants at the top of the DRL for easy calibration
(values are R&D estimates; calibrate against replay data).

### 4.1 Build-order fingerprinting

| Rule | Condition | Derived fact |
|---|---|---|
| Zerg roach rush | ≥ 6 `ROACH` first-seen in 3-min window | `BuildOrderDetected("ZERG_ROACH_RUSH")` |
| Terran 3-rax | ≥ 12 `MARINE` first-seen in 4-min window | `BuildOrderDetected("TERRAN_3RAX")` |
| Protoss 4-gate | ≥ 8 `STALKER` or `ZEALOT` first-seen in 3-min window | `BuildOrderDetected("PROTOSS_4GATE")` |

### 4.2 Timing attack

| Rule | Condition | Derived fact |
|---|---|---|
| Timing attack incoming | `EnemyArmyNearBase` observed within last 10 seconds | `TimingAttackAlert` |

### 4.3 Expansion status

| Rule | Condition | Derived fact |
|---|---|---|
| All-in posture | No `EnemyExpansionSeen` by 3-min mark AND at least one scout probe dispatched | `ExpansionStatus("ALL_IN")` |
| Macro posture | `EnemyExpansionSeen` present | `ExpansionStatus("MACRO")` |

All derived facts are inserted via **`insertLogical`** — they auto-retract when the
triggering condition is no longer satisfied (e.g. `MACRO` retracts if the expansion
was destroyed and observation lost, though this is an edge case in practice).

---

## 5. CaseFile Keys

Three new constants added to `StarCraftCaseFile`:

| Constant | Key string | Type | Value examples |
|---|---|---|---|
| `ENEMY_BUILD_ORDER` | `agent.intel.enemy.build` | `String` | `"PROTOSS_4GATE"`, `"TERRAN_3RAX"` |
| `TIMING_ATTACK_INCOMING` | `agent.intel.enemy.timing` | `Boolean` | `true` / absent |
| `ENEMY_POSTURE` | `agent.intel.enemy.posture` | `String` | `"ALL_IN"`, `"MACRO"`, `"UNKNOWN"` |

Existing keys `ENEMY_ARMY_SIZE` and `NEAREST_THREAT` continue to be produced by
`DroolsScoutingTask` via plain Java (identical logic to `BasicScoutingTask`).

---

## 6. Code Structure

```
plugin/scouting/
  DroolsScoutingTask.java          — @ApplicationScoped, @CaseType("starcraft-game")
  ScoutingSessionManager.java      — owns KieSession lifecycle (create/dispose)
  events/
    EnemyUnitFirstSeen.java        — plain Java record
    EnemyExpansionSeen.java        — plain Java record
    EnemyArmyNearBase.java         — plain Java record
  facts/
    BuildOrderDetected.java        — derived fact (inserted logically by rules)
    TimingAttackAlert.java         — derived fact
    ExpansionStatus.java           — derived fact

src/main/resources/rules/
  DroolsScoutingTask.drl           — event declarations + all five rules

StarCraftCaseFile.java             — three new constants (ENEMY_BUILD_ORDER,
                                     TIMING_ATTACK_INCOMING, ENEMY_POSTURE)

BasicScoutingTask.java             — unchanged; kept as reference implementation
```

---

## 7. Testing

| Test class | Style | Coverage |
|---|---|---|
| `DroolsScoutingTaskTest` | Plain JUnit (`new`) | Passive intel (army size, nearest threat); probe dispatch; no-op before READY |
| `DroolsScoutingRulesTest` | Plain JUnit (`new`) | Each CEP rule fires correctly; PseudoClock advanced manually; derived facts auto-retract |
| `DroolsScoutingTaskIT` | `@QuarkusTest` | Full CDI wiring; session initialises on READY; CEP keys appear in CaseFile |

PseudoClock-driven tests are fast and fully deterministic. No `Thread.sleep`.
Example pattern for a rules test:

```java
// Advance clock to 3 minutes, insert 6 roach events, assert build order detected
pseudoClock.advanceTime(3, TimeUnit.MINUTES);
for (int i = 0; i < 6; i++) session.insert(new EnemyUnitFirstSeen(ROACH, now()));
session.fireAllRules();
assertThat(session.getObjects(BuildOrderDetected.class))
    .anyMatch(b -> b.name().equals("ZERG_ROACH_RUSH"));
```

---

## 8. Native Compatibility

Drools Executable Model compiles rules at build time. STREAM mode + Fusion
temporal operators are native-safe in Drools 10 — one of the explicit
native-safe scenarios in the Drools 10 release notes. Update `NATIVE.md` to
✅ once verified during implementation.

The event records and derived-fact records are plain Java — no reflection issues.

---

## 9. Open Questions (non-blocking)

- **Threshold calibration:** Unit count thresholds (≥ 6 ROACH, ≥ 12 MARINE, ≥ 8
  STALKER/ZEALOT) are R&D starting points. Calibrate against IEM10 or AI Arena
  replay dataset once the system is running.
- **CEP-driven probe dispatch:** The active probe scout currently uses plain Java
  (same as BasicScoutingTask). A future extension could have a CEP rule trigger
  probe dispatch ("no expansion seen by frame 300 and no scout assigned →
  `MoveIntent`"). Deferred — plain Java is sufficient for Phase 4.
- **Expansion destruction:** If an observed expansion is destroyed, `EnemyExpansionSeen`
  remains in the session (it's an event, not a retractable fact). This means
  `MACRO` posture could persist after the expansion is gone. Acceptable for Phase 4;
  fix in a follow-on if it causes strategy misjudgements.
