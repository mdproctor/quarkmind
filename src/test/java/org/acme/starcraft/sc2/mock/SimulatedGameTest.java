package org.acme.starcraft.sc2.mock;

import org.acme.starcraft.domain.*;
import org.acme.starcraft.sc2.intent.*;
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
        game.tick(); // complete training
        GameState state = game.snapshot();
        assertThat(state.supplyUsed()).isEqualTo(14); // +2 for zealot
    }

    @Test
    void applyBuildIntentAddsPylon() {
        String probeTag = game.snapshot().myUnits().get(0).tag();
        game.applyIntent(new BuildIntent(probeTag, BuildingType.PYLON, new Point2d(15, 15)));
        game.tick();
        GameState state = game.snapshot();
        assertThat(state.myBuildings().stream().anyMatch(b -> b.type() == BuildingType.PYLON)).isTrue();
        assertThat(state.supply()).isEqualTo(23); // +8 from pylon
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
