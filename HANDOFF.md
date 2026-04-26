# Handover — 2026-04-26 (end of session)

**Head commit:** `81404e4` — blog: The Replay Plays

## What Changed This Session

**E22: Replay terrain** — Real SC2 map terrain extracted from `.SC2Map` MPQ files. `SC2MapTerrainExtractor` parses `t3SyncCliffLevel` (CLIF binary, 2 bytes/cell, cliff tiers as multiples of 64). `MapDownloader` fetches AI Arena season packs (att 45/44). `TerrainResource` (`GET /qa/terrain?map=<name>`) + `CurrentMapResource` (`GET /qa/current-map`) serve terrain to visualizer. Visualizer two-phase terrain load: replay → emulated → flat fallback. Closed epic #95.

**E23: Replay movement + interactive UI** — `GameEventStream` parses 22,653 `CmdEvent`/`SelectionDeltaEvent` from GAME_EVENTS. Tag encoding: `tagIndex = rawTag >> 18`, `tagRecycle = rawTag & 0x3FFFF`. `UnitOrderTracker` advances positions at unit-type speed per tick. Play/pause/rewind/scrub/speed control bar. Click-to-inspect unit panel (raycaster + slide-out). SC2/3D camera toggle + WASD pan + responsive layout. Closed epic #101.

**Bug fixes:**
- Enemy units were frozen: `spawnEnemyUnit()` used `"enemy-N"` tags; GAME_EVENTS used `"r-{idx}-{recycle}"`. Fixed with `addEnemyUnit(new Unit(tag,...))`.
- Click inspect never fired: `e.preventDefault()` on `mousedown` suppresses `click` event. Fixed: use `mouseup`.
- Scrub bar stole keyboard focus after seeking. Fixed: `scrub.blur()` on mouseup.

**Electron app** (`electron-app/`) — wraps Quarkus jar + visualizer. Must build jar with `-Dquarkus.profile=replay` (build-time, not runtime — `@IfBuildProfile` is augmentation-time). JVM flags must precede `-jar`. Polls `/qa/replay/status` for `totalLoops > 0` before opening visualizer. ~5s startup.

## Immediate Next Step

**Map features epic** — the replay is live but several visual elements are missing:
- Minerals + geysers: neutral units (`ctrlId == 0`) are dropped entirely in `ReplaySimulatedGame.applyUnitBorn()`
- Enemy buildings (Hatchery, etc.): enemy building branch has no `else` — buildings are dropped
- Visibility toggle (pure JS): hide/show enemy sprite layer
- Creep rendering: depends on enemy buildings being tracked

No issue created yet. Worth creating a "Map Completeness" epic before starting.

## Key Technical Notes

- **`@IfBuildProfile("replay")` is build-time** — must `mvn package -Dquarkus.profile=replay`; runtime `-Dquarkus.profile` only changes config, not CDI beans.
- **`java -jar` flag ordering** — JVM `-D` flags must precede `-jar`, not follow it.
- **`t3SyncCliffLevel` format** — CLIF magic + version + w + h (16-byte header), then `w×h` LE uint16. Multiples of 64 = cliff tiers; non-multiples = ramps. Undocumented.
- **SC2 GAME_EVENTS tag encoding** — `rawTag >> 18` = tagIndex, `rawTag & 0x3FFFF` = tagRecycle → matches tracker-event `"r-{idx}-{recycle}"` format.
- **Enemy buildings** — currently dropped in `applyUnitBorn()` (no `else` for enemy building case).
- *Unchanged notes — `git show HEAD~1:HANDOFF.md`*

## Open Issues

| # | What | Status |
|---|------|--------|
| #74 | Unit genericisation | Parked |
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |

## References

| Context | Where |
|---------|-------|
| Blog entry (this session) | `docs/_posts/2026-04-26-mdp01-the-replay-plays.md` |
| E22 spec | `docs/superpowers/specs/2026-04-24-e22-replay-terrain-design.md` |
| E23 spec | `docs/superpowers/specs/2026-04-24-e23-replay-playback-design.md` |
| Prior handover | `git show HEAD~1:HANDOFF.md` |
| GitHub | mdproctor/quarkmind (#95 closed, #101 closed) |
