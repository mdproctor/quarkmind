# Handover — 2026-04-20

**Head commit:** `2bfcac3` — fix(e12): kiting guard prevents double-classification with blinking

## What Changed This Session

**Post-E11 housekeeping:** benchmark baseline recorded (`docs/benchmarks/2026-04-18-post-e11.md` — p95 1ms, max 1ms, unchanged from E10). Stale issues #69–#73 (E10 work landed but never closed) — all closed.

**E12 complete — Stalker Blink (closes #82)**

- `blinkCooldownTicks` added as 9th field to `Unit` — 70 call sites migrated across 24 files
- `SC2Data`: `blinkRange(STALKER)=8f`, `blinkCooldownInTicks(STALKER)=21`, `blinkShieldRestore(STALKER)=40`
- `BlinkIntent` sealed type; `Intent` permits updated; `EmulatedGame.applyIntent()` and `ActionTranslator` dispatch updated
- `EmulatedGame`: `blinkCooldowns` map (decrement/cleanup matching `unitCooldowns`), `executeBlink()` (8-tile teleport + shield restore capped at maxShields + cooldown reset), `blinkRetreatTarget()` (angular sweep, terrain-aware), `snapshot()` stamps real value, `spawnFriendlyUnitForTesting()` helper added
- `TacticsRuleUnit`: `blinkReadyTags` + `shieldsLowTags` DataStores
- `StarCraftTactics.drl`: BLINKING group (salience 205) + Blink action (salience 105); `not /shieldsLowTags` guard on kiting rule prevents double-classification
- `DroolsTacticsTask`: BLINK GoapAction, `computeBlinkReadyTags/ShieldsLowTags`, `buildWorldState("blinking")`, dispatch BLINK case
- Tests: `BlinkMechanicsTest` (6 unit), survival E2E in `EmulatedGameTest`, static helper tests in `DroolsTacticsTaskTest`
- 446 tests, 0 failures

**Garden:** 2 gotchas submitted — PR #77 on Hortora/garden (sealed interface exhaustiveness mid-plan, Drools salience ≠ mutual exclusion).

**Blog:** `docs/_posts/2026-04-20-mdp01-e12-stalker-blink.md`

## Immediate Next Step

**Start #16: Scouting CEP calibration.** Read the issue first — needs replay data context.

```bash
gh issue view 16
```

## Key Technical Notes

*E11 notes unchanged — retrieve with:* `git show HEAD~1:HANDOFF.md`

**E12 additions:**
- **Drools salience ≠ mutual exclusion** — all matching rules fire in salience order; enforce exclusivity with `not` guards between classification groups
- **`blinkCooldowns` follows `unitCooldowns` pattern** — Unit records store 0; maps are authoritative; `snapshot()` stamps both at read time
- **Sealed interface exhaustiveness is codebase-wide** — adding a permitted type breaks all existing switches immediately; add placeholder cases in the same commit as the type

## Open Issues

| # | What | Status |
|---|------|--------|
| #16 | Scouting CEP calibration | **Next** |
| #74 | Unit genericisation / configurable YAML | Parked — platform direction |
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |

## References

| Context | Where |
|---------|-------|
| E12 implementation plan | `docs/superpowers/plans/2026-04-18-e12-stalker-blink.md` |
| E12 design spec | `docs/superpowers/specs/2026-04-17-e11-strategy-pattern-tactics-extensions-design.md` § Section 5 |
| E11 handover (prior) | `git show HEAD~1:HANDOFF.md` |
| Blog entry | `docs/_posts/2026-04-20-mdp01-e12-stalker-blink.md` |
| Post-E11 benchmark | `docs/benchmarks/2026-04-18-post-e11.md` |
| GitHub | mdproctor/quarkmind (#82 closed; #16 next) |
