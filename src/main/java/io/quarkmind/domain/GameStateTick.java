package io.quarkmind.domain;

import io.quarkmind.agent.ResourceBudget;

import java.util.List;

/**
 * Snapshot of game state emitted once per CaseHub tick by FlowEconomicsTask.
 *
 * <p>The budget is a per-tick snapshot copy — independent of the CaseHub shared
 * ResourceBudget, which has already been partially consumed by other plugins
 * by the time the flow processes this tick (one-tick lag; see ADR-0001).
 *
 * <p>gasReady is computed in FlowEconomicsTask from buildings: true when a Gateway
 * exists. When Drools CEP lands it becomes QuarkMindCaseFile.SIGNAL_GAS_READY.
 */
public record GameStateTick(
        int minerals,
        int vespene,
        int supplyUsed,
        int supplyCap,
        List<Unit> workers,
        List<Building> buildings,
        List<Resource> geysers,
        ResourceBudget budget,
        String strategy,
        boolean gasReady
) {}
