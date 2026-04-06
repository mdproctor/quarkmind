package org.acme.starcraft.sc2.real;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.starcraft.sc2.CommandDispatcher;
import org.jboss.logging.Logger;

/**
 * No-op implementation for %sc2 profile.
 *
 * Commands are sent by SC2BotAgent.onStep() which drains IntentQueue directly.
 * SC2 requires all actions within the onStep() callback — dispatching from
 * our @Scheduled pipeline would be too late. AgentOrchestrator still calls
 * dispatch() but this implementation does nothing.
 */
@IfBuildProfile("sc2")
@ApplicationScoped
public class RealCommandDispatcher implements CommandDispatcher {
    private static final Logger log = Logger.getLogger(RealCommandDispatcher.class);

    @Override
    public void dispatch() {
        // No-op: SC2BotAgent.onStep() handles command dispatch within the SC2 frame callback.
        log.trace("[SC2] CommandDispatcher.dispatch() — no-op, SC2BotAgent handles dispatch");
    }
}
