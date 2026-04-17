# Handover — 2026-04-17

**Head commit:** `e077a90` — docs(e10): add blog entry 2026-04-17

## What Changed This Session

**Scouting fix committed (recovered from working tree):**
- `DroolsTacticsTask.estimatedEnemyBase` parameterised by `mapWidth`; `%emulated.scouting.map.width=64` config; 7 unit tests + 1 IT regression guard. Closes #68.

**E10: Kiting, focus-fire, per-unit range — complete (closes #69–#73)**
- `weaponCooldownTicks` added to `Unit` record (8th field); stamped from `unitCooldowns` map in `EmulatedGame.snapshot()`. Matches SC2 protobuf `weapon_cooldown`.
- Range fix: `STALKER_RANGE=6.0` deleted; `SC2Data.attackRange(unit.type())` per unit.
- Focus-fire: `selectFocusTarget()` picks lowest HP+shields enemy; all attacking units target same position.
- Kiting: `onCooldownTags` DataStore in `TacticsRuleUnit`; Drools `"kiting"` group; GOAP `KITE` action; `kiteRetreatTarget()` steps 1.0 tile away from nearest enemy.
- Two GOAP correctness bugs caught mid-flight: kiting goal was `"unitSafe"` (unreachable) → fixed to `"enemyEliminated"`; ATTACK missing `"onCooldown", false` precondition allowed planner to bypass KITE at cost 2 vs 3.
- 430 tests, 0 failures.

**Garden:** 5 entries submitted — PRs #70–74 on Hortora/garden (GOAP silent plan, ATTACK precondition bypass, handover commit gap, GOAP two-condition correctness check, WorldState open-world semantics).

**Blog:** `docs/_posts/2026-04-17-mdp01-kiting-and-the-planners-mistakes.md`

## Immediate Next Step

**Run the post-E10 benchmark baseline before E11:**
```bash
mvn test -Pbenchmark
```
Paste output to `docs/benchmarks/2026-04-17-post-e10.md`.

**Then brainstorm E11.** Deferred from E10: kiting with terrain avoidance (kite step may land on wall tile), Stalker Blink micro, multi-target focus-fire split, timing fidelity (`attackCooldownInTicks(STALKER)` → 1 tick). Full deferred list in `docs/superpowers/specs/2026-04-17-e10-tactics-kiting-focusfire-design.md` § Out of Scope.

## Key Technical Notes

- **GOAP goal key must be reachable** — wrong goal = empty plan, no error, units idle. Check: can any action chain reach the goal from the starting WorldState? See GE-20260417-c6e3db.
- **GOAP ATTACK precondition now includes `"onCooldown", false`** — without it, planner bypasses KITE (cost 2 < 3). See GE-20260417-a7f7fc.
- **`WorldState.get()` returns false for absent keys** — open-world semantics; actions only check keys they declare. See GE-20260417-988839.
- **`weaponCooldownTicks` internal units always 0** — cooldown lives in `unitCooldowns` map; stamped onto `Unit` only at `snapshot()` time.
- **Package-private static pattern for CDI bean unit testing** — make pure methods `static` (not `private`) so same-package test classes can call them. Now in CLAUDE.md.

## Open Issues

| # | What | Status |
|---|---|---|
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |
| #16 | Scouting CEP threshold calibration | Needs replay data |

## References

| Context | Where |
|---|---|
| E10 design spec | `docs/superpowers/specs/2026-04-17-e10-tactics-kiting-focusfire-design.md` |
| E10 implementation plan | `docs/superpowers/plans/2026-04-17-e10-tactics-kiting-focusfire.md` |
| E9 handover (prior) | `git show 79b7424:HANDOVER.md` |
| Blog entry | `docs/_posts/2026-04-17-mdp01-kiting-and-the-planners-mistakes.md` |
| GitHub | mdproctor/quarkmind (issues #68–#73 closed) |
