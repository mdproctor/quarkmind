package io.quarkmind.sc2.replay;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkmind.agent.AgentOrchestrator;
import org.jboss.logging.Logger;

/**
 * Auto-starts the replay session on application startup in the {@code %replay} profile.
 * Mirrors {@code SC2StartupBean} — same pattern, different engine.
 */
@IfBuildProfile("replay")
@ApplicationScoped
public class ReplayStartupBean {
    private static final Logger log = Logger.getLogger(ReplayStartupBean.class);

    @Inject AgentOrchestrator orchestrator;

    void onStart(@Observes StartupEvent ev) {
        log.info("[REPLAY] Auto-starting replay session on application startup...");
        try {
            orchestrator.startGame();
        } catch (Exception e) {
            log.errorf("[REPLAY] Auto-start failed: %s — use POST /sc2/start to retry", e.getMessage());
        }
    }
}
