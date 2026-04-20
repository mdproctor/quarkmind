package io.quarkmind.sc2.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.Intent;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A SimulatedGame variant that drives state from SC2EGSet pre-processed JSON replays.
 * Reads from the nested ZIP structure: outer ZIP → *_data.zip → *.SC2Replay.json.
 *
 * <p>Mirrors ReplaySimulatedGame in tick model and interface. applyIntent() is a no-op.
 * Enemy units accumulate (not removed on death) — matches ReplaySimulatedGame behaviour
 * and gives calibration data for the full 0–3 min sighting window.
 */
public class IEM10JsonSimulatedGame extends SimulatedGame {

    static final int LOOPS_PER_TICK = Sc2ReplayShared.LOOPS_PER_TICK;

    private final String          replayName;
    private final String          matchup;
    private final int             watchedPlayerId;
    private final List<JsonNode>  events;
    private final Map<String, Building> pendingBuildings = new HashMap<>();

    private int  cursor;
    private long currentLoop;

    public IEM10JsonSimulatedGame(byte[] jsonBytes, String replayName) throws IOException {
        this.replayName = replayName;
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(jsonBytes);

        JsonNode playerMap = root.get("ToonPlayerDescMap");
        int protossId  = 1;
        String enemyRace = "Prot";

        for (JsonNode player : playerMap) {
            if (player.get("race").asText().equals("Prot")) {
                protossId = player.get("playerID").asInt();
                break;
            }
        }
        for (JsonNode player : playerMap) {
            if (player.get("playerID").asInt() != protossId) {
                enemyRace = player.get("race").asText();
                break;
            }
        }

        this.watchedPlayerId = protossId;
        this.matchup         = "Pv" + raceInitial(enemyRace);

        List<JsonNode> list = new ArrayList<>();
        for (JsonNode e : root.get("trackerEvents")) list.add(e);
        this.events = Collections.unmodifiableList(list);

        reset();
    }

    // ---- SimulatedGame contract ----

    @Override
    public synchronized void reset() {
        setMinerals(0); setVespene(0); setSupply(0); setSupplyUsed(0);
        clearAll();
        setGameFrame(0);
        pendingBuildings.clear();
        cursor      = 0;
        currentLoop = 0;
        drainEventsUpTo(0);
    }

    @Override
    public synchronized void tick() {
        currentLoop += LOOPS_PER_TICK;
        setGameFrame(currentLoop / LOOPS_PER_TICK);
        drainEventsUpTo(currentLoop);
    }

    @Override
    public synchronized void applyIntent(Intent intent) { /* no-op */ }

    // ---- Navigation ----

    public boolean isComplete()  { return cursor >= events.size(); }
    public String  matchup()     { return matchup; }
    public String  replayName()  { return replayName; }

    // ---- Static factory ----

    /**
     * Enumerates all 30 games from the nested ZIP structure.
     * The outer ZIP uses BZip2 compression (method 12) — handled by Apache Commons Compress.
     * The inner _data.zip uses standard DEFLATE (method 8) — handled by java.util.zip.
     */
    public static List<IEM10JsonSimulatedGame> enumerate(Path outerZip) throws IOException {
        List<IEM10JsonSimulatedGame> games = new ArrayList<>();
        try (ZipArchiveInputStream outer = new ZipArchiveInputStream(
                Files.newInputStream(outerZip), "UTF-8", true, true)) {
            ZipArchiveEntry outerEntry;
            while ((outerEntry = outer.getNextEntry()) != null) {
                if (outerEntry.getName().endsWith("_data.zip")) {
                    byte[] innerZipBytes = outer.readAllBytes();
                    try (ZipInputStream inner = new ZipInputStream(
                            new ByteArrayInputStream(innerZipBytes))) {
                        ZipEntry innerEntry;
                        while ((innerEntry = inner.getNextEntry()) != null) {
                            if (innerEntry.getName().endsWith(".SC2Replay.json")) {
                                byte[] jsonBytes = inner.readAllBytes();
                                games.add(new IEM10JsonSimulatedGame(
                                    jsonBytes, innerEntry.getName()));
                            }
                        }
                    }
                }
            }
        }
        return games;
    }

    // ---- Event processing ----

    private void drainEventsUpTo(long targetLoop) {
        while (cursor < events.size()) {
            JsonNode e = events.get(cursor);
            if (e.get("loop").asLong() > targetLoop) break;
            cursor++;
            applyTrackerEvent(e);
        }
    }

    private void applyTrackerEvent(JsonNode e) {
        switch (e.get("evtTypeName").asText()) {
            case "UnitBorn"    -> applyUnitBorn(e);
            case "PlayerStats" -> applyPlayerStats(e);
            case "UnitDied"    -> applyUnitDied(e);
            case "UnitInit"    -> applyUnitInit(e);
            case "UnitDone"    -> applyUnitDone(e);
        }
    }

    private void applyUnitBorn(JsonNode e) {
        String unitName = e.get("unitTypeName").asText();
        String tag      = makeTag(e.get("unitTagIndex").asInt(), e.get("unitTagRecycle").asInt());
        int    ctrlId   = e.get("controlPlayerId").asInt();

        if (Sc2ReplayShared.BUILDING_NAMES.contains(unitName)) {
            if (ctrlId == watchedPlayerId) {
                BuildingType bt = toBuildingType(unitName);
                if (bt != BuildingType.UNKNOWN) {
                    Point2d pos = pos(e);
                    addBuilding(new Building(tag, bt, pos,
                        defaultBuildingHealth(bt), defaultBuildingHealth(bt), true));
                }
            }
        } else {
            UnitType ut = toUnitType(unitName);
            if (ut == UnitType.UNKNOWN) return;
            Point2d pos = pos(e);
            if (ctrlId == watchedPlayerId) {
                addUnit(new Unit(tag, ut, pos,
                    defaultUnitHealth(ut), defaultUnitHealth(ut), 0, 0, 0, 0));
            } else if (ctrlId != 0) {
                spawnEnemyUnit(ut, pos);
            }
        }
    }

    private void applyPlayerStats(JsonNode e) {
        if (e.get("playerId").asInt() != watchedPlayerId) return;
        JsonNode stats = e.get("stats");
        setMinerals(stats.get("scoreValueMineralsCurrent").asInt());
        setVespene(stats.get("scoreValueVespeneCurrent").asInt());
        // SC2EGSet JSON food values are raw integers — no ×4096 fixed-point unlike Scelight binary
        setSupplyUsed(stats.get("scoreValueFoodUsed").asInt());
        setSupply(stats.get("scoreValueFoodMade").asInt());
    }

    private void applyUnitDied(JsonNode e) {
        String tag = makeTag(e.get("unitTagIndex").asInt(), e.get("unitTagRecycle").asInt());
        removeUnitByTag(tag);
        removeBuildingByTag(tag);
        pendingBuildings.remove(tag);
        // Enemy units are NOT removed — accumulate for calibration (matches ReplaySimulatedGame)
    }

    private void applyUnitInit(JsonNode e) {
        int ctrlId = e.get("controlPlayerId").asInt();
        if (ctrlId != watchedPlayerId) return;
        String       unitName = e.get("unitTypeName").asText();
        String       tag      = makeTag(e.get("unitTagIndex").asInt(), e.get("unitTagRecycle").asInt());
        BuildingType bt       = toBuildingType(unitName);
        Point2d      pos      = pos(e);
        Building b = new Building(tag, bt, pos,
            defaultBuildingHealth(bt), defaultBuildingHealth(bt), false);
        pendingBuildings.put(tag, b);
        addBuilding(b);
    }

    private void applyUnitDone(JsonNode e) {
        String tag = makeTag(e.get("unitTagIndex").asInt(), e.get("unitTagRecycle").asInt());
        if (pendingBuildings.remove(tag) != null) markBuildingComplete(tag);
    }

    // ---- Helpers ----

    private static Point2d pos(JsonNode e) {
        return new Point2d(e.get("x").floatValue(), e.get("y").floatValue());
    }

    private static String makeTag(int index, int recycle) {
        return "j-" + index + "-" + recycle;
    }

    private static String raceInitial(String race) {
        return switch (race) {
            case "Terr" -> "T";
            case "Zerg" -> "Z";
            default     -> "P";
        };
    }

    private static UnitType toUnitType(String name)               { return Sc2ReplayShared.toUnitType(name); }
    private static BuildingType toBuildingType(String name)        { return Sc2ReplayShared.toBuildingType(name); }
    private static int defaultUnitHealth(UnitType type)            { return Sc2ReplayShared.defaultUnitHealth(type); }
    private static int defaultBuildingHealth(BuildingType type)    { return Sc2ReplayShared.defaultBuildingHealth(type); }
}
