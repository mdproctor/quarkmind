package org.acme.starcraft.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.starcraft.agent.StarCraftCaseFile;
import org.acme.starcraft.agent.plugin.StrategyTask;
import org.jboss.logging.Logger;

import java.util.Set;

@ApplicationScoped
@CaseType("starcraft-game")
public class PassThroughStrategyTask implements StrategyTask {
    private static final Logger log = Logger.getLogger(PassThroughStrategyTask.class);

    @Override public String getId() { return "strategy.passthrough"; }
    @Override public String getName() { return "PassThrough Strategy"; }
    @Override public Set<String> entryCriteria() { return Set.of(StarCraftCaseFile.READY); }
    @Override public Set<String> producedKeys() { return Set.of(); }

    @Override
    public void execute(CaseFile caseFile) {
        log.debugf("[STRATEGY] PassThrough activated at frame=%s minerals=%s",
            caseFile.get(StarCraftCaseFile.GAME_FRAME, Long.class).orElse(-1L),
            caseFile.get(StarCraftCaseFile.MINERALS, Integer.class).orElse(0));
    }
}
