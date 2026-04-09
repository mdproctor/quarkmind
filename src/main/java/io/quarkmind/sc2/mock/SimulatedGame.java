package io.quarkmind.sc2.mock;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@UnlessBuildProfile("sc2")
@ApplicationScoped
public class SimulatedGame {

    private volatile int minerals;
    private volatile int vespene;
    private volatile int supply;
    private volatile int supplyUsed;
    private final AtomicLong gameFrame = new AtomicLong(0);
    private final List<Unit> myUnits = new CopyOnWriteArrayList<>();
    private final List<Building> myBuildings = new CopyOnWriteArrayList<>();
    private final List<Unit> enemyUnits = new CopyOnWriteArrayList<>();
    private final List<Resource> geysers = new CopyOnWriteArrayList<>();
    private final List<PendingCompletion> pendingCompletions = new CopyOnWriteArrayList<>();
    private int nextTag = 200;

    private record PendingCompletion(long completesAtTick, Runnable action) {}

    public synchronized void reset() {
        minerals = SC2Data.INITIAL_MINERALS;
        vespene = SC2Data.INITIAL_VESPENE;
        supply = SC2Data.INITIAL_SUPPLY;
        supplyUsed = SC2Data.INITIAL_SUPPLY_USED;
        gameFrame.set(0);
        myUnits.clear();
        myBuildings.clear();
        enemyUnits.clear();
        geysers.clear();
        pendingCompletions.clear();
        nextTag = 200;

        for (int i = 0; i < SC2Data.INITIAL_PROBES; i++) {
            myUnits.add(new Unit("probe-" + i, UnitType.PROBE, new Point2d(9 + i * 0.5f, 9), 45, 45));
        }
        myBuildings.add(new Building("nexus-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true));
        geysers.add(new Resource("geyser-0", new Point2d(5, 11), 2250));
        geysers.add(new Resource("geyser-1", new Point2d(11, 5), 2250));
    }

    public synchronized void tick() {
        long frame = gameFrame.incrementAndGet();
        minerals = Math.min(minerals + 5, 9999); // rough mineral trickle
        pendingCompletions.removeIf(item -> {
            if (item.completesAtTick() <= frame) {
                item.action().run();
                return true;
            }
            return false;
        });
    }

    public synchronized void applyIntent(Intent intent) {
        if (intent instanceof TrainIntent t) {
            long completesAt = gameFrame.get() + trainTimeInTicks(t.unitType());
            pendingCompletions.add(new PendingCompletion(completesAt, () -> {
                supplyUsed += supplyCost(t.unitType());
                myUnits.add(new Unit("unit-" + nextTag++, t.unitType(), new Point2d(9, 9),
                    maxHealth(t.unitType()), maxHealth(t.unitType())));
            }));
        } else if (intent instanceof BuildIntent b) {
            String bldgTag = "bldg-" + nextTag++;
            BuildingType bt = b.buildingType();
            // Add immediately as incomplete — visible to plugins during construction
            myBuildings.add(new Building(bldgTag, bt, b.location(),
                maxBuildingHealth(bt), maxBuildingHealth(bt), false));
            long completesAt = gameFrame.get() + buildTimeInTicks(bt);
            pendingCompletions.add(new PendingCompletion(completesAt, () -> {
                markBuildingComplete(bldgTag);
                supply += supplyBonus(bt); // supply granted only when complete
            }));
        }
        // AttackIntent and MoveIntent: unit positions updated in future phases
    }

    public synchronized GameState snapshot() {
        return new GameState(minerals, vespene, supply, supplyUsed,
            List.copyOf(myUnits), List.copyOf(myBuildings), List.copyOf(enemyUnits),
            List.copyOf(geysers), gameFrame.get());
    }

    public synchronized void spawnEnemyUnit(UnitType type, Point2d position) {
        enemyUnits.add(new Unit("enemy-" + nextTag++, type, position, maxHealth(type), maxHealth(type)));
    }

    public void setMinerals(int amount) { this.minerals = amount; }
    public void setVespene(int amount) { this.vespene = amount; }
    public void setSupply(int cap) { this.supply = cap; }
    public void setSupplyUsed(int used) { this.supplyUsed = used; }

    // --- Protected mutation helpers for subclasses ---

    protected void setGameFrame(long frame) { gameFrame.set(frame); }
    protected void addUnit(Unit u) { myUnits.add(u); }
    protected void removeUnitByTag(String tag) { myUnits.removeIf(u -> u.tag().equals(tag)); }
    protected void addBuilding(Building b) { myBuildings.add(b); }
    protected void removeBuildingByTag(String tag) { myBuildings.removeIf(b -> b.tag().equals(tag)); }
    protected void markBuildingComplete(String tag) {
        myBuildings.replaceAll(b -> b.tag().equals(tag)
            ? new Building(b.tag(), b.type(), b.position(), b.health(), b.maxHealth(), true)
            : b);
    }
    protected void removeEnemyByTag(String tag) { enemyUnits.removeIf(u -> u.tag().equals(tag)); }
    protected void clearAll() {
        myUnits.clear();
        myBuildings.clear();
        enemyUnits.clear();
        geysers.clear();
        pendingCompletions.clear();
    }

    public List<Resource> getGeysers() { return List.copyOf(geysers); }

    private static int trainTimeInTicks(UnitType type)     { return SC2Data.trainTimeInTicks(type); }
    private static int buildTimeInTicks(BuildingType type)  { return SC2Data.buildTimeInTicks(type); }
    private int supplyCost(UnitType type)                   { return SC2Data.supplyCost(type); }
    private int supplyBonus(BuildingType type)              { return SC2Data.supplyBonus(type); }
    private int maxHealth(UnitType type)                    { return SC2Data.maxHealth(type); }
    private int maxBuildingHealth(BuildingType type)        { return SC2Data.maxBuildingHealth(type); }
}
