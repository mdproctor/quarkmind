# Replay Throughput — 2026-04-27

Machine: MacBook Pro M-series  
Build: `c825b57` (marker scale fix)  
Runner: `mvn test -Pbenchmark -Dtest=ReplayScenarioValidationTest`

| Replay | Duration | Loops | Time (ms) | Loops/sec | Real-time ratio |
|--------|----------|-------|-----------|-----------|-----------------|
| PuckPvZ-short | ~4m | 5,670 | 0.6 | 10,065,095 | 449,335× |
| TycklesPvP-marathon | ~44m | 58,626 | 6.3 | 9,346,574 | 417,258× |
| StarLightPvZ-long | ~20m | 27,710 | 4.4 | 6,347,679 | 283,379× |
| ArgoBotPvT | ~10m | 13,413 | 1.8 | 7,623,912 | 340,353× |
| NothingPvZ | ~8m | 11,229 | 7.6 | 1,484,344 | 66,265× |

**Baseline:** ~1.5M–10M loops/sec raw (66,000–450,000× real-time).  
Nothing PvZ is slower due to 22,653 GAME_EVENTS movement orders loaded via `GameEventStream`.  
All other replays process orders-of-magnitude faster than real-time — no performance concerns.

Note: NothingPvZ slower because it loads movement orders (`loadOrders(22,653 entries)`) which are advanced each tick in `advanceMovement()`. The 66,265× ratio is still well above any real-time requirement.
