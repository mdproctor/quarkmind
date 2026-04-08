package io.quarkmind.sc2.mock.scenario;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class ScenarioLibrary {
    private final Map<String, Scenario> scenarios = Map.of(
        "spawn-enemy-attack", new SpawnEnemyAttackScenario(),
        "set-resources-500",  new SetResourcesScenario(),
        "supply-almost-capped", new SupplyAlmostCappedScenario(),
        "enemy-expands",      new EnemyExpandsScenario()
    );

    public Scenario get(String name) {
        Scenario scenario = scenarios.get(name);
        if (scenario == null) {
            throw new IllegalArgumentException("Unknown scenario: " + name + ". Available: " + scenarios.keySet());
        }
        return scenario;
    }

    public Set<String> names() {
        return scenarios.keySet();
    }
}
