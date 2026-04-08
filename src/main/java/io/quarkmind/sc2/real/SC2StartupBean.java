package io.quarkmind.sc2.real;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkmind.agent.AgentOrchestrator;
import org.jboss.logging.Logger;

/**
 * Auto-starts the SC2 game on application startup in the %sc2 profile only.
 * Not present in %mock or %test profiles — mock tests call startGame() explicitly.
 */
@IfBuildProfile("sc2")
@ApplicationScoped
public class SC2StartupBean {
    private static final Logger log = Logger.getLogger(SC2StartupBean.class);

    @Inject AgentOrchestrator orchestrator;

    void onStart(@Observes StartupEvent ev) {
        log.info("[SC2] Auto-starting game on application startup...");
        try {
            orchestrator.startGame();
        } catch (Exception e) {
            log.errorf("[SC2] Auto-start failed: %s — use POST /sc2/start to retry", e.getMessage());
        }
    }
}
