# SC2 Image Index

Quick reference for StarCraft II imagery — URLs, descriptions, style notes, and relevance to this project.
Use this before searching the web. Update it when new images are found or downloaded.

**Image pattern (Liquipedia):**
- Full-res: `https://liquipedia.net/commons/images/<hash>/<filename>.jpg`
- Thumbnail: `https://liquipedia.net/commons/images/thumb/<hash>/<filename>.jpg/600px-<filename>.jpg`
- Strip `/thumb/` and `/600px-*` suffix for the full-resolution original.

---

## Already Downloaded (docs/blog/assets/)

| File | Source | Notes |
|---|---|---|
| `protoss-icon.png` | Liquipedia | 493×493 PNG, transparent background. Gold Protoss crest/emblem. Clean, works on light or dark. |
| `sc2-zealot.jpg` | Liquipedia | 900×700 in-game render. Zealot mid-stance on dark floor tiles. Matches units in our `UnitType.ZEALOT`. |
| `sc2-stalker.jpg` | Liquipedia | 900×700 in-game render. Stalker — blue glowing orbs, dark armour, distinctive silhouette. Used in Phase 1 header (spawned in `spawn-enemy-attack` debug scenario). Maps to `UnitType.STALKER`. |
| `architecture-plugins.png` | Local screenshot | 1400×900 @2x. Our four-level plugin architecture diagram from the brainstorming session. |

---

## Liquipedia — Protoss Units

All images are in-game renders on neutral dark backgrounds, consistent style across the unit set. Good for pairing with code that references the unit type. Full-res downloads are clean enough for blog headers.

### Workers & Scouting
| Unit | Full-res URL | Notes |
|---|---|---|
| **Probe** | `https://liquipedia.net/commons/images/4/4f/SC2Probe.jpg` | 600px thumb available. Worker unit — relevant to `BasicEconomicsTask`, mining logic, supply. Our SimulatedGame starts with 12 of these. |
| **Observer** | `https://liquipedia.net/commons/images/9/91/SC2Observer.jpg` | Scouting unit — maps to `ScoutingTask`, fog-of-war, terrain analysis phase. |

### Ground Combat
| Unit | Full-res URL | Notes |
|---|---|---|
| **Zealot** | `https://liquipedia.net/commons/images/5/5c/SC2Zealot.jpg` ✅ downloaded | Melee, psi-blades, +8 supply. Our primary scenario target (`spawn-enemy-attack` spawns 2 zealots). Already in blog. |
| **Stalker** | `https://liquipedia.net/commons/images/6/63/SC2Stalker.jpg` | Ranged blink unit. Spawned in `SpawnEnemyAttackScenario` alongside zealots. Dark teal energy coils — visually distinct from zealot. |
| **Immortal** | `https://liquipedia.net/commons/images/e/ee/SC2Immortal.jpg` | Heavy assault mech. Hardened shields make it punishing to fight attrition-style — relevant for future combat simulation (`sc2-libvoxelbot`). Distinctive silhouette. |
| **Colossus** | `https://liquipedia.net/commons/images/6/6c/SC2Colossus.jpg` | Massive walker — tall, spindly legs. The unit most visually unlike everything else. Good for "look what this thing can be" posts. In our `UnitType` enum as `COLOSSUS`. |

### Air Units
| Unit | Full-res URL | Notes |
|---|---|---|
| **Carrier** | Look up: `https://liquipedia.net/starcraft2/Carrier` | Mothership-adjacent capital ship. In our `UnitType.CARRIER`. Long-game tech unit — good visual for Phase 4+ ambition posts. |
| **Void Ray** | Look up: `https://liquipedia.net/starcraft2/Void_Ray` | Prismatic beam, charges up on armored. In our `UnitType.VOID_RAY`. |

### Cloaked / Special
| Unit | Full-res URL | Notes |
|---|---|---|
| **Dark Templar** | Look up: `https://liquipedia.net/starcraft2/Dark_Templar` | Permanently cloaked melee unit. Our `UnitType.DARK_TEMPLAR`. Classic GDA EISBot featured Dark Templar strategy — direct callback to the paper. |
| **High Templar** | Look up: `https://liquipedia.net/starcraft2/High_Templar` | Psionic storm caster. `UnitType.HIGH_TEMPLAR`. |
| **Archon** | Look up: `https://liquipedia.net/starcraft2/Archon` | Merged from two High Templar. Massive energy shield. `UnitType.ARCHON`. |

---

## Liquipedia — Protoss Buildings

Consistent style: isometric renders, gold/blue Protoss aesthetic, dark floor.

| Building | Full-res URL | Notes |
|---|---|---|
| **Nexus** | `https://liquipedia.net/commons/images/f/f8/SC2Nexus.jpg` | Main base structure. Our SimulatedGame starts with one. Relevant to any economic discussion (minerals, workers, recall ability). |
| **Pylon** | `https://liquipedia.net/commons/images/4/48/SC2Pylon.jpg` | Supply building, powers the Psionic Matrix. `BuildingType.PYLON`. Good for posts about supply management, `BasicEconomicsTask`. |
| **Gateway** | `https://liquipedia.net/commons/images/9/99/SC2Gateway.jpg` | Primary unit production. `BuildingType.GATEWAY`. Relevant when we implement actual build order logic. |
| **Cybernetics Core** | Look up: `https://liquipedia.net/starcraft2/Cybernetics_Core` | Enables Stalkers, Warp Gate. `BuildingType.CYBERNETICS_CORE`. Tech progression. |
| **Assimilator** | Look up: `https://liquipedia.net/starcraft2/Assimilator` | Vespene gas extractor. `BuildingType.ASSIMILATOR`. Relevant to resource economics posts. |
| **Robotics Facility** | Look up: `https://liquipedia.net/starcraft2/Robotics_Facility` | Produces Immortals, Colossus, Observers. `BuildingType.ROBOTICS_FACILITY`. |
| **Stargate** | Look up: `https://liquipedia.net/starcraft2/Stargate` | Air unit production. `BuildingType.STARGATE`. |

---

## Liquipedia — Race Icons & Emblems

| Asset | URL | Notes |
|---|---|---|
| **Protoss Icon** | `https://liquipedia.net/commons/images/e/e4/ProtossIcon.png` ✅ downloaded | 493×493 PNG, transparent. The gold Khaydarin crystal crest. Used in Day Zero header. |
| **Terran Icon** | `https://liquipedia.net/commons/images/3/33/TerranIcon.png` | For comparison posts, multi-race context. |
| **Zerg Icon** | `https://liquipedia.net/commons/images/5/5e/ZergIcon.png` | Same. |
| **SC2 Logo** | Look up on Liquipedia | Full SC2 Wings of Liberty / LotV logo variants. |

---

## Wallpaper Collections (Larger Artwork, Atmospheric)

Good for post headers, background context images, "what is this game" visuals. These are fan-aggregated sites; Blizzard content, editorial use.

| Site | URL | Style |
|---|---|---|
| **Alpha Coders** | `https://alphacoders.com/protoss-(starcraft)-wallpapers` | High quality, curated. Concept art, CG renders, fan art mixed. Good variety. |
| **Wallpaper Cave (Protoss HD)** | `https://wallpapercave.com/starcraft-2-protoss-hd-wallpapers` | Broadly sourced. Some fan art. Useful for atmospheric wide-crop header images. |
| **Wallpaper Flare** | `https://www.wallpaperflare.com/search?wallpaper=starcraft+ii+protoss` | Very high resolution options (4K, 5K). Good if you want a stunning wide-format image. |
| **WallpaperAccess** | `https://wallpaperaccess.com/starcraft-2-protoss` | 1920×1080 and 1920×1200 direct downloads. Clean resolution for standard blog widths. |

---

## Official Blizzard Sources

| Source | URL | Notes |
|---|---|---|
| **SC2 Wallpapers (legacy)** | `http://us.battle.net/sc2/en/media/wallpapers/` | Redirects to starcraft2.com. Official wallpapers page — currently hard to navigate directly but was the canonical source. |
| **Blizzard News – Wallpapers** | `https://news.blizzard.com/en-gb/article/9985352/new-wallpaper-available` | Individual news articles with direct wallpaper downloads at multiple resolutions. Worth checking for specific pieces. |
| **Classic SC1 Wallpaper Archive** | `https://classic.battle.net/scc/wall.shtml` | SC1 only, but has clean concept art establishing shots. Historically interesting for context posts about the franchise. |

---

## Copyright Note

All StarCraft II artwork and screenshots are © Blizzard Entertainment. Used here for editorial, non-commercial purposes consistent with Blizzard's community media guidelines. The Liquipedia wiki images are widely used in editorial gaming content under the same conventions.

For any future commercial use or publication, check [Blizzard's Fan Art Policy](https://www.blizzard.com/en-us/legal/2749df07-2b53-4990-b75e-bae2c9d83695/blizzard-entertainment-limited-use-license-for-fan-art).

---

## Lookup Pattern for New Liquipedia Units

For any unit or building in the game, try:
```
https://liquipedia.net/commons/images/<first>/<second>/SC2<UnitName>.jpg
```
Or look up the Liquipedia page for the unit and inspect the infobox image URL. The pattern is consistent across all SC2 units and buildings.

The hash path (`<first>/<second>`) varies per file but follows MD5 sharding — you need to look it up from the page rather than guessing it.

---

## TODO — Expand Index When These Phases Begin

These gaps are intentionally deferred. When the relevant phase starts, do a focused web search and add what you find here.

### Atmospheric / Hero Images (needed now for any new blog post)
The wallpaper collection sites are indexed above but we have no direct URLs ready to use.
When writing any new blog post that needs a wide cinematic header, pull one from the wallpaper sites and add the direct URL + local filename here.
- **Trigger:** any new blog post that needs a post header image (not a unit portrait)
- **Sources to check first:** Alpha Coders, Wallpaper Flare (4K options), WallpaperAccess

### AI Arena / SC2 Bot Competition Imagery (Phase 3+)
When we write about competing on the ladder or the competitive bot ecosystem, screenshots from the AI Arena leaderboard, match results, or race breakdowns will be useful.
- **Trigger:** Phase 3 blog posts, or any post mentioning AI Arena / bot laddering
- **Source:** https://aiarena.net — screenshots of leaderboard, race distribution, match replays

### SC2 Map Screenshots with Terrain Overlays (Phase 3+)
Relevant when we implement map analysis (SC2MapAnalysis Python sidecar, influence maps, choke points, expansion locations). A top-down map view with coloured region overlays would be ideal.
- **Trigger:** Phase 3 blog posts covering `GameMap`, terrain analysis, or the Python sidecar
- **Sources:** SC2MapAnalysis GitHub repo has example images; generate your own from the sidecar once it's running

### Terran and Zerg Icons (opportunistic)
Useful if we write a post explaining why we chose Protoss, or covering the broader SC2 ecosystem.
- **Trigger:** any post contrasting the three races
- **Likely URLs:** `https://liquipedia.net/commons/images/3/33/TerranIcon.png` and `https://liquipedia.net/commons/images/5/5e/ZergIcon.png` (unverified — check before using)
