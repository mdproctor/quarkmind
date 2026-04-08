package io.quarkmind.plugin.tactics;

import java.util.Map;

public record GoapAction(
    String name,
    Map<String, Boolean> preconditions,
    Map<String, Boolean> effects,
    int cost
) {
    public GoapAction {
        preconditions = Map.copyOf(preconditions);
        effects       = Map.copyOf(effects);
    }

    public boolean isApplicable(WorldState state) {
        return preconditions.entrySet().stream()
            .allMatch(e -> state.get(e.getKey()) == e.getValue());
    }

    public WorldState applyTo(WorldState state) {
        WorldState result = state;
        for (var entry : effects.entrySet()) {
            result = result.with(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
