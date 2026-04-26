package io.quarkmind.sc2.mock;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.IPlayerStatsEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.Intent;
import io.quarkmind.sc2.replay.UnitOrder;
import io.quarkmind.sc2.replay.UnitOrderTracker;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A SimulatedGame variant that drives state from real replay tracker events.
 * Not a CDI bean — construct directly in tests or replay-driven harnesses.
 * <p>
 * Advances {@link #LOOPS_PER_TICK} game loops per {@link #tick()} call
 * (~1 second at SC2 Faster speed). State is derived entirely from
 * {@code PlayerStatsEvent}, {@code UnitBornEvent}, {@code UnitInitEvent},
 * {@code UnitDoneEvent}, and {@code UnitDiedEvent} for the watched player.
 * <p>
 * {@link #applyIntent(Intent)} is a no-op — replay events are authoritative.
 */
public class ReplaySimulatedGame extends SimulatedGame {

    /** Game loops per tick — 22.4/sec at Faster speed, rounded to 22. */
    static final int LOOPS_PER_TICK = Sc2ReplayShared.LOOPS_PER_TICK;

    private final Event[] trackerEvents;
    private final int watchedPlayerId;

    /** Tags of buildings that started construction (UnitInit) awaiting UnitDone. */
    private final Map<String, Building> pendingBuildings = new HashMap<>();

    private int eventCursor;
    private long currentLoop;
    private UnitOrderTracker orderTracker;
    private long totalLoops = 0;

    /**
     * @param replayFile      path to a parseable .SC2Replay file
     * @param watchedPlayerId 1-indexed player ID to track as "our" player (1 or 2)
     * @throws IllegalArgumentException if the replay cannot be parsed
     */
    public ReplaySimulatedGame(Path replayFile, int watchedPlayerId) {
        Replay replay = RepParserEngine.parseReplay(replayFile, EnumSet.of(RepContent.TRACKER_EVENTS));
        if (replay == null || replay.trackerEvents == null) {
            throw new IllegalArgumentException("Cannot parse replay: " + replayFile);
        }
        this.trackerEvents = replay.trackerEvents.getEvents();
        this.watchedPlayerId = watchedPlayerId;
        if (trackerEvents.length > 0) {
            this.totalLoops = trackerEvents[trackerEvents.length - 1].getLoop();
        }
        reset();
    }

    /**
     * Resets to loop 0 and replays all events at that loop (initial Nexus + Probes).
     */
    @Override
    public synchronized void reset() {
        setMinerals(0);
        setVespene(0);
        setSupply(0);
        setSupplyUsed(0);
        clearAll();
        setGameFrame(0);
        pendingBuildings.clear();
        if (orderTracker != null) orderTracker.reset();
        eventCursor = 0;
        currentLoop = 0;
        drainEventsUpTo(0);
    }

    /**
     * Advances by {@link #LOOPS_PER_TICK} game loops and applies all tracker events in that window.
     */
    @Override
    public synchronized void tick() {
        currentLoop += LOOPS_PER_TICK;
        setGameFrame(currentLoop / LOOPS_PER_TICK);
        drainEventsUpTo(currentLoop);
        if (orderTracker != null) advanceMovement();
    }

    /** Intents are ignored — replay state is authoritative. */
    @Override
    public synchronized void applyIntent(Intent intent) {
        // no-op
    }

    private void drainEventsUpTo(long targetLoop) {
        while (eventCursor < trackerEvents.length) {
            Event event = trackerEvents[eventCursor];
            if (event.getLoop() > targetLoop) break;
            eventCursor++;
            applyTrackerEvent(event);
        }
    }

    private void applyTrackerEvent(Event event) {
        switch (event.getId()) {
            case ITrackerEvents.ID_PLAYER_STATS -> applyPlayerStats(event);
            case ITrackerEvents.ID_UNIT_BORN    -> applyUnitBorn(event);
            case ITrackerEvents.ID_UNIT_DIED    -> applyUnitDied(event);
            case ITrackerEvents.ID_UNIT_INIT    -> applyUnitInit(event);
            case ITrackerEvents.ID_UNIT_DONE    -> applyUnitDone(event);
        }
    }

    private void applyPlayerStats(Event rawEvent) {
        // Tracker events always have userId=-1; player is identified by getPlayerId() (1-indexed)
        Integer playerId = rawEvent.getPlayerId();
        if (playerId == null || playerId != watchedPlayerId) return;
        IPlayerStatsEvent event = (IPlayerStatsEvent) rawEvent;
        Integer mins     = event.getMineralsCurrent();
        Integer gas      = event.getGasCurrent();
        Integer foodUsed = event.getFoodUsed();
        Integer foodMade = event.getFoodMade();
        if (mins     != null) setMinerals(mins);
        if (gas      != null) setVespene(gas);
        // Food values are fixed-point ×4096
        if (foodUsed != null) setSupplyUsed(foodUsed / 4096);
        if (foodMade != null) setSupply(foodMade / 4096);
    }

    private void applyUnitBorn(Event rawEvent) {
        IBaseUnitEvent event = (IBaseUnitEvent) rawEvent;
        String unitName = event.getUnitTypeName().toString();
        String tag      = makeTag(event.getUnitTagIndex(), event.getUnitTagRecycle());
        Integer ctrlId  = event.getControlPlayerId();

        if (ctrlId != null && ctrlId == 0) {
            // Neutral unit — mineral patches and geysers
            Point2d pos = new Point2d(event.getXCoord(), event.getYCoord());
            if (Sc2ReplayShared.isGeyser(unitName)) {
                addGeyser(new Resource(tag, pos, Sc2ReplayShared.defaultGeyserAmount(unitName)));
            } else if (Sc2ReplayShared.isMineralPatch(unitName)) {
                addMineralPatch(new Resource(tag, pos, Sc2ReplayShared.defaultMineralAmount(unitName)));
            }
            // Other neutral types (rocks, debris) are ignored
        } else if (Sc2ReplayShared.BUILDING_NAMES.contains(unitName)) {
            BuildingType bt = toBuildingType(unitName);
            Point2d pos = new Point2d(event.getXCoord(), event.getYCoord());
            if (ctrlId != null && ctrlId == watchedPlayerId) {
                // Initial friendly structures (Nexus at loop 0) arrive as UnitBorn, already complete
                if (bt != BuildingType.UNKNOWN) {
                    addBuilding(new Building(tag, bt, pos, defaultBuildingHealth(bt), defaultBuildingHealth(bt), true));
                }
            } else if (ctrlId != null && ctrlId != 0) {
                // Enemy building — track it
                if (bt != BuildingType.UNKNOWN) {
                    addEnemyBuilding(new Building(tag, bt, pos, defaultBuildingHealth(bt), defaultBuildingHealth(bt), true));
                }
            }
        } else {
            // Unit (Probe, Zealot, etc.) — fully trained; skip unrecognized SC2-internal types
            UnitType ut = toUnitType(unitName);
            if (ut == UnitType.UNKNOWN) return;
            if (ctrlId != null && ctrlId == watchedPlayerId) {
                Point2d pos = new Point2d(event.getXCoord(), event.getYCoord());
                // Replay tracker events don't include instantaneous shield values — set to 0.
                addUnit(new Unit(tag, ut, pos, defaultUnitHealth(ut), defaultUnitHealth(ut), 0, 0, 0, 0));
            } else if (ctrlId != null) {
                // Enemy unit — use tracker-event tag so GAME_EVENTS movement orders can be matched
                Point2d pos = new Point2d(event.getXCoord(), event.getYCoord());
                addEnemyUnit(new Unit(tag, ut, pos, defaultUnitHealth(ut), defaultUnitHealth(ut), 0, 0, 0, 0));
            }
        }
    }

    private void applyUnitDied(Event event) {
        // UnitDied has no typed class — access tag via raw struct
        Integer tagIndex   = event.get("unitTagIndex");
        Integer tagRecycle = event.get("unitTagRecycle");
        if (tagIndex == null || tagRecycle == null) return;
        String tag = makeTag(tagIndex, tagRecycle);
        removeUnitByTag(tag);
        removeEnemyByTag(tag);
        removeBuildingByTag(tag);
        removeEnemyBuildingByTag(tag);
        removeGeyserByTag(tag);
        removeMineralPatchByTag(tag);
        pendingBuildings.remove(tag);
        if (orderTracker != null) orderTracker.removeUnit(tag);
    }

    private void applyUnitInit(Event rawEvent) {
        IBaseUnitEvent event = (IBaseUnitEvent) rawEvent;
        Integer ctrlId = event.getControlPlayerId();
        if (ctrlId == null) return;
        String       unitName = event.getUnitTypeName().toString();
        String       tag      = makeTag(event.getUnitTagIndex(), event.getUnitTagRecycle());
        BuildingType bt       = toBuildingType(unitName);
        Point2d      pos      = new Point2d(event.getXCoord(), event.getYCoord());
        if (ctrlId == watchedPlayerId) {
            Building b = new Building(tag, bt, pos, defaultBuildingHealth(bt), defaultBuildingHealth(bt), false);
            pendingBuildings.put(tag, b);
            addBuilding(b);
        } else if (ctrlId != 0 && bt != BuildingType.UNKNOWN) {
            // Enemy building under construction — add immediately as incomplete
            Building b = new Building(tag, bt, pos, defaultBuildingHealth(bt), defaultBuildingHealth(bt), false);
            pendingBuildings.put(tag, b);
            addEnemyBuilding(b);
        }
    }

    private void applyUnitDone(Event event) {
        // UnitDoneEvent has the tag in its raw struct but no typed accessor
        Integer tagIndex   = event.get("unitTagIndex");
        Integer tagRecycle = event.get("unitTagRecycle");
        if (tagIndex == null || tagRecycle == null) return;
        String tag = makeTag(tagIndex, tagRecycle);
        Building pending = pendingBuildings.remove(tag);
        if (pending != null) {
            markBuildingComplete(tag);
            markEnemyBuildingComplete(tag);
        }
    }

    // --- Movement integration ---

    public void loadOrders(List<UnitOrder> orders) {
        this.orderTracker = new UnitOrderTracker();
        this.orderTracker.loadOrders(orders);
    }

    public long totalLoops()  { return totalLoops; }
    public long currentLoop() { return currentLoop; }

    public synchronized void seekTo(long targetLoop) {
        reset();
        while (currentLoop < targetLoop && !isComplete()) {
            currentLoop += LOOPS_PER_TICK;
            setGameFrame(currentLoop / LOOPS_PER_TICK);
            drainEventsUpTo(currentLoop);
            if (orderTracker != null) advanceMovement();
        }
    }

    private void advanceMovement() {
        Map<String, Point2d> positions = new HashMap<>();
        Map<String, UnitType> types    = new HashMap<>();
        for (var u : getMyUnits())    { positions.put(u.tag(), u.position()); types.put(u.tag(), u.type()); }
        for (var u : getEnemyUnits()) { positions.put(u.tag(), u.position()); types.put(u.tag(), u.type()); }

        orderTracker.advance(currentLoop, positions, types);

        for (var e : positions.entrySet()) {
            replaceUnitPosition(e.getKey(), e.getValue());
            replaceEnemyPosition(e.getKey(), e.getValue());
        }
    }

    // --- Replay metadata ---

    /** Total number of tracker events in this replay. */
    public int eventCount() { return trackerEvents.length; }

    /** True when all tracker events have been processed. */
    public boolean isComplete() { return eventCursor >= trackerEvents.length; }

    // --- Helpers ---

    private static String makeTag(int index, int recycle) {
        return "r-" + index + "-" + recycle;
    }

    /** Thin delegate — preserves package-private accessibility for {@link ReplaySimulatedGameUnitTypeTest}. */
    static UnitType toUnitType(String name) { return Sc2ReplayShared.toUnitType(name); }

    private static BuildingType toBuildingType(String name)       { return Sc2ReplayShared.toBuildingType(name); }
    private static int defaultUnitHealth(UnitType type)           { return Sc2ReplayShared.defaultUnitHealth(type); }
    private static int defaultBuildingHealth(BuildingType type)   { return Sc2ReplayShared.defaultBuildingHealth(type); }
}
