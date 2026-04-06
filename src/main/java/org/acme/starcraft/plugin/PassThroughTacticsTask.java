package org.acme.starcraft.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.starcraft.agent.StarCraftCaseFile;
import org.acme.starcraft.agent.plugin.TacticsTask;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

@ApplicationScoped
@CaseType("starcraft-game")
public class PassThroughTacticsTask implements TacticsTask {
    private static final Logger log = Logger.getLogger(PassThroughTacticsTask.class);

    @Override public String getId() { return "tactics.passthrough"; }
    @Override public String getName() { return "PassThrough Tactics"; }
    @Override public Set<String> entryCriteria() { return Set.of(StarCraftCaseFile.READY); }
    @Override public Set<String> producedKeys() { return Set.of(); }

    @Override
    public void execute(CaseFile caseFile) {
        List<?> army = caseFile.get(StarCraftCaseFile.ARMY, List.class).orElse(List.of());
        log.debugf("[TACTICS] PassThrough activated — army size=%d", army.size());
    }
}
