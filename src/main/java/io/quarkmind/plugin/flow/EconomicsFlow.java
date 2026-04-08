package io.quarkmind.plugin.flow;

import io.quarkiverse.flow.Flow;
import io.serverlessworkflow.api.types.Workflow;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.domain.GameStateTick;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.consume;
import static io.serverlessworkflow.fluent.func.FuncWorkflowBuilder.workflow;

/**
 * Quarkus Flow workflow that processes each SC2 economics tick.
 *
 * <p>Receives {@link GameStateTick} events from the {@code economics-ticks}
 * SmallRye in-memory channel and triggers one workflow instance per tick via
 * {@link #startInstance(Object)}. Each instance runs four sequential FuncDSL
 * {@code consume} steps that delegate to {@link EconomicsDecisionService}.
 *
 * <p>The workflow does not loop internally — each tick starts a fresh, short-lived
 * instance. The per-tick cadence is driven externally by {@link FlowEconomicsTask}
 * emitting on the channel each CaseHub game tick.
 */
@ApplicationScoped
public class EconomicsFlow extends Flow {

    private static final Logger log = Logger.getLogger(EconomicsFlow.class);

    private final EconomicsDecisionService decisions;

    @Inject
    public EconomicsFlow(EconomicsDecisionService decisions) {
        this.decisions = decisions;
    }

    /**
     * Defines the workflow: four sequential consume steps, one per economics decision.
     * Each step receives the {@link GameStateTick} passed as the instance input.
     */
    @Override
    public Workflow descriptor() {
        return workflow("starcraft-economics")
            .tasks(
                consume(decisions::checkSupply,    GameStateTick.class),
                consume(decisions::checkProbes,    GameStateTick.class),
                consume(decisions::checkGas,       GameStateTick.class),
                consume(decisions::checkExpansion, GameStateTick.class)
            )
            .build();
    }

    /**
     * Receives each tick from the SmallRye in-memory channel and starts a new
     * workflow instance for that tick. Returns {@code Uni<Void>} so SmallRye
     * handles back-pressure correctly.
     */
    @Incoming("economics-ticks")
    public Uni<Void> processTick(GameStateTick tick) {
        log.debugf("[FLOW-ECONOMICS] Tick received: workers=%d supply=%d/%d gasReady=%b",
            tick.workers().size(), tick.supplyUsed(), tick.supplyCap(), tick.gasReady());
        return startInstance(tick).replaceWithVoid();
    }
}
