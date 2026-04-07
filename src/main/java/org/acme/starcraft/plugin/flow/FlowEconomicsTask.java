package org.acme.starcraft.plugin.flow;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.starcraft.agent.StarCraftCaseFile;
import org.acme.starcraft.agent.plugin.EconomicsTask;
import org.acme.starcraft.plugin.BasicEconomicsTask;
import org.acme.starcraft.sc2.IntentQueue;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * Flow-based Protoss economics — replaces {@link BasicEconomicsTask} as the active CaseHub plugin.
 *
 * <p><b>Status:</b> stub — delegates to {@link BasicEconomicsTask} until the Quarkus Flow
 * pipeline (EconomicsFlow, EconomicsDecisionService, EconomicsLifecycle) is wired in Tasks 5–8.
 * Owns assimilator-building decisions (moved from DroolsStrategyTask).
 */
@ApplicationScoped
@CaseType("starcraft-game")
public class FlowEconomicsTask implements EconomicsTask {

    private static final Logger log = Logger.getLogger(FlowEconomicsTask.class);

    private final BasicEconomicsTask delegate;

    @Inject
    public FlowEconomicsTask(IntentQueue intentQueue) {
        this.delegate = new BasicEconomicsTask(intentQueue);
    }

    @Override public String getId()   { return "economics.flow"; }
    @Override public String getName() { return "Flow Economics"; }
    @Override public Set<String> entryCriteria() { return Set.of(StarCraftCaseFile.READY); }
    @Override public Set<String> producedKeys()  { return Set.of(); }

    @Override
    public void execute(CaseFile caseFile) {
        log.debugf("[FLOW-ECONOMICS] delegating to BasicEconomicsTask (stub)");
        delegate.execute(caseFile);
    }
}
