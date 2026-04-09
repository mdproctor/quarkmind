# Benchmark — Post-E3 Baseline

**Date:** 2026-04-09  
**Commit:** `3d2df0c` — docs: session handover 2026-04-09 (session 2)  
**Context:** Post-E3 (shields, two-pass combat, health tinting). Pre-E4 baseline.

## Results

```
Phase               mean     p95      max
engine.tick()         0ms      0ms      0ms
engine.observe()   (included in physics above)
caseEngine plugins    0ms      1ms      1ms
engine.dispatch()     0ms      0ms      0ms
────────────────────────────────────────
Total gameTick()      0ms      1ms      1ms
```

Raw samples (ms): `[0, 1, 0, 1, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0]`

## Notes

- Identical to pre-E2 baseline — E3 combat additions (shields, two-pass resolution, unit death) had zero measurable impact on tick latency.
- Post-shutdown ArC container errors in test output are expected noise — benchmark test itself passes cleanly (1/1).
