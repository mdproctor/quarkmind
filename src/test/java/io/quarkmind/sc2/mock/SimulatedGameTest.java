package io.quarkmind.sc2.mock;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SimulatedGameTest {

    SimulatedGame game;

    @BeforeEach
    void setUp() {
        game = new SimulatedGame();
        game.reset();
    }

    @Test
    void initialStateIsStandardProtossOpener() {
        GameState state = game.snapshot();
        assertThat(state.minerals()).isEqualTo(50);
        assertThat(state.vespene()).isEqualTo(0);
        assertThat(state.supply()).isEqualTo(15);
        assertThat(state.supplyUsed()).isEqualTo(12);
        assertThat(state.myUnits().stream().filter(u -> u.type() == UnitType.PROBE).count()).isEqualTo(12);
        assertThat(state.myBuildings().stream().filter(b -> b.type() == BuildingType.NEXUS).count()).isEqualTo(1);
        assertThat(state.enemyUnits()).isEmpty();
        assertThat(state.gameFrame()).isEqualTo(0L);
    }

    @Test
    void tickAdvancesGameFrame() {
        game.tick();
        game.tick();
        assertThat(game.snapshot().gameFrame()).isEqualTo(2L);
    }

    @Test
    void applyTrainIntentIncreasesSupplyUsed() {
        String nexusTag = game.snapshot().myBuildings().get(0).tag();
        game.applyIntent(new TrainIntent(nexusTag, UnitType.ZEALOT));
        // Zealot train time = 28 ticks (27s at Faster speed)
        for (int i = 0; i < 28; i++) game.tick();
        GameState state = game.snapshot();
        assertThat(state.supplyUsed()).isEqualTo(14); // +2 for zealot
    }

    @Test
    void applyBuildIntentAddsPylonUnderConstruction() {
        String probeTag = game.snapshot().myUnits().get(0).tag();
        game.applyIntent(new BuildIntent(probeTag, BuildingType.PYLON, new Point2d(15, 15)));
        game.tick(); // 1 tick — Pylon appears immediately as incomplete
        GameState state = game.snapshot();
        assertThat(state.myBuildings().stream()
            .anyMatch(b -> b.type() == BuildingType.PYLON && !b.isComplete())).isTrue();
        assertThat(state.supply()).isEqualTo(15); // supply not yet granted
    }

    @Test
    void applyBuildIntentCompletesPylonAfterBuildTime() {
        String probeTag = game.snapshot().myUnits().get(0).tag();
        game.applyIntent(new BuildIntent(probeTag, BuildingType.PYLON, new Point2d(15, 15)));
        // Pylon build time = 18 ticks (18s at Faster speed)
        for (int i = 0; i < 18; i++) game.tick();
        GameState state = game.snapshot();
        assertThat(state.myBuildings().stream()
            .anyMatch(b -> b.type() == BuildingType.PYLON && b.isComplete())).isTrue();
        assertThat(state.supply()).isEqualTo(23); // +8 from completed pylon
    }

    @Test
    void spawnEnemyUnitsAreVisible() {
        game.spawnEnemyUnit(UnitType.ZEALOT, new Point2d(100, 100));
        assertThat(game.snapshot().enemyUnits()).hasSize(1);
        assertThat(game.snapshot().enemyUnits().get(0).type()).isEqualTo(UnitType.ZEALOT);
    }

    @Test
    void setMineralsDirectly() {
        game.setMinerals(500);
        assertThat(game.snapshot().minerals()).isEqualTo(500);
    }
}
