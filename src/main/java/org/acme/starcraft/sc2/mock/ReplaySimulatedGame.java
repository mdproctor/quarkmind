package org.acme.starcraft.sc2.mock;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.IPlayerStatsEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import org.acme.starcraft.domain.*;
import org.acme.starcraft.sc2.intent.Intent;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
    static final int LOOPS_PER_TICK = 22;

    private static final Set<String> BUILDING_NAMES = Set.of(
        "Nexus", "Pylon", "Gateway", "CyberneticsCore", "Assimilator",
        "RoboticsFacility", "Stargate", "Forge", "TwilightCouncil",
        "PhotonCannon", "ShieldBattery", "RoboticsBay", "FleetBeacon",
        "TemplarArchives", "DarkShrine", "WarpGate"
    );

    private final Event[] trackerEvents;
    private final int watchedPlayerId;

    /** Tags of buildings that started construction (UnitInit) awaiting UnitDone. */
    private final Map<String, Building> pendingBuildings = new HashMap<>();

    private int eventCursor;
    private long currentLoop;

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

        if (BUILDING_NAMES.contains(unitName)) {
            // Initial structures (Nexus at loop 0) arrive as UnitBorn, already complete
            if (ctrlId != null && ctrlId == watchedPlayerId) {
                BuildingType bt = toBuildingType(unitName);
                if (bt != BuildingType.UNKNOWN) {
                    Point2d pos = new Point2d(event.getXCoord(), event.getYCoord());
                    addBuilding(new Building(tag, bt, pos, defaultBuildingHealth(bt), defaultBuildingHealth(bt), true));
                }
            }
        } else {
            // Unit (Probe, Zealot, etc.) — fully trained; skip unrecognized SC2-internal types
            UnitType ut = toUnitType(unitName);
            if (ut == UnitType.UNKNOWN) return;
            if (ctrlId != null && ctrlId == watchedPlayerId) {
                Point2d pos = new Point2d(event.getXCoord(), event.getYCoord());
                addUnit(new Unit(tag, ut, pos, defaultUnitHealth(ut), defaultUnitHealth(ut)));
            } else if (ctrlId != null && ctrlId != 0) {
                // Enemy unit — visible on map
                Point2d pos = new Point2d(event.getXCoord(), event.getYCoord());
                spawnEnemyUnit(ut, pos);
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
        removeBuildingByTag(tag);
        pendingBuildings.remove(tag);
    }

    private void applyUnitInit(Event rawEvent) {
        IBaseUnitEvent event = (IBaseUnitEvent) rawEvent;
        Integer ctrlId = event.getControlPlayerId();
        if (ctrlId == null || ctrlId != watchedPlayerId) return;
        String       unitName = event.getUnitTypeName().toString();
        String       tag      = makeTag(event.getUnitTagIndex(), event.getUnitTagRecycle());
        BuildingType bt       = toBuildingType(unitName);
        Point2d      pos      = new Point2d(event.getXCoord(), event.getYCoord());
        Building b = new Building(tag, bt, pos, defaultBuildingHealth(bt), defaultBuildingHealth(bt), false);
        pendingBuildings.put(tag, b);
        addBuilding(b);
    }

    private void applyUnitDone(Event event) {
        // UnitDoneEvent has the tag in its raw struct but no typed accessor
        Integer tagIndex   = event.get("unitTagIndex");
        Integer tagRecycle = event.get("unitTagRecycle");
        if (tagIndex == null || tagRecycle == null) return;
        String tag = makeTag(tagIndex, tagRecycle);
        if (pendingBuildings.remove(tag) != null) {
            markBuildingComplete(tag);
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

    private static UnitType toUnitType(String name) {
        return switch (name) {
            case "Probe"        -> UnitType.PROBE;
            case "Zealot"       -> UnitType.ZEALOT;
            case "Stalker"      -> UnitType.STALKER;
            case "Immortal"     -> UnitType.IMMORTAL;
            case "Colossus"     -> UnitType.COLOSSUS;
            case "Carrier"      -> UnitType.CARRIER;
            case "DarkTemplar"  -> UnitType.DARK_TEMPLAR;
            case "HighTemplar"  -> UnitType.HIGH_TEMPLAR;
            case "Archon"       -> UnitType.ARCHON;
            case "Observer"     -> UnitType.OBSERVER;
            case "VoidRay"      -> UnitType.VOID_RAY;
            default             -> UnitType.UNKNOWN;
        };
    }

    private static BuildingType toBuildingType(String name) {
        return switch (name) {
            case "Nexus"             -> BuildingType.NEXUS;
            case "Pylon"             -> BuildingType.PYLON;
            case "Gateway", "WarpGate" -> BuildingType.GATEWAY;
            case "CyberneticsCore"   -> BuildingType.CYBERNETICS_CORE;
            case "Assimilator"       -> BuildingType.ASSIMILATOR;
            case "RoboticsFacility"  -> BuildingType.ROBOTICS_FACILITY;
            case "Stargate"          -> BuildingType.STARGATE;
            case "Forge"             -> BuildingType.FORGE;
            case "TwilightCouncil"   -> BuildingType.TWILIGHT_COUNCIL;
            default                  -> BuildingType.UNKNOWN;
        };
    }

    private static int defaultUnitHealth(UnitType type) {
        return switch (type) {
            case PROBE        -> 45;
            case ZEALOT       -> 100;
            case STALKER      -> 80;
            case IMMORTAL     -> 200;
            case COLOSSUS     -> 200;
            case OBSERVER     -> 40;
            default           -> 100;
        };
    }

    private static int defaultBuildingHealth(BuildingType type) {
        return switch (type) {
            case NEXUS             -> 1500;
            case PYLON             -> 200;
            case GATEWAY           -> 500;
            case CYBERNETICS_CORE  -> 550;
            case ASSIMILATOR       -> 450;
            case ROBOTICS_FACILITY -> 500;
            case STARGATE          -> 600;
            case FORGE             -> 400;
            case TWILIGHT_COUNCIL  -> 500;
            default                -> 400;
        };
    }
}
