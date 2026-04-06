package org.acme.starcraft.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.starcraft.agent.StarCraftCaseFile;
import org.acme.starcraft.agent.plugin.ScoutingTask;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

@ApplicationScoped
@CaseType("starcraft-game")
public class PassThroughScoutingTask implements ScoutingTask {
    private static final Logger log = Logger.getLogger(PassThroughScoutingTask.class);

    @Override public String getId() { return "scouting.passthrough"; }
    @Override public String getName() { return "PassThrough Scouting"; }
    @Override public Set<String> entryCriteria() { return Set.of(StarCraftCaseFile.READY); }
    @Override public Set<String> producedKeys() { return Set.of(); }

    @Override
    public void execute(CaseFile caseFile) {
        List<?> enemies = caseFile.get(StarCraftCaseFile.ENEMY_UNITS, List.class).orElse(List.of());
        log.debugf("[SCOUTING] PassThrough activated — visible enemy units=%d", enemies.size());
    }
}
