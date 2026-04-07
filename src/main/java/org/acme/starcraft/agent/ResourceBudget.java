package org.acme.starcraft.agent;

/**
 * Per-tick resource budget shared across plugins via the CaseFile.
 *
 * <p>Plugins call {@link #spend(int, int)} to reserve resources before queuing
 * an intent. If the budget is insufficient, the plugin skips the intent for this
 * tick. This prevents two plugins from both seeing "minerals >= 150" and both
 * queuing a 150-mineral build in the same tick.
 *
 * <p>The budget is written to the CaseFile at the start of each tick by
 * {@link GameStateTranslator} under {@link StarCraftCaseFile#RESOURCE_BUDGET}
 * and is consumed in place — it is mutable shared state within a single
 * CaseEngine solve cycle.
 *
 * <p>Note: the budget enforces logic-level arbitration only. {@code SimulatedGame}
 * does not enforce mineral costs; real SC2 rejects commands it cannot honour.
 */
public class ResourceBudget {

    private int minerals;
    private int vespene;

    public ResourceBudget(int minerals, int vespene) {
        this.minerals = minerals;
        this.vespene  = vespene;
    }

    /** Returns current available minerals. */
    public int minerals() { return minerals; }

    /** Returns current available vespene. */
    public int vespene()  { return vespene; }

    /**
     * Attempts to spend the given amounts. Returns {@code true} and deducts
     * the costs if affordable; returns {@code false} without mutating if not.
     */
    public boolean spend(int mineralCost, int vespeneCost) {
        if (minerals < mineralCost || vespene < vespeneCost) return false;
        minerals -= mineralCost;
        vespene  -= vespeneCost;
        return true;
    }

    /** Convenience for mineral-only costs. */
    public boolean spendMinerals(int cost) {
        return spend(cost, 0);
    }

    @Override
    public String toString() {
        return "ResourceBudget{minerals=" + minerals + ", vespene=" + vespene + "}";
    }
}
