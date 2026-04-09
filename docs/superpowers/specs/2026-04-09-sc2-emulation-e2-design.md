# SC2 Emulation Engine — Phase E2: Movement, Scripted Enemy, Intent Handling
**Date:** 2026-04-09
**Supersedes:** `2026-04-09-sc2-emulation-e1-design.md`

---

## Context

Phase E1 gave us probe-driven mineral harvesting and the `%emulated` profile. Units
exist as positions in the `GameState` but never move, and `applyIntent()` is a no-op.
Phase E2 makes the emulation come alive:

- Friendly units move when plugins issue `MoveIntent` or `AttackIntent`
- `TrainIntent` and `BuildIntent` deduct resources and complete after real build times
- A scripted enemy wave spawns at a configurable frame and advances toward the nexus
- All settings are tunable via `application.properties` with hardcoded fallbacks, and
  can be adjusted live through a config panel in the visualizer

**Demo after E2:** the strategy plugin switches to DEFEND, scouting detects the
approaching 4-gate, tactics issues attack orders, units visibly move on the map.

---

## Scope (Phase E2 only)

**In scope:**
- `SC2Data` — mineral costs, gas costs, unit speed constant
- `EmulatedConfig` — CDI bean; Quarkus `@ConfigProperty` + runtime-mutable fields
- `EmulatedConfigResource` — `GET/PUT /qa/emulated/config` REST endpoint
- `EmulatedGame` — movement, pending completions, enemy wave spawning, full intent handling
- `EmulatedEngine` — inject `EmulatedConfig`; sync live speed each tick
- Visualizer config panel — HTML sidebar; `fetch PUT` on change; hides when not in `%emulated`

**Explicitly out of scope:**
- Combat (damage, death) — Phase E3
- Pathfinding — Phase E5
- Enemy economy (enemy doesn't build, just waves) — Phase E4
- Probe auto-movement to minerals (`miningProbes` count stays correct; visual idle)
- Gas harvesting — after Phase E3

---

## File Map

| Action | File | Purpose |
|---|---|---|
| Modify | `src/main/java/io/quarkmind/domain/SC2Data.java` | Add mineral/gas costs |
| Create | `src/main/java/io/quarkmind/sc2/emulated/EnemyWave.java` | Wave definition record |
| Create | `src/main/java/io/quarkmind/qa/EmulatedConfig.java` | Config bean (properties + live override) |
| Create | `src/main/java/io/quarkmind/qa/EmulatedConfigResource.java` | GET/PUT /qa/emulated/config |
| Modify | `src/main/java/io/quarkmind/sc2/emulated/EmulatedGame.java` | Movement, intents, waves |
| Modify | `src/main/java/io/quarkmind/sc2/emulated/EmulatedEngine.java` | Inject config, sync speed |
| Modify | `src/main/resources/META-INF/resources/visualizer.html` | Config panel sidebar |
| Modify | `src/main/resources/META-INF/resources/visualizer.js` | Config panel JS |
| Modify | `src/test/java/io/quarkmind/sc2/emulated/EmulatedGameTest.java` | New E2 tests |
| Create | `src/test/java/io/quarkmind/qa/EmulatedConfigResourceTest.java` | @QuarkusTest config endpoint |

---

## Part 1: `SC2Data` additions

New static methods alongside existing ones:

```java
public static int mineralCost(UnitType type) {
    return switch (type) {
        case PROBE    -> 50;
        case ZEALOT   -> 100;
        case STALKER  -> 125;
        case IMMORTAL -> 250;
        case OBSERVER -> 25;
        default       -> 100;
    };
}

public static int mineralCost(BuildingType type) {
    return switch (type) {
        case NEXUS             -> 400;
        case PYLON             -> 100;
        case GATEWAY           -> 150;
        case CYBERNETICS_CORE  -> 150;
        case ASSIMILATOR       -> 75;
        case ROBOTICS_FACILITY -> 200;
        case STARGATE          -> 150;
        case FORGE             -> 150;
        case TWILIGHT_COUNCIL  -> 150;
        default                -> 100;
    };
}

public static int gasCost(UnitType type) {
    return switch (type) {
        case STALKER  -> 50;
        case IMMORTAL -> 100;
        case OBSERVER -> 75;
        default       -> 0;
    };
}
```

---

## Part 2: `EnemyWave`

```java
package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
import java.util.List;

/** A scheduled enemy spawn event. Consumed by EmulatedGame.tick() when spawnFrame is reached. */
record EnemyWave(long spawnFrame, List<UnitType> unitTypes,
                 Point2d spawnPosition, Point2d targetPosition) {}
```

Package-private — only `EmulatedGame` creates and consumes waves.

---

## Part 3: `EmulatedConfig`

`@ApplicationScoped` (no profile guard — the defaults are harmless in any profile).
`EmulatedConfigResource` guards API access with `@UnlessBuildProfile("prod")`.

```java
package io.quarkmind.qa;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class EmulatedConfig {

    // Layer 1: read from application.properties; hardcoded fallback if absent
    @ConfigProperty(name = "emulated.wave.spawn-frame", defaultValue = "200")
    int defaultWaveSpawnFrame;

    @ConfigProperty(name = "emulated.wave.unit-count", defaultValue = "4")
    int defaultWaveUnitCount;

    @ConfigProperty(name = "emulated.wave.unit-type", defaultValue = "ZEALOT")
    String defaultWaveUnitType;

    @ConfigProperty(name = "emulated.unit.speed", defaultValue = "0.5")
    double defaultUnitSpeed;

    // Layer 2: runtime-mutable (volatile for thread safety across tick/REST threads)
    private volatile int    waveSpawnFrame;
    private volatile int    waveUnitCount;
    private volatile String waveUnitType;
    private volatile double unitSpeed;

    @PostConstruct
    void init() {
        waveSpawnFrame = defaultWaveSpawnFrame;
        waveUnitCount  = defaultWaveUnitCount;
        waveUnitType   = defaultWaveUnitType;
        unitSpeed      = defaultUnitSpeed;
    }

    // Getters (read by EmulatedGame each tick for live speed, each reset for waves)
    public int    getWaveSpawnFrame() { return waveSpawnFrame; }
    public int    getWaveUnitCount()  { return waveUnitCount;  }
    public String getWaveUnitType()   { return waveUnitType;   }
    public double getUnitSpeed()      { return unitSpeed;      }

    // Setters (called by EmulatedConfigResource on PUT)
    public void setWaveSpawnFrame(int v)    { this.waveSpawnFrame = v; }
    public void setWaveUnitCount(int v)     { this.waveUnitCount  = v; }
    public void setWaveUnitType(String v)   { this.waveUnitType   = v; }
    public void setUnitSpeed(double v)      { this.unitSpeed      = v; }

    /** Serialisable snapshot for the REST response body. */
    public record Snapshot(int waveSpawnFrame, int waveUnitCount,
                           String waveUnitType, double unitSpeed) {}

    public Snapshot snapshot() {
        return new Snapshot(waveSpawnFrame, waveUnitCount, waveUnitType, unitSpeed);
    }
}
```

---

## Part 4: `EmulatedConfigResource`

```java
package io.quarkmind.qa;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@UnlessBuildProfile("prod")
@Path("/qa/emulated/config")
@Produces(MediaType.APPLICATION_JSON)
public class EmulatedConfigResource {

    @Inject EmulatedConfig config;

    @GET
    public EmulatedConfig.Snapshot getConfig() {
        return config.snapshot();
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateConfig(Map<String, Object> updates) {
        if (updates.containsKey("waveSpawnFrame"))
            config.setWaveSpawnFrame(((Number) updates.get("waveSpawnFrame")).intValue());
        if (updates.containsKey("waveUnitCount"))
            config.setWaveUnitCount(((Number) updates.get("waveUnitCount")).intValue());
        if (updates.containsKey("waveUnitType"))
            config.setWaveUnitType((String) updates.get("waveUnitType"));
        if (updates.containsKey("unitSpeed"))
            config.setUnitSpeed(((Number) updates.get("unitSpeed")).doubleValue());
        return Response.ok(config.snapshot()).build();
    }
}
```

Partial updates supported: only fields present in the JSON body are changed.

---

## Part 5: `EmulatedGame` additions

### New fields

```java
// Movement
private double unitSpeed;                             // tiles/tick; updated by EmulatedEngine each tick
private final Map<String, Point2d> unitTargets  = new HashMap<>();  // friendly unit tag → target
private final Map<String, Point2d> enemyTargets = new HashMap<>();  // enemy tag → target (nexus)

// Timers
private final List<EnemyWave>         pendingWaves       = new ArrayList<>();
private final List<PendingCompletion> pendingCompletions = new ArrayList<>();
private int nextTag = 200;

private record PendingCompletion(long completesAtTick, Runnable action) {}
```

### `reset()` additions

After clearing existing collections:
```java
unitTargets.clear();
enemyTargets.clear();
pendingCompletions.clear();
nextTag = 200;
// Wave is configured externally by EmulatedEngine before calling reset()
```

### New `configureWave()` — called by `EmulatedEngine` before `reset()`

```java
void configureWave(long spawnFrame, int unitCount, UnitType unitType) {
    pendingWaves.clear();
    List<UnitType> types = Collections.nCopies(unitCount, unitType);
    pendingWaves.add(new EnemyWave(
        spawnFrame,
        new ArrayList<>(types),
        new Point2d(26, 26),   // enemy spawn — far corner
        new Point2d(8, 8)      // target — our nexus
    ));
}
```

### New `setUnitSpeed()` — called by `EmulatedEngine` each tick

```java
void setUnitSpeed(double speed) { this.unitSpeed = speed; }
```

### `tick()` additions

```java
public void tick() {
    gameFrame++;
    mineralAccumulator += miningProbes * SC2Data.MINERALS_PER_PROBE_PER_TICK;
    moveFriendlyUnits();
    moveEnemyUnits();
    fireCompletions();
    spawnEnemyWaves();
}
```

### Movement helpers

```java
private void moveFriendlyUnits() {
    myUnits.replaceAll(u -> {
        Point2d target = unitTargets.get(u.tag());
        if (target == null) return u;
        Point2d newPos = stepToward(u.position(), target, unitSpeed);
        if (distance(newPos, target) < 0.2) unitTargets.remove(u.tag());
        return new Unit(u.tag(), u.type(), newPos, u.health(), u.maxHealth());
    });
}

private static final Point2d NEXUS_POS = new Point2d(8, 8);

private void moveEnemyUnits() {
    enemyUnits.replaceAll(u -> {
        Point2d target = enemyTargets.getOrDefault(u.tag(), NEXUS_POS);
        Point2d newPos = stepToward(u.position(), target, unitSpeed);
        return new Unit(u.tag(), u.type(), newPos, u.health(), u.maxHealth());
    });
}

// Package-private for testing
static Point2d stepToward(Point2d from, Point2d to, double speed) {
    double dx = to.x() - from.x();
    double dy = to.y() - from.y();
    double dist = Math.sqrt(dx * dx + dy * dy);
    if (dist <= speed) return to;
    return new Point2d(
        (float)(from.x() + dx * speed / dist),
        (float)(from.y() + dy * speed / dist));
}

static double distance(Point2d a, Point2d b) {
    double dx = a.x() - b.x(), dy = a.y() - b.y();
    return Math.sqrt(dx * dx + dy * dy);
}
```

### Wave spawning and completions

```java
private void spawnEnemyWaves() {
    pendingWaves.removeIf(wave -> {
        if (wave.spawnFrame() > gameFrame) return false;
        for (int i = 0; i < wave.unitTypes().size(); i++) {
            UnitType type = wave.unitTypes().get(i);
            Point2d pos   = new Point2d(wave.spawnPosition().x() + i * 0.5f,
                                        wave.spawnPosition().y());
            String tag = "enemy-" + nextTag++;
            int hp = SC2Data.maxHealth(type);
            enemyUnits.add(new Unit(tag, type, pos, hp, hp));
            enemyTargets.put(tag, wave.targetPosition());
        }
        log.infof("[EMULATED] Enemy wave spawned: %dx%s at frame %d",
            wave.unitTypes().size(), wave.unitTypes().get(0), gameFrame);
        return true;
    });
}

private void fireCompletions() {
    pendingCompletions.removeIf(item -> {
        if (item.completesAtTick() > gameFrame) return false;
        item.action().run();
        return true;
    });
}
```

### `applyIntent()` — full implementation

```java
public void applyIntent(Intent intent) {
    switch (intent) {
        case MoveIntent   m -> setTarget(m.unitTag(), m.targetLocation());
        case AttackIntent a -> setTarget(a.unitTag(), a.targetLocation());
        case TrainIntent  t -> handleTrain(t);
        case BuildIntent  b -> handleBuild(b);
    }
}

private void setTarget(String tag, Point2d target) {
    if (myUnits.stream().anyMatch(u -> u.tag().equals(tag))) {
        unitTargets.put(tag, target);
        log.debugf("[EMULATED] %s → (%.1f,%.1f)", tag, target.x(), target.y());
    }
}

private void handleTrain(TrainIntent t) {
    int mCost = SC2Data.mineralCost(t.unitType());
    int gCost = SC2Data.gasCost(t.unitType());
    int sCost = SC2Data.supplyCost(t.unitType());
    if ((int) mineralAccumulator < mCost || vespene < gCost || supplyUsed + sCost > supply) {
        log.debugf("[EMULATED] Cannot train %s — resources insufficient", t.unitType());
        return;
    }
    mineralAccumulator -= mCost;
    vespene -= gCost;
    long completesAt = gameFrame + SC2Data.trainTimeInTicks(t.unitType());
    pendingCompletions.add(new PendingCompletion(completesAt, () -> {
        supplyUsed += sCost;
        String tag = "unit-" + nextTag++;
        int hp = SC2Data.maxHealth(t.unitType());
        myUnits.add(new Unit(tag, t.unitType(), new Point2d(9, 9), hp, hp));
        log.debugf("[EMULATED] Trained %s (tag=%s)", t.unitType(), tag);
    }));
}

private void handleBuild(BuildIntent b) {
    int mCost = SC2Data.mineralCost(b.buildingType());
    if ((int) mineralAccumulator < mCost) {
        log.debugf("[EMULATED] Cannot build %s — insufficient minerals", b.buildingType());
        return;
    }
    mineralAccumulator -= mCost;
    String tag = "bldg-" + nextTag++;
    BuildingType bt = b.buildingType();
    myBuildings.add(new Building(tag, bt, b.location(),
        SC2Data.maxBuildingHealth(bt), SC2Data.maxBuildingHealth(bt), false));
    long completesAt = gameFrame + SC2Data.buildTimeInTicks(bt);
    pendingCompletions.add(new PendingCompletion(completesAt, () -> {
        markBuildingComplete(tag);
        supply += SC2Data.supplyBonus(bt);
        log.debugf("[EMULATED] Completed %s (tag=%s)", bt, tag);
    }));
}

private void markBuildingComplete(String tag) {
    myBuildings.replaceAll(b -> b.tag().equals(tag)
        ? new Building(b.tag(), b.type(), b.position(), b.health(), b.maxHealth(), true)
        : b);
}
```

### Package-private setters for `EmulatedGameTest`

```java
void setUnitSpeedForTesting(double speed)      { this.unitSpeed = speed; }
void setWaveSpawnFrameForTesting(long frame)   { configureWave(frame, 4, UnitType.ZEALOT); }
```

---

## Part 6: `EmulatedEngine` changes

```java
@Inject EmulatedConfig config;   // new field

@Override
public void joinGame() {
    game.configureWave(
        config.getWaveSpawnFrame(),
        config.getWaveUnitCount(),
        UnitType.valueOf(config.getWaveUnitType()));
    game.reset();
    log.info("[EMULATED] Joined game — movement + scripted enemy active");
}

@Override
public void tick() {
    game.setUnitSpeed(config.getUnitSpeed());  // live speed from config
    game.tick();
}
```

---

## Part 7: Visualizer config panel

### `visualizer.html` changes

Wrap existing layout in a flex container; add config sidebar:

```html
<body>
    <h1>QUARKMIND</h1>
    <div style="display:flex; align-items:flex-start; gap:8px;">
        <div id="game"></div>
        <div id="config-panel" style="display:none; width:180px; background:#0d0d20;
             color:#ccccff; padding:12px; font-family:monospace; font-size:12px;
             border:1px solid #2a2a4e;">
            <div style="color:#8888ff; font-size:13px; margin-bottom:10px;">WAVE CONFIG</div>

            <label>Spawn frame</label>
            <input id="cfg-wave-frame" type="number" value="200" min="1" max="2000"
                   style="width:100%; margin-bottom:8px; background:#1a1a3e; color:#ccc; border:1px solid #333;">

            <label>Enemy count</label>
            <input id="cfg-unit-count" type="number" value="4" min="1" max="12"
                   style="width:100%; margin-bottom:8px; background:#1a1a3e; color:#ccc; border:1px solid #333;">

            <label>Unit type</label>
            <select id="cfg-unit-type"
                    style="width:100%; margin-bottom:12px; background:#1a1a3e; color:#ccc; border:1px solid #333;">
                <option value="ZEALOT">Zealot</option>
                <option value="STALKER">Stalker</option>
                <option value="ROACH">Roach</option>
                <option value="MARINE">Marine</option>
            </select>

            <div style="color:#8888ff; font-size:13px; margin-bottom:8px;">PHYSICS</div>
            <label>Speed: <span id="cfg-speed-val">0.5</span> t/tick</label>
            <input id="cfg-speed" type="range" min="0.05" max="1.5" step="0.05" value="0.5"
                   style="width:100%; margin-bottom:12px;">

            <button id="cfg-apply"
                    style="width:100%; margin-bottom:4px; background:#1a2a4e; color:#ccccff; border:1px solid #444; padding:4px; cursor:pointer;">
                Apply Changes
            </button>
            <button id="cfg-restart"
                    style="width:100%; background:#2a1a1e; color:#ffaaaa; border:1px solid #444; padding:4px; cursor:pointer;">
                Restart Game
            </button>
            <div id="cfg-status" style="margin-top:8px; font-size:11px; color:#88ff88; min-height:14px;"></div>
        </div>
    </div>
    <script src="/pixi.min.js"></script>
    <script src="/visualizer.js"></script>
</body>
```

### `visualizer.js` — `initConfigPanel()` function

Called at the end of `init()`. Hides the panel silently if the config endpoint doesn't
exist (i.e. not in `%emulated` profile).

```javascript
function initConfigPanel() {
    const panel  = document.getElementById('config-panel');
    const status = document.getElementById('cfg-status');
    const speedSlider = document.getElementById('cfg-speed');
    const speedVal    = document.getElementById('cfg-speed-val');

    // Probe the endpoint — show panel only in %emulated profile
    fetch('/qa/emulated/config')
        .then(r => { if (!r.ok) return null; panel.style.display = 'block'; return r.json(); })
        .then(cfg => {
            if (!cfg) return;
            document.getElementById('cfg-wave-frame').value = cfg.waveSpawnFrame;
            document.getElementById('cfg-unit-count').value = cfg.waveUnitCount;
            document.getElementById('cfg-unit-type').value  = cfg.waveUnitType;
            speedSlider.value = cfg.unitSpeed;
            speedVal.textContent = cfg.unitSpeed;
        })
        .catch(() => {}); // not in %emulated profile — panel stays hidden

    // Speed is live — send immediately on slider move
    speedSlider.addEventListener('input', () => {
        speedVal.textContent = speedSlider.value;
        sendConfig({ unitSpeed: parseFloat(speedSlider.value) });
    });

    // Apply button — sends wave + speed config (wave takes effect on next restart)
    document.getElementById('cfg-apply').addEventListener('click', () => {
        sendConfig(currentConfig()).then(() => showStatus('Applied — restart to activate wave'));
    });

    // Restart — apply config then call /sc2/start
    document.getElementById('cfg-restart').addEventListener('click', () => {
        sendConfig(currentConfig())
            .then(() => fetch('/sc2/start', { method: 'POST' }))
            .then(() => showStatus('Game restarted'))
            .catch(() => showStatus('Restart failed', true));
    });

    function currentConfig() {
        return {
            waveSpawnFrame: parseInt(document.getElementById('cfg-wave-frame').value),
            waveUnitCount:  parseInt(document.getElementById('cfg-unit-count').value),
            waveUnitType:   document.getElementById('cfg-unit-type').value,
            unitSpeed:      parseFloat(speedSlider.value),
        };
    }

    function sendConfig(partial) {
        return fetch('/qa/emulated/config', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(partial),
        }).then(r => r.json()).catch(() => showStatus('Update failed', true));
    }

    function showStatus(msg, isError = false) {
        status.textContent  = msg;
        status.style.color  = isError ? '#ff4444' : '#88ff88';
        setTimeout(() => { status.textContent = ''; }, 2500);
    }
}
```

Add `initConfigPanel()` call at the end of `init()`.

---

## Part 8: Testing

### `EmulatedGameTest` additions (plain JUnit)

| Test | Asserts |
|---|---|
| `unitMovesEachTickWhenTargetSet` | After 1 tick with target set, unit position changed |
| `unitArrivesAtTarget` | After N ticks, unit reaches target (within 0.2 tiles) |
| `moveIntentSetsTarget` | `applyIntent(MoveIntent)` → position changes on next tick |
| `attackIntentSetsTarget` | Same as move |
| `enemySpawnsAtConfiguredFrame` | `setWaveSpawnFrameForTesting(5)`, advance 5 ticks → enemy visible |
| `enemyMovesEachTickTowardNexus` | After spawn + 1 tick, enemy closer to (8,8) than spawn |
| `trainIntentDeductsMinerals` | `TrainIntent(ZEALOT)` → minerals reduced by 100 |
| `trainedUnitAppearsAfterBuildTime` | Advance 28 ticks after TrainIntent → new unit in myUnits |
| `trainBlockedIfInsufficientMinerals` | 0 minerals → TrainIntent ignored |
| `buildIntentDeductsMinerals` | `BuildIntent(PYLON)` → minerals reduced by 100 |
| `buildingCompletesAfterBuildTime` | Advance 18 ticks after BuildIntent(PYLON) → supply increased by 8 |
| `stepTowardHelper` | static method: `stepToward((0,0),(10,0),0.5)` → `(0.5,0)` |

### `EmulatedConfigResourceTest` (`@QuarkusTest`)

| Test | Asserts |
|---|---|
| `getReturnsDefaultConfig` | `GET /qa/emulated/config` → 200, `waveSpawnFrame: 200`, `unitSpeed: 0.5` |
| `putUpdatesUnitSpeed` | `PUT {unitSpeed:0.8}` → 200, response has `unitSpeed: 0.8` |
| `putPartialUpdatePreservesOtherFields` | `PUT {waveUnitCount:6}` → `waveSpawnFrame` unchanged |
| `putInvalidTypeIgnored` | `PUT {unitSpeed:"fast"}` → either 400 or graceful ignore |

---

## Phase Roadmap (updated)

| Phase | Status | Adds |
|---|---|---|
| E1 | ✅ Done | Probe harvest, scaffold |
| **E2** | **This spec** | Movement, scripted enemy, full intent handling, config UI |
| E3 | Next | Combat (DPS, range, death) |
| E4 | — | Enemy active AI (economy + attack wave) |
| E5 | — | Pathfinding + terrain |
| E6 | — | Headless SC2 Docker |

---

## Context Links

- E1 spec: `docs/superpowers/specs/2026-04-09-sc2-emulation-e1-design.md`
- Visualizer spec: `docs/superpowers/specs/2026-04-09-quarkmind-visualizer-design.md`
- GitHub: mdproctor/quarkmind
