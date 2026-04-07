package org.acme.starcraft.sc2.mock;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.starcraft.domain.*;
import org.acme.starcraft.sc2.intent.*;
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
        minerals = 50;
        vespene = 0;
        supply = 15;
        supplyUsed = 12;
        gameFrame.set(0);
        myUnits.clear();
        myBuildings.clear();
        enemyUnits.clear();
        geysers.clear();
        pendingCompletions.clear();
        nextTag = 200;

        // 12 Probes
        for (int i = 0; i < 12; i++) {
            myUnits.add(new Unit("probe-" + i, UnitType.PROBE, new Point2d(9 + i * 0.5f, 9), 45, 45));
        }
        // 1 Nexus
        myBuildings.add(new Building("nexus-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true));
        // 2 Vespene geysers (typical positions relative to Nexus at 8,8)
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

    // Build times in ticks (22 loops/tick at SC2 Faster speed = 22.4 loops/sec)
    private static int trainTimeInTicks(UnitType type) {
        return switch (type) {
            case PROBE    -> 12;  // 12s
            case ZEALOT   -> 28;  // 27s
            case STALKER  -> 31;  // 30s
            case IMMORTAL -> 40;  // 39s
            case OBSERVER -> 22;  // 21s
            default       -> 30;
        };
    }

    private static int buildTimeInTicks(BuildingType type) {
        return switch (type) {
            case PYLON             -> 18;  // 18s
            case GATEWAY           -> 47;  // 46s
            case CYBERNETICS_CORE  -> 37;  // 36s
            case ASSIMILATOR       -> 21;  // 21s
            case ROBOTICS_FACILITY -> 47;  // 46s
            case STARGATE          -> 44;  // 43s
            case FORGE             -> 30;  // 29s
            case TWILIGHT_COUNCIL  -> 37;  // 36s
            default                -> 40;
        };
    }

    private int supplyCost(UnitType type) {
        return switch (type) {
            case PROBE -> 1;
            case ZEALOT -> 2;
            case STALKER -> 2;
            case IMMORTAL -> 4;
            default -> 2;
        };
    }

    private int supplyBonus(BuildingType type) {
        return type == BuildingType.PYLON ? 8 : 0;
    }

    private int maxHealth(UnitType type) {
        return switch (type) {
            case PROBE -> 45;
            case ZEALOT -> 100;
            case STALKER -> 80;
            default -> 100;
        };
    }

    private int maxBuildingHealth(BuildingType type) {
        return switch (type) {
            case NEXUS -> 1500;
            case PYLON -> 200;
            case GATEWAY -> 500;
            default -> 500;
        };
    }
}
