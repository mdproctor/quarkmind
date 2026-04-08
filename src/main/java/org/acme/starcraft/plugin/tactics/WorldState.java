package org.acme.starcraft.plugin.tactics;

import java.util.HashMap;
import java.util.Map;

public record WorldState(Map<String, Boolean> conditions) {

    public WorldState {
        conditions = Map.copyOf(conditions);  // defensive copy + makes unmodifiable
    }

    public WorldState with(String key, boolean value) {
        Map<String, Boolean> copy = new HashMap<>(conditions);
        copy.put(key, value);
        return new WorldState(copy);
    }

    public boolean get(String key) {
        return Boolean.TRUE.equals(conditions.get(key));
    }

    public boolean satisfies(String goalCondition) {
        return get(goalCondition);
    }
}
