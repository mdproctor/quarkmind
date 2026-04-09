# Handover — 2026-04-09 (session 2)

**Head commit:** `587fc67` — docs: design snapshot 2026-04-09 — E3 combat complete + budget fix

## What Changed This Session

- **#15 fixed** — `FlowEconomicsTask` budget arbitration: Quarkus Flow serialises `GameStateTick` between `consume()` steps, resetting `ResourceBudget` each time. Fix: collapsed four steps into `checkAll()`. Regression test: 110 minerals, conditions for two decisions → assert size=1.
- **E3 combat** — shields/maxShields on `Unit` record (20-file compile-guided refactor), `resolveCombat()` two-pass simultaneous resolution, `SC2Data.damagePerTick`/`attackRange`/`maxShields`, unit death. 236 tests (unit + integration + Playwright E2E).
- **Visualiser health tinting** — `healthTint()` applies yellow→red tint to sprites; tint written to both PixiJS Container and inner Sprite (dual-locus fix).
- **Playwright E2E combat tests** — full-health no tint, low-health red tint, unit disappears on removal. Injects state via `SimulatedGame.setUnitHealth()`/`removeUnit()`.
- **Blog** — `mdp03` (E3 combat), `mdp04` (E1/E2/visualiser); screenshots and SC2 unit portraits added.
- **Garden** — GE-0068 revised (Flow consume fix), GE-0157 (macOS tmp ENOSPC), GE-0158 (Java record compiler refactoring).

## Immediate Next Step

**E4: Enemy active AI.** Enemy needs its own economy (mineral harvesting, unit production) and attack logic — so the bot faces a real opponent. Before starting: brainstorm, create GitHub epic + child issues, then write plan. Run `mvn test -Pbenchmark` and record the post-E3 baseline before changing anything.

## Open Issues

| # | What | Status |
|---|---|---|
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image tracing | Blocked on #13 |
| #16 | Scouting CEP threshold calibration | Needs replay data |

## Key Technical Notes

- **`attackingUnits` set** — populated by `AttackIntent`, never cleared by `MoveIntent`. Unit continues firing on retreat. E4 needs a cancel path.
- **Two-pass combat** — collect damage into `Map<String, Integer>` first, then apply. Do NOT change to sequential.
- **`moveFriendlyUnits()` and `moveEnemyUnits()`** — must carry `u.shields()` and `u.maxShields()` through the `Unit` replacement or shields silently reset to 0 every tick.
- **Quarkus Flow + shared mutable state** — use one `consume()` step; separate steps serialize/deserialize input, resetting mutations.

## References

| Context | Where |
|---|---|
| Design snapshot | `docs/design-snapshots/2026-04-09-emulation-e3-combat-complete.md` |
| E3 spec | `docs/superpowers/specs/2026-04-09-sc2-emulation-e3-design.md` |
| E4 roadmap entry | E2 spec phase table: `docs/superpowers/specs/2026-04-09-sc2-emulation-e2-design.md` |
| Benchmark | `docs/benchmarks/2026-04-09-pre-e2-baseline.md` (pre-E3; run again before E4) |
| Blog | `docs/blog/2026-04-09-mdp03-the-game-has-stakes.md`, `mdp04-watching-the-game.md` |
| GitHub | mdproctor/quarkmind |
