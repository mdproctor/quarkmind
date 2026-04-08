package io.quarkmind.sc2;

import java.util.Set;

public interface ScenarioRunner {
    void run(String scenarioName);
    Set<String> availableScenarios();
}
