package org.acme.starcraft.plugin.flow;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;
import org.acme.starcraft.sc2.GameStarted;
import org.acme.starcraft.sc2.GameStopped;

/**
 * Logs economics workflow lifecycle events.
 *
 * <p>EconomicsFlow processes ticks reactively via @Incoming — no explicit
 * instance management needed. These events provide lifecycle visibility
 * and a hook for future flow management (e.g., resetting state between games).
 */
@ApplicationScoped
public class EconomicsLifecycle {

    private static final Logger log = Logger.getLogger(EconomicsLifecycle.class);

    void onGameStart(@Observes GameStarted event) {
        log.info("[FLOW-ECONOMICS] Game started — economics workflow active (per-tick instances)");
    }

    void onGameStop(@Observes GameStopped event) {
        log.info("[FLOW-ECONOMICS] Game stopped — economics workflow will idle");
    }
}
