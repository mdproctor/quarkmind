package io.quarkmind.sc2.mock;

import io.quarkmind.domain.GameState;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.mock.scenario.ScenarioLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioLibraryTest {

    SimulatedGame game;
    ScenarioLibrary library;
    MockScenarioRunner runner;

    @BeforeEach
    void setUp() {
        game = new SimulatedGame();
        game.reset();
        library = new ScenarioLibrary();
        runner = new MockScenarioRunner(game, library);
    }

    @Test
    void availableScenariosContainsAllFour() {
        assertThat(runner.availableScenarios()).containsExactlyInAnyOrder(
            "spawn-enemy-attack", "set-resources-500", "supply-almost-capped", "enemy-expands"
        );
    }

    @Test
    void spawnEnemyAttackAddsEnemyUnits() {
        runner.run("spawn-enemy-attack");
        assertThat(game.snapshot().enemyUnits()).isNotEmpty();
        assertThat(game.snapshot().enemyUnits().stream().anyMatch(u -> u.type() == UnitType.ZEALOT)).isTrue();
    }

    @Test
    void setResources500SetsMinerals() {
        runner.run("set-resources-500");
        assertThat(game.snapshot().minerals()).isEqualTo(500);
    }

    @Test
    void supplyAlmostCappedSetsSupplyUsedNearCap() {
        runner.run("supply-almost-capped");
        GameState state = game.snapshot();
        assertThat(state.supplyUsed()).isGreaterThanOrEqualTo(state.supply() - 2);
    }

    @Test
    void unknownScenarioThrows() {
        assertThatThrownBy(() -> runner.run("nonexistent"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nonexistent");
    }
}
