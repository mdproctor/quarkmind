# SC2 Replay Index

Living index of replay datasets available for feeding into `ReplaySimulatedGame`.
Update this file when new replays or datasets are downloaded.

**Format:** Pre-processed JSON from SC2EGSet — no need to run `RepParserEngine`, the JSON already contains all tracker events, player data, and game metadata.

---

## How to Use

The JSON files in each dataset contain the full event stream. Key fields:
- `ToonPlayerDescMap` — player names, races, results, SQ, APM
- `trackerEvents` — `UnitBornEvent`, `PlayerStatsEvent`, `UpgradeEvent` etc. (frame-by-frame)
- `header.elapsedGameLoops` — total game length (divide by 22.4 for seconds)
- `metadata.mapName` — map name
- `details.timeUTC` — game date

---

## Dataset 1: IEM Season 10 Taipei (2016)

**Source:** [SC2EGSet on Zenodo](https://zenodo.org/records/14963484) (CC BY 4.0)  
**Downloaded:** 2026-04-06  
**Local path:** `replays/2016_IEM_10_Taipei.zip` (11 MB)  
**Format:** Nested ZIP → `*_data.zip` → `<hash>.SC2Replay.json`  
**Game version:** 3.1.1.39948 (Legacy of the Void, January 2016)  
**Total replays:** 30 | **Protoss games:** 21  
**Matchups:** 8×PvP, 5×PvT/TvP, 4×PvZ/ZvP, 9×TvZ/ZvT  
**Maps:** Central Protocol, Dusk Towers, Lerilak Crest, Orbital Shipyard, Prion Terraces, Ruins of Seras, Ulrena  

### Players
| Player | Race | Notes |
|---|---|---|
| sOs | Protoss | Tournament winner. Known for creative, unconventional play. |
| herO | Protoss | Semi-finalist. Strong macro player. |
| Lilbow | Protoss | Quarter-finalist. European Protoss. |
| MinChul (MC) | Protoss | Quarter-finalist. |
| ByuN | Terran | Finalist. Very aggressive bio play. |
| Polt | Terran | Quarter-finalist. |
| Soulkey | Zerg | Semi-finalist. |
| Snute | Zerg | Quarter-finalist. European Zerg. |

### Replay List (Protoss games only)

| # | Hash | Matchup | Map | Duration | Stage | Players | Winner | Labels |
|---|---|---|---|---|---|---|---|---|
| 1 | `095724b...` | TvP | Lerilak Crest | 6m22s | QF | ByuN vs Lilbow | ByuN | `early-game` `terran-aggression` `short` |
| 2 | `b09eebe...` | TvP | Dusk Towers | 5m42s | QF | ByuN vs Lilbow | ByuN | `early-game` `terran-aggression` `short` |
| 3 | `0e0b1a5...` | TvP | Orbital Shipyard | 10m51s | QF | ByuN vs Lilbow | ByuN | `mid-game` `pvt` |
| 4 | `15ad08e...` | PvP | Dusk Towers | 5m38s | QF | MinChul vs sOs | sOs | `early-game` `pvp` `short` |
| 5 | `1e8c6...` | PvP | Ruins of Seras | 12m06s | QF | sOs vs MinChul | sOs | `mid-game` `pvp` |
| 6 | `...` | PvP | Orbital Shipyard | 7m55s | QF | sOs vs MinChul | sOs | `early-game` `pvp` |
| 7 | `...` | ZvP | Dusk Towers | 10m28s | QF | Snute vs herO | herO | `mid-game` `pvz` |
| 8 | `...` | ZvP | Lerilak Crest | 14m30s | QF | Snute vs herO | herO | `mid-game` `pvz` `protoss-wins` |
| 9 | `...` | PvZ | Ruins of Seras | 4m14s | QF | herO vs Snute | Snute | `very-short` `pvz` `zerg-wins` |
| 10 | `...` | PvZ | Central Protocol | 13m05s | QF | herO vs Snute | herO | `mid-game` `pvz` `protoss-wins` |
| 11 | `...` | PvP | Orbital Shipyard | 5m34s | SF | herO vs sOs | herO | `early-game` `pvp` `short` |
| 12 | `...` | PvP | Prion Terraces | 16m30s | SF | herO vs sOs | sOs | `late-game` `pvp` `long` |
| 13 | `...` | PvP | Dusk Towers | 7m59s | SF | sOs vs herO | herO | `early-game` `pvp` |
| 14 | `...` | PvP | Ruins of Seras | 5m43s | SF | sOs vs herO | sOs | `early-game` `pvp` `short` |
| 15 | `...` | PvP | Lerilak Crest | 22m13s | SF | sOs vs herO | sOs | `late-game` `pvp` `very-long` |
| 16 | `...` | PvT | Ruins of Seras | 21m13s | Final | sOs vs ByuN | sOs | `late-game` `pvt` `protoss-wins` `very-long` |
| 17 | `...` | PvT | Dusk Towers | 9m31s | Final | sOs vs ByuN | ByuN | `mid-game` `pvt` `terran-wins` |
| 18 | `...` | TvP | Prion Terraces | 9m38s | Final | ByuN vs sOs | sOs | `mid-game` `pvt` `protoss-wins` |
| 19 | `...` | PvT | Orbital Shipyard | 6m53s | Final | sOs vs ByuN | sOs | `early-game` `pvt` `protoss-wins` |
| 20 | `...` | TvP | Lerilak Crest | 5m36s | Final | ByuN vs sOs | ByuN | `early-game` `pvt` `terran-wins` `short` |
| 21 | `...` | TvP | Central Protocol | 5m35s | Final | ByuN vs sOs | sOs | `early-game` `pvt` `protoss-wins` `short` |

### Good Games for Specific Scenarios

| Scenario | Recommended | Why |
|---|---|---|
| **Standard Protoss economy** | Any Final game (sOs) | sOs plays clean macro, representative Protoss economic curve |
| **Early aggression / short game** | QF ByuN vs Lilbow G1 (5m42s) | Fast bio pressure, good for testing crisis response |
| **Long macro game** | SF sOs vs herO G5 (22m13s) | Full tech tree, late-game army compositions |
| **PvP mirror** | SF sOs vs herO series | Pure Protoss vs Protoss, easier to map to our domain model |
| **Timing attack** | QF herO vs Snute G1 (4m14s) | Very short — zerg all-in, good for spawn-enemy-attack scenario |

---

## TODO — Additional Datasets to Download

| Dataset | Source | Priority | Why |
|---|---|---|---|
| AI Arena Protoss bot games | `aiarena.net` (requires API token) | High | Bot games are more regular/machine-readable than human esports |
| SC2EGSet 2022 DH Masters Atlanta | Zenodo (662 MB) | Medium | More recent LotV meta, larger variety |
| SC2EGSet 2019 WCS Summer | Zenodo (265 MB) | Low | More variety but older meta |
| Any local SC2 replays | `~/Documents/StarCraft II/...` | — | None found on this machine yet |

## Notes

- All SC2EGSet files are **pre-processed JSON** — no need to use `RepParserEngine` or `scelight-s2protocol`. The JSON already has all tracker events extracted.
- Game loops ÷ 22.4 = game time in seconds (Faster speed is 22.4 game loops/second)
- `SQ` (Spending Quotient) measures economic efficiency — higher = fewer wasted resources
- Labels: `early-game` (<8 min), `mid-game` (8-15 min), `late-game` (>15 min), `very-long` (>20 min), `short` (<6 min)
