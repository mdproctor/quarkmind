# ocraft Action Translation — Design Spec
**Date:** 2026-04-09
**Issue:** #12 — Implement ocraft action translation
**Epic:** #11 — Enable real SC2 command dispatch

---

## Context

`SC2BotAgent.onStep()` drains the `IntentQueue` every game frame but logs each
intent as a no-op. All four plugins (DroolsStrategyTask, FlowEconomicsTask,
DroolsTacticsTask, DroolsScoutingTask) produce intents with real unit/building
tags drawn from `GameState`. This spec covers translating those intents into
ocraft `ActionInterface` commands so the bot can play.

The `sc2/real/` connection layer is complete and does not change. The only
production change is adding `ActionTranslator` and wiring it into
`SC2BotAgent.onStep()`.

---

## Architecture

### New class: `ActionTranslator`

`io.quarkmind.sc2.real.ActionTranslator` — `final`, no CDI, no instance state.
Mirrors `ObservationTranslator` exactly: a pure static function family that
translates domain types to ocraft types.

**Entry point:**
```java
public static List<ResolvedCommand> translate(List<Intent> intents)
```

Returns a list of resolved commands ready to dispatch. Returning data rather
than issuing commands directly keeps the class a pure function and makes it
fully testable without mocking ocraft interfaces.

**Package-private mapping methods** (directly testable):
```java
static Abilities mapBuildAbility(BuildingType type)
static Abilities mapTrainAbility(UnitType type)
```

**Private helpers:**
```java
private static Tag toTag(String tagStr)           // Long.parseLong → Tag.of
private static ocraft.Point2d toOcraft(domain.Point2d p)  // float x,y passthrough
```

### New record: `ResolvedCommand`

`io.quarkmind.sc2.real.ResolvedCommand` — package-private, immutable:
```java
record ResolvedCommand(Tag tag, Abilities ability, Optional<ocraft.Point2d> target) {}
```

`target` is present for position-targeted commands (build, attack, move) and
empty for non-positional commands (train).

### `SC2BotAgent.onStep()` change

Replace the current no-op intent drain:
```java
// Before
intentQueue.drainAll().forEach(intent ->
    log.debugf("[SC2] Intent (Phase 1 no-op): %s", intent));

// After
List<Intent> intents = intentQueue.drainAll();
ActionTranslator.translate(intents).forEach(cmd ->
    cmd.target().ifPresentOrElse(
        pos -> actions().unitCommand(cmd.tag(), cmd.ability(), pos, false),
        ()  -> actions().unitCommand(cmd.tag(), cmd.ability(), false)
    )
);
```

`observation()` is not passed to `ActionTranslator` — the Tag-based
`ActionInterface` overloads dispatch directly without unit lookup. Stale or
dead-unit tags are silently ignored by SC2 naturally.

---

## Ability Maps

All constants verified against ocraft 0.4.21 bytecode.

### `mapBuildAbility(BuildingType)` — Probe issues, position-targeted

| BuildingType | Abilities constant |
|---|---|
| NEXUS | `BUILD_NEXUS` |
| PYLON | `BUILD_PYLON` |
| GATEWAY | `BUILD_GATEWAY` |
| CYBERNETICS_CORE | `BUILD_CYBERNETICS_CORE` |
| ASSIMILATOR | `BUILD_ASSIMILATOR` |
| ROBOTICS_FACILITY | `BUILD_ROBOTICS_FACILITY` |
| STARGATE | `BUILD_STARGATE` |
| FORGE | `BUILD_FORGE` |
| TWILIGHT_COUNCIL | `BUILD_TWILIGHT_COUNCIL` |
| UNKNOWN | `null` — warn + skip |

### `mapTrainAbility(UnitType)` — Building issues, no position

| UnitType | Abilities constant | Notes |
|---|---|---|
| PROBE | `TRAIN_PROBE` | |
| ZEALOT | `TRAIN_ZEALOT` | |
| STALKER | `TRAIN_STALKER` | |
| IMMORTAL | `TRAIN_IMMORTAL` | |
| COLOSSUS | `TRAIN_COLOSSUS` | |
| CARRIER | `TRAIN_CARRIER` | |
| DARK_TEMPLAR | `TRAIN_DARK_TEMPLAR` | |
| HIGH_TEMPLAR | `TRAIN_HIGH_TEMPLAR` | |
| OBSERVER | `TRAIN_OBSERVER` | |
| VOID_RAY | `TRAIN_VOIDRAY` | ocraft spells it without underscore |
| ARCHON | `null` — warn + skip | Requires two Templar; single-tag TrainIntent cannot express this |
| ZERGLING / ROACH / HYDRALISK | `null` — warn + skip | Zerg enemy types, not trainable by Protoss |
| MARINE / MARAUDER / MEDIVAC | `null` — warn + skip | Terran enemy types, not trainable by Protoss |
| UNKNOWN | `null` — warn + skip | |

### Attack and Move — fixed abilities, no map needed

| Intent | Ability | Target |
|---|---|---|
| `AttackIntent` | `Abilities.ATTACK` | position-targeted |
| `MoveIntent` | `Abilities.MOVE` | position-targeted |

---

## Intent-to-Command Translation

### Tag conversion

Intent tags are Strings produced by `ObservationTranslator`:
`String.valueOf(u.getTag().getValue())`. The reverse is
`Tag.of(Long.parseLong(tagStr))`. A `NumberFormatException` on parse is caught,
logged, and skipped.

### BuildIntent

```
tag = Probe's unit tag (from GameState.myUnits)
buildingType → mapBuildAbility → Abilities constant
location → toOcraft(domain.Point2d)
→ ResolvedCommand(toTag(tag), ability, Optional.of(toOcraft(location)))
```

### TrainIntent

```
tag = Building's tag (e.g. Nexus tag from GameState.myBuildings)
unitType → mapTrainAbility → Abilities constant
→ ResolvedCommand(toTag(tag), ability, Optional.empty())
```

### AttackIntent / MoveIntent

```
tag = Unit's tag (from GameState.myUnits)
→ ResolvedCommand(toTag(tag), ATTACK or MOVE, Optional.of(toOcraft(targetLocation)))
```

---

## Error Handling

Every intent is translated inside an individual try-catch — one bad intent does
not abort the rest of the frame. The rules:

| Situation | Behaviour |
|---|---|
| Null ability (ARCHON, UNKNOWN, enemy type) | Log warn with intent detail; produce no command |
| `NumberFormatException` on tag parse | Log warn; produce no command |
| Any other exception | Log warn with stack trace summary; produce no command |
| Unknown `Intent` subtype | Log warn; produce no command |

All log messages use the `[SC2]` prefix for consistency with the rest of
`sc2.real`.

---

## Testing

`ActionTranslatorTest` — plain JUnit 5, AssertJ. No CDI, no Quarkus, no mocks.
Tests are documentation: names read as sentences describing behaviour.

### Group 1 — Ability maps (full table coverage)

**`allProtossBuildAbilitiesMapCorrectly()`**
Asserts every BuildingType → BUILD_* mapping in one test. Documents the
complete mapping table as executable specification.

**`unknownBuildingTypeHasNullBuildAbility()`**
Asserts UNKNOWN → null. Documents the skip-on-unknown contract.

**`allProtossTrainAbilitiesMapCorrectly()`**
Asserts every trainable UnitType → TRAIN_* mapping. Includes the VOID_RAY →
TRAIN_VOIDRAY spelling note as a comment.

**`archonTrainAbilityIsNullBecauseItRequiresTwoTemplar()`**
Asserts ARCHON → null. Name documents the reason — "two Templar" makes the
limitation explicit without reading the source.

**`enemyAndUnknownUnitTypesHaveNullTrainAbility()`**
Asserts ZERGLING / ROACH / HYDRALISK / MARINE / MARAUDER / MEDIVAC / UNKNOWN
all return null. Documents that the train map is Protoss-only.

### Group 2 — Intent dispatch (per intent type)

**`buildIntentProducesPositionTargetedCommandWithCorrectTagAbilityAndLocation()`**
Translates `BuildIntent("456", GATEWAY, Point2d(30f, 40f))` and asserts:
tag = `Tag.of(456L)`, ability = `BUILD_GATEWAY`, target present at `(30, 40)`.

**`trainIntentProducesCommandWithCorrectTagAndAbilityAndNoPosition()`**
Translates `TrainIntent("789", PROBE)` and asserts:
tag = `Tag.of(789L)`, ability = `TRAIN_PROBE`, target empty.

**`attackIntentProducesAttackCommandAtTargetLocation()`**
Translates `AttackIntent("111", Point2d(50f, 60f))` and asserts:
ability = `ATTACK`, target present at `(50, 60)`.

**`moveIntentProducesMoveCommandAtTargetLocation()`**
Translates `MoveIntent("222", Point2d(10f, 20f))` and asserts:
ability = `MOVE`, target present at `(10, 20)`.

### Group 3 — Error and edge cases

**`archonTrainIntentProducesNoCommandAndDoesNotThrow()`**
Translates `TrainIntent("333", ARCHON)`. Asserts result is empty list, no
exception thrown. Documents that unsupported morphs are silent no-ops.

**`unknownBuildingTypeProducesNoCommand()`**
Translates `BuildIntent("444", UNKNOWN, Point2d(0f, 0f))`. Asserts empty result.

**`malformedTagProducesNoCommandAndDoesNotThrow()`**
Translates `BuildIntent("not-a-number", PYLON, Point2d(0f, 0f))`. Asserts
empty result, no exception. Documents the parse-error safety contract.

**`validAndInvalidIntentsMixed_onlyValidOnesProduceCommands()`**
Translates three intents: a valid `BuildIntent`, an ARCHON `TrainIntent`, and
a malformed-tag `MoveIntent`. Asserts exactly one command produced (the valid
build). Documents that bad intents do not poison the batch.

**`emptyIntentListProducesEmptyCommandList()`**
Asserts `translate(List.of())` returns an empty list. Documents the base case.

---

## Files Changed

| File | Change |
|---|---|
| `sc2/real/ActionTranslator.java` | New — pure static translator |
| `sc2/real/ResolvedCommand.java` | New — package-private record |
| `sc2/real/SC2BotAgent.java` | Wire ActionTranslator into onStep() |
| `test/.../sc2/real/ActionTranslatorTest.java` | New — full test suite |

No changes to domain, plugins, agent, or any other layer.

---

## Constraints

- `ActionTranslator` must have no CDI annotations, no Quarkus imports, no
  framework dependencies — same rule as `ObservationTranslator`.
- `ResolvedCommand` is package-private to `sc2.real`. Nothing outside that
  package should reference it.
- The `mapBuildAbility` and `mapTrainAbility` methods are package-private (not
  private) so `ActionTranslatorTest` can call them directly without reflection.
