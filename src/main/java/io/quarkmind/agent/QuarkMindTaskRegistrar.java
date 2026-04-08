package io.quarkmind.agent;

import io.casehub.annotation.CaseType;
import io.casehub.core.TaskDefinition;
import io.casehub.core.TaskDefinitionRegistry;
import io.casehub.error.CircularDependencyException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkmind.agent.plugin.EconomicsTask;
import io.quarkmind.agent.plugin.ScoutingTask;
import io.quarkmind.agent.plugin.StrategyTask;
import io.quarkmind.agent.plugin.TacticsTask;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Wires all four CaseHub plugin seams into {@link TaskDefinitionRegistry} at startup.
 *
 * <p>Quarkus Arc removes {@code @CaseType} beans as "unused" unless something injects
 * them. This registrar injects each plugin seam via its typed interface, which both
 * keeps the beans alive and registers them so {@link io.casehub.coordination.CaseEngine}
 * can discover them when solving a {@code "starcraft-game"} case.
 *
 * <p>Registration order matches the plugin priority declared in each task's
 * {@link TaskDefinition#entryCriteria()} and {@link TaskDefinition#producedKeys()}.
 */
@ApplicationScoped
public class QuarkMindTaskRegistrar {

    private static final Logger log = Logger.getLogger(QuarkMindTaskRegistrar.class);
    private static final String CASE_TYPE = "starcraft-game";

    @Inject @CaseType("starcraft-game") EconomicsTask economicsTask;
    @Inject @CaseType("starcraft-game") ScoutingTask  scoutingTask;
    @Inject @CaseType("starcraft-game") StrategyTask  strategyTask;
    @Inject @CaseType("starcraft-game") TacticsTask   tacticsTask;

    @Inject TaskDefinitionRegistry registry;

    void onStart(@Observes StartupEvent ev) {
        List<TaskDefinition> tasks = List.of(
            (TaskDefinition) economicsTask,
            (TaskDefinition) scoutingTask,
            (TaskDefinition) strategyTask,
            (TaskDefinition) tacticsTask
        );
        for (TaskDefinition td : tasks) {
            try {
                registry.register(td, java.util.Set.of(CASE_TYPE));
                log.infof("[REGISTRAR] Registered %s (%s)", td.getName(), td.getId());
            } catch (CircularDependencyException e) {
                log.errorf("[REGISTRAR] Circular dependency detected for %s: %s", td.getId(), e.getMessage());
                throw new RuntimeException("CaseHub task registration failed", e);
            }
        }
    }
}
