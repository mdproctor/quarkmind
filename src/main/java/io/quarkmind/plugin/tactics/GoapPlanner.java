package io.quarkmind.plugin.tactics;

import java.util.*;

public class GoapPlanner {

    /**
     * A* search over WorldState nodes.
     *
     * @param initial        starting world state
     * @param goalCondition  boolean key that must be true in the goal state
     * @param actions        all available actions (planner picks applicable ones)
     * @return cheapest action sequence, or empty list if goal already satisfied or unreachable
     */
    public List<GoapAction> plan(WorldState initial, String goalCondition, List<GoapAction> actions) {
        if (initial.satisfies(goalCondition)) return List.of();

        record Node(WorldState state, List<GoapAction> plan, int cost) {}

        PriorityQueue<Node> open = new PriorityQueue<>(
            Comparator.comparingInt(n -> n.cost() + heuristic(n.state(), goalCondition)));
        open.add(new Node(initial, List.of(), 0));

        Set<Map<String, Boolean>> visited = new HashSet<>();

        while (!open.isEmpty()) {
            Node current = open.poll();
            if (current.state().satisfies(goalCondition)) return current.plan();
            if (!visited.add(current.state().conditions())) continue;

            for (GoapAction action : actions) {
                if (action.isApplicable(current.state())) {
                    WorldState next = action.applyTo(current.state());
                    List<GoapAction> newPlan = new ArrayList<>(current.plan());
                    newPlan.add(action);
                    open.add(new Node(next, newPlan, current.cost() + action.cost()));
                }
            }
        }
        return List.of();
    }

    private int heuristic(WorldState state, String goalCondition) {
        return state.satisfies(goalCondition) ? 0 : 1;
    }
}
