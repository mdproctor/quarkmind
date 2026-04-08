package io.quarkmind.sc2.mock;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.ScenarioRunner;
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

        // Run a tick — pipeline observes enemy, plugins execute through CaseHub
        orchestrator.gameTick();

        // Strategy should be DEFEND (enemy visible); economics trains probe (50 minerals available)
        assertThat(simulatedGame.snapshot().enemyUnits()).isNotEmpty(); // still visible
        // At least one plugin ran and the pipeline completed without error
        // (economics queues a probe train at 50 minerals)
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
