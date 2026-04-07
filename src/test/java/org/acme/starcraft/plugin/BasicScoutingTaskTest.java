package org.acme.starcraft.plugin;

import io.casehub.core.DefaultCaseFile;
import org.acme.starcraft.agent.StarCraftCaseFile;
import org.acme.starcraft.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BasicScoutingTaskTest {

    BasicScoutingTask task;

    @BeforeEach
    void setUp() {
        task = new BasicScoutingTask();
    }

    @Test
    void writesZeroArmySizeWhenNoEnemiesVisible() {
        var cf = caseFile(List.of(), List.of(nexus()));
        task.execute(cf);
        assertThat(cf.get(StarCraftCaseFile.ENEMY_ARMY_SIZE, Integer.class)).contains(0);
    }

    @Test
    void writesCorrectArmySizeWhenEnemiesPresent() {
        var cf = caseFile(List.of(enemy(10, 10), enemy(20, 20)), List.of(nexus()));
        task.execute(cf);
        assertThat(cf.get(StarCraftCaseFile.ENEMY_ARMY_SIZE, Integer.class)).contains(2);
    }

    @Test
    void writesNearestThreatPositionWhenEnemiesVisible() {
        Point2d home = new Point2d(8, 8);
        // Enemy at (10,10) is closer to home than (100,100)
        var cf = caseFile(List.of(enemy(10, 10), enemy(100, 100)), List.of(nexus()));
        task.execute(cf);
        assertThat(cf.get(StarCraftCaseFile.NEAREST_THREAT, Point2d.class))
            .contains(new Point2d(10, 10));
    }

    @Test
    void doesNotWriteNearestThreatWhenNoEnemies() {
        var cf = caseFile(List.of(), List.of(nexus()));
        task.execute(cf);
        assertThat(cf.get(StarCraftCaseFile.NEAREST_THREAT, Point2d.class)).isEmpty();
    }

    @Test
    void usesNexusAsHomeForDistanceCalculation() {
        // Nexus at (50,50) — enemy at (51,51) should be nearest, not (0,0)
        Building farNexus = new Building("n-0", BuildingType.NEXUS, new Point2d(50, 50), 1500, 1500, true);
        var cf = caseFile(List.of(enemy(51, 51), enemy(0, 0)), List.of(farNexus));
        task.execute(cf);
        assertThat(cf.get(StarCraftCaseFile.NEAREST_THREAT, Point2d.class))
            .contains(new Point2d(51, 51));
    }

    @Test
    void fallsBackToOriginWhenNoNexus() {
        var cf = caseFile(List.of(enemy(10, 10)), List.of());
        task.execute(cf);
        // Should not throw — nearest threat still computed from (0,0)
        assertThat(cf.get(StarCraftCaseFile.NEAREST_THREAT, Point2d.class)).isPresent();
    }

    // --- Helpers ---

    private DefaultCaseFile caseFile(List<Unit> enemies, List<Building> buildings) {
        var cf = new DefaultCaseFile("test-" + System.nanoTime(), "starcraft-game", null, null);
        cf.put(StarCraftCaseFile.ENEMY_UNITS,  enemies);
        cf.put(StarCraftCaseFile.MY_BUILDINGS, buildings);
        cf.put(StarCraftCaseFile.READY,        Boolean.TRUE);
        return cf;
    }

    private Building nexus() {
        return new Building("n-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true);
    }

    private Unit enemy(float x, float y) {
        return new Unit("e-" + (int) x, UnitType.ZEALOT, new Point2d(x, y), 100, 100);
    }
}
