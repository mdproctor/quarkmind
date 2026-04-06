package org.acme.starcraft.sc2.mock;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.acme.starcraft.agent.AgentOrchestrator;
import org.acme.starcraft.domain.UnitType;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.ScenarioRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class FullMockPipelineIT {

    @Inject AgentOrchestrator orchestrator;
    @Inject SimulatedGame simulatedGame;
    @Inject IntentQueue intentQueue;
    @Inject ScenarioRunner scenarioRunner;

    @BeforeEach
    void setUp() {
        simulatedGame.reset();
        orchestrator.startGame();
    }

    @Test
    void gameTickAdvancesFrameAndDispatchesPipeline() {
        long frameBefore = simulatedGame.snapshot().gameFrame();
        // Manually trigger one tick (scheduler is disabled in %test)
        orchestrator.gameTick();
        long frameAfter = simulatedGame.snapshot().gameFrame();
        assertThat(frameAfter).isGreaterThan(frameBefore);
    }

    @Test
    void scenarioChangesGameStateAndPipelineReacts() {
        // Apply scenario
        scenarioRunner.run("spawn-enemy-attack");
        assertThat(simulatedGame.snapshot().enemyUnits()).isNotEmpty();

        // Run a tick — pipeline should observe the enemy
        orchestrator.gameTick();

        // Dummy plugins produce no intents but pipeline should complete without error
        assertThat(intentQueue.pending()).isEmpty();
        assertThat(simulatedGame.snapshot().enemyUnits()).isNotEmpty(); // still visible
    }

    @Test
    void setResourcesScenarioVisibleInNextTick() {
        scenarioRunner.run("set-resources-500");
        assertThat(simulatedGame.snapshot().minerals()).isEqualTo(500);

        orchestrator.gameTick();
        // Verify no crash — minerals observed correctly by pipeline
        assertThat(simulatedGame.snapshot().minerals()).isGreaterThanOrEqualTo(500);
    }

    @Test
    void allScenariosRunWithoutException() {
        scenarioRunner.availableScenarios().forEach(name -> {
            simulatedGame.reset();
            orchestrator.startGame();
            scenarioRunner.run(name);
            orchestrator.gameTick(); // pipeline reacts without error
        });
    }
}
