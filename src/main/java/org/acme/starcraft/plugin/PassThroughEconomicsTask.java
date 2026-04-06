package org.acme.starcraft.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.starcraft.agent.StarCraftCaseFile;
import org.acme.starcraft.agent.plugin.EconomicsTask;
import org.jboss.logging.Logger;

import java.util.Set;

@ApplicationScoped
@CaseType("starcraft-game")
public class PassThroughEconomicsTask implements EconomicsTask {
    private static final Logger log = Logger.getLogger(PassThroughEconomicsTask.class);

    @Override public String getId() { return "economics.passthrough"; }
    @Override public String getName() { return "PassThrough Economics"; }
    @Override public Set<String> entryCriteria() { return Set.of(StarCraftCaseFile.READY); }
    @Override public Set<String> producedKeys() { return Set.of(); }

    @Override
    public void execute(CaseFile caseFile) {
        log.debugf("[ECONOMICS] PassThrough activated — supply=%s/%s",
            caseFile.get(StarCraftCaseFile.SUPPLY_USED, Integer.class).orElse(0),
            caseFile.get(StarCraftCaseFile.SUPPLY_CAP, Integer.class).orElse(0));
    }
}
