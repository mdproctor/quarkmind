package org.acme.starcraft.sc2.mock;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.starcraft.sc2.ScenarioRunner;
import org.acme.starcraft.sc2.mock.scenario.ScenarioLibrary;
import org.jboss.logging.Logger;
import java.util.Set;

@UnlessBuildProfile("sc2")
@ApplicationScoped
public class MockScenarioRunner implements ScenarioRunner {
    private static final Logger log = Logger.getLogger(MockScenarioRunner.class);
    private final SimulatedGame game;
    private final ScenarioLibrary library;

    @Inject
    public MockScenarioRunner(SimulatedGame game, ScenarioLibrary library) {
        this.game = game;
        this.library = library;
    }

    @Override
    public void run(String scenarioName) {
        log.infof("[MOCK] Running scenario: %s", scenarioName);
        library.get(scenarioName).apply(game);
    }

    @Override
    public Set<String> availableScenarios() {
        return library.names();
    }
}
