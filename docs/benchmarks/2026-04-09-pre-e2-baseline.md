# Game Loop Benchmark — Pre-E2 Baseline

**Date:** 2026-04-09  
**Context:** Baseline before Phase E2 (movement + scripted enemy + intent handling)  
**Commit:** see `git log` at this date  
**Profile:** `%test` (MockEngine), Warmup: 5, Samples: 30

```
Phase               mean     p95      max
engine.tick()         0ms      1ms      1ms
engine.observe()   (included in physics above)
caseEngine plugins    2ms      7ms      7ms
engine.dispatch()     0ms      0ms      1ms
────────────────────────────────────────
Total gameTick()      2ms      7ms      8ms
```

Raw samples (ms): `[5, 3, 5, 1, 1, 8, 4, 6, 2, 4, 7, 1, 1, 1, 6, 6, 4, 0, 1, 4, 2, 2, 1, 0, 3, 1, 2, 2, 3, 1]`

## Notes

- All four plugins registered (Strategy/Drools, Economics/Flow, Tactics/GOAP, Scouting/CEP)
- MockEngine physics is trivial (+5 minerals/tick, no movement)
- Plugin chain dominates at 2ms mean — well within the 500ms tick budget
- No regression risk from E2 physics changes expected (EmulatedGame physics is O(n) units, n=12 today)
- Watch the `caseEngine plugins` line as unit count grows in E3+
