# Handover — 2026-04-20

**Head commit:** `f0161c8` — docs: E13 blog entry + CLAUDE.md updates

## What Changed This Session

**E13 complete — Scouting CEP Calibration (closes #16)**

- `UnitType` extended with full Terran/Zerg roster: SIEGE_TANK, THOR, VIKING, GHOST, RAVEN, BANSHEE, BATTLECRUISER, CYCLONE, LIBERATOR, WIDOW_MINE + MUTALISK, ULTRALISK, BROOD_LORD, CORRUPTOR, INFESTOR, SWARM_HOST, VIPER, QUEEN, RAVAGER, LURKER + ADEPT, DISRUPTOR, SENTRY
- `Sc2ReplayShared` — package-private utility class in `sc2/mock/` holding shared lookup tables (`toUnitType`, `toBuildingType`, `defaultUnitHealth`, `defaultBuildingHealth`, `BUILDING_NAMES`, `LOOPS_PER_TICK`); both replay runners delegate to it
- `IEM10JsonSimulatedGame extends SimulatedGame` — reads SC2EGSet JSON from nested BZip2 ZIP (outer requires `commons-compress`, inner uses standard DEFLATE); auto-detects Protoss player; `enumerate(Path)` returns all 30 IEM10 games; 18 tests
- `ReplaySimulatedGame.toUnitType()` — now package-private, full Terran/Zerg cases added
- `ScoutingCalibrationTest @Tag("benchmark")` — runs 59 replays to 3-min mark, prints unit count table by matchup; output to `target/scouting-calibration.txt`
- DRL thresholds calibrated: ROACH_RUSH 6→4, TERRAN_3RAX 12→5, PROTOSS_4GATE 8→4
- 478 tests, 0 failures

**Calibration finding:** ROACH=0 at 3-min across all PvZ games — Roach Rushes peak after the 3-min CEP window. Threshold lowered to 4 as a sensitive lower bound with a DRL comment noting the timing constraint.

**Garden:** 4 gotchas submitted — PR #80 on Hortora/garden (BZip2 in ZIP, exhaustive enum switch, Python ZIP inspection, SC2EGSet food encoding).

**Blog:** `docs/_posts/2026-04-20-mdp02-e13-scouting-calibration.md`

## Immediate Next Step

No clear next epic — #16 done, remaining issues are either parked or blocked. Run:

```bash
gh issue list --state open
```

Then pick from: #74 (unit genericisation / configurable YAML — parked, platform direction) or define a new epic.

## Key Technical Notes

*E12 and earlier notes unchanged — retrieve with:* `git show HEAD~1:HANDOFF.md`

**E13 additions:**
- **BZip2 in ZIP rejects `java.util.zip`** — `ZipArchiveInputStream` (commons-compress) needed for outer ZIP; inner ZIP is DEFLATE and fine with stdlib
- **SC2EGSet JSON food values are raw integers** — NOT ×4096 like Scelight binary; `scoreValueFoodUsed=12` means 12 supply
- **`IEM10JsonSimulatedGame` tag prefix is `"j-"`** — `ReplaySimulatedGame` uses `"r-"` — avoids tag collisions between the two in mixed scenarios
- **commons-compress in NATIVE.md** — tracked as not-yet-verified for GraalVM native; confined to `sc2/mock/`

## Open Issues

| # | What | Status |
|---|------|--------|
| #74 | Unit genericisation / configurable YAML | Parked — platform direction |
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |

## References

| Context | Where |
|---------|-------|
| E13 design spec | `docs/superpowers/specs/2026-04-20-e13-scouting-cep-calibration-design.md` |
| E13 implementation plan | `docs/superpowers/plans/2026-04-20-e13-scouting-cep-calibration.md` |
| Calibration output | `docs/benchmarks/2026-04-20-e13-scouting-calibration.txt` |
| E12 handover (prior) | `git show HEAD~1:HANDOFF.md` |
| Blog entry | `docs/_posts/2026-04-20-mdp02-e13-scouting-calibration.md` |
| GitHub | mdproctor/quarkmind (#16 closed; next: `gh issue list`) |
