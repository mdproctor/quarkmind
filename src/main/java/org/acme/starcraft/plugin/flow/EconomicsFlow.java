package org.acme.starcraft.plugin.flow;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.starcraft.domain.GameStateTick;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Placeholder consumer for the {@code economics-ticks} in-memory channel.
 *
 * <p><b>Status:</b> stub — drops all ticks until the Quarkus Flow pipeline
 * is wired in Task 7 (EconomicsFlow workflow DSL).
 *
 * <p>This class exists solely to satisfy the SmallRye Reactive Messaging wiring
 * requirement: an {@code @Channel} emitter must have at least one downstream
 * {@code @Incoming} consumer registered at Quarkus boot time.
 * Task 7 will replace this with the real flow-based consumer.
 */
@ApplicationScoped
public class EconomicsFlow {

    private static final Logger log = Logger.getLogger(EconomicsFlow.class);

    /**
     * Drain stub — discards ticks until Task 7 wires the real flow consumer.
     */
    @Incoming("economics-ticks")
    public Uni<Void> process(GameStateTick tick) {
        log.tracef("[FLOW-ECONOMICS] Tick received (stub drain): workers=%d supply=%d/%d",
            tick.workers().size(), tick.supplyUsed(), tick.supplyCap());
        return Uni.createFrom().voidItem();
    }
}
