package io.quarkmind.plugin;

import io.quarkmind.domain.*;
import io.quarkmind.plugin.scouting.ScoutingSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoutingSessionManagerTest {

    ScoutingSessionManager manager;

    @BeforeEach void setUp() { manager = new ScoutingSessionManager(); }

    @Test
    void firstSeenEnemyAddsUnitEvent() {
        manager.processFrame(List.of(roach("r-0", 10, 10)), 1000L, nexus(), enemyBase());
        // buildRuleUnit() requires DataSource.createStore() which needs Quarkus build-time init (GE-0053)
        // verify via the internal tracking set instead
        assertThat(manager.seenTagCount()).isEqualTo(1);
    }

    @Test
    void sameTagNotInsertedTwice() {
        manager.processFrame(List.of(roach("r-0", 10, 10)), 1000L, nexus(), enemyBase());
        manager.processFrame(List.of(roach("r-0", 10, 10)), 2000L, nexus(), enemyBase());
        assertThat(manager.seenTagCount()).isEqualTo(1);
    }

    @Test
    void evictRemovesExpiredUnitEvents() {
        manager.processFrame(List.of(roach("r-0", 10, 10)), 0L, nexus(), enemyBase());
        manager.evict(ScoutingSessionManager.UNIT_WINDOW_MS + 1);
        assertThat(manager.unitBufferSize()).isEqualTo(0);
    }

    @Test
    void evictKeepsRecentUnitEvents() {
        manager.processFrame(List.of(roach("r-0", 10, 10)), 0L, nexus(), enemyBase());
        manager.evict(ScoutingSessionManager.UNIT_WINDOW_MS - 1);
        assertThat(manager.unitBufferSize()).isEqualTo(1);
    }

    @Test
    void resetClearsAllBuffers() {
        manager.processFrame(List.of(roach("r-0", 10, 10)), 1000L, nexus(), enemyBase());
        manager.reset();
        assertThat(manager.seenTagCount()).isEqualTo(0);
        assertThat(manager.unitBufferSize()).isEqualTo(0);
        assertThat(manager.armyBufferSize()).isEqualTo(0);
        assertThat(manager.expansionBufferSize()).isEqualTo(0);
    }

    @Test
    void armyNearBaseAddsEvent() {
        List<Unit> enemies = List.of(
            roach("r-0", 10, 10), roach("r-1", 11, 10), roach("r-2", 12, 10));
        manager.processFrame(enemies, 1000L, nexus(), enemyBase());
        assertThat(manager.armyBufferSize()).isEqualTo(1);
    }

    @Test
    void armyEventsExpireAfterWindow() {
        List<Unit> enemies = List.of(
            roach("r-0", 10, 10), roach("r-1", 11, 10), roach("r-2", 12, 10));
        manager.processFrame(enemies, 0L, nexus(), enemyBase());
        manager.evict(ScoutingSessionManager.ARMY_WINDOW_MS + 1);
        assertThat(manager.armyBufferSize()).isEqualTo(0);
    }

    @Test
    void enemyFarFromEnemyBaseAddsExpansionEvent() {
        // Unit at (64,64) is > 50 tiles from enemyBase at (224,224) — triggers expansion
        Unit farUnit = new Unit("x-0", UnitType.ROACH, new Point2d(64, 64), 100, 100, 0, 0, 0, 0);
        manager.processFrame(List.of(farUnit), 1000L, nexus(), enemyBase());
        assertThat(manager.expansionBufferSize()).isEqualTo(1);
    }

    @Test
    void expansionEventNotInsertedTwiceForSameCell() {
        Unit farUnit  = new Unit("x-0", UnitType.ROACH, new Point2d(64, 64), 100, 100, 0, 0, 0, 0);
        Unit farUnit2 = new Unit("x-1", UnitType.ROACH, new Point2d(64, 64), 100, 100, 0, 0, 0, 0);
        manager.processFrame(List.of(farUnit),  1000L, nexus(), enemyBase());
        manager.processFrame(List.of(farUnit2), 2000L, nexus(), enemyBase());
        assertThat(manager.expansionBufferSize()).isEqualTo(1);
    }

    private Unit roach(String tag, float x, float y) {
        return new Unit(tag, UnitType.ROACH, new Point2d(x, y), 100, 100, 0, 0, 0, 0);
    }
    private Point2d nexus()     { return new Point2d(8, 8); }
    private Point2d enemyBase() { return new Point2d(224, 224); }
}
