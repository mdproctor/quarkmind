# Handover — 2026-04-14

**Head commit:** `b193fdc` — feat(e4): add YAML file loading support for EnemyStrategy config

## What Changed This Session

- **E4 complete** — enemy active AI + attack cooldowns merged to main. 272 tests (unit + integration + E2E Playwright).
- **EnemyStrategy** — pure domain record (loop, mineralsPerTick, buildOrder, attackConfig). Staged units wait at spawn until threshold OR timer fires. Swappable at runtime via `PUT /qa/emulated/config/enemy-strategy` or YAML file at startup.
- **Attack cooldowns** — `damagePerTick` deleted; per-unit `unitCooldowns`/`enemyCooldowns` maps in `EmulatedGame`. `resolveCombat()` 4-step: decrement → collect from cd=0 → apply two-pass → reset fired units. `MoveIntent` now clears `attackingUnits` (cancel path fixed).
- **Visualizer** — blue-tinted staging layer (0x4488ff) via `syncLayer` on `state.enemyStagingArea`.
- **Issues #17–#25** created retroactively; commit history rewritten with cherry-pick loop to add `Refs/Closes #N` footers; pushed.
- **Garden** — 5 new entries: bash `$()` newline stripping, Quarkus `@ConfigProperty defaultValue=""`, subagents don't inherit CLAUDE.md, cherry-pick history rewrite technique, Jackson records without annotations in Quarkus.
- **Blog** — `2026-04-14-mdp01-enemy-gets-to-work.md` (E4 complete + pivot from Terran plan).

## Immediate Next Step

**E5: Damage types, armour, retreat, pathfinding.** Per E4 design spec phase table. Before starting: brainstorm → create epic + child issues → write plan. Run `mvn test -Pbenchmark` for post-E4 baseline first.

**Issue-workflow fix for subagents:** Subagent prompts must include explicit issue-workflow instructions — CLAUDE.md automatic behaviours don't propagate to fresh subagent contexts. Add to every implementer prompt: check for active issue, create if missing, include `Refs #N` in all commits.

## Open Issues

| # | What | Status |
|---|---|---|
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image tracing | Blocked on #13 |
| #16 | Scouting CEP threshold calibration | Needs replay data |

## Key Technical Notes

- **Two-pass combat invariant** — all damage collected before any applied. Do NOT change to sequential.
- **Cooldown absent = 0 = fire immediately** — missing key in cooldown map means unit is ready.
- **`framesSinceLastAttack` timer** — only resets when attack actually fires (staging non-empty). Empty staging at timer fire → no reset.
- **`attackingUnits` cancel path** — `MoveIntent` removes from set; `AttackIntent` adds. Fixed in E4.
- **Subagent issue-workflow** — see garden entry GE-20260414-736039 for the pattern to avoid.
- **Quarkus Optional config** — use `Optional<String>` not `defaultValue=""` for optional properties (see GE-20260414-1b00a0).

## References

| Context | Where |
|---|---|
| E4 design spec | `docs/superpowers/specs/2026-04-14-sc2-emulation-e4-design.md` |
| E4 implementation plan | `docs/superpowers/plans/2026-04-14-e4-enemy-ai-attack-cooldowns.md` |
| E3 spec (prior phase) | `docs/superpowers/specs/2026-04-09-sc2-emulation-e3-design.md` |
| Blog entry | `docs/_posts/2026-04-14-mdp01-enemy-gets-to-work.md` |
| GitHub | mdproctor/quarkmind (issues #17–#25 closed) |
