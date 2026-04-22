package io.quarkmind.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.quarkmind.domain.EnemyStrategy;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Live configuration for EmulatedGame.
 * Layer 1: read from application.properties with hardcoded defaults.
 * Layer 2: runtime-mutable via EmulatedConfigResource (PUT /qa/emulated/config).
 * Layer 3: visualizer config panel calls the REST endpoint.
 *
 * <p>No profile guard — this bean is instantiated in all profiles (including %mock and %prod).
 * It is only actively read by EmulatedEngine (@IfBuildProfile("emulated")). All four
 * @ConfigProperty fields must retain their defaultValue permanently to avoid resolution
 * failures in profiles where the property is not configured.
 */
@ApplicationScoped
public class EmulatedConfig {

    private static final Logger log = Logger.getLogger(EmulatedConfig.class);

    @ConfigProperty(name = "emulated.active", defaultValue = "false")
    boolean active;

    @ConfigProperty(name = "emulated.wave.spawn-frame", defaultValue = "200")
    int defaultWaveSpawnFrame;

    @ConfigProperty(name = "emulated.wave.unit-count", defaultValue = "4")
    int defaultWaveUnitCount;

    @ConfigProperty(name = "emulated.wave.unit-type", defaultValue = "ZEALOT")
    String defaultWaveUnitType;

    @ConfigProperty(name = "emulated.unit.speed", defaultValue = "0.5")
    double defaultUnitSpeed;

    @ConfigProperty(name = "emulated.enemy.strategy-file")
    Optional<String> strategyFile;

    @Inject
    ObjectMapper objectMapper;

    // Volatile for thread safety (REST thread writes, scheduler thread reads)
    private volatile int    waveSpawnFrame;
    private volatile int    waveUnitCount;
    private volatile String waveUnitType;
    private volatile double unitSpeed;
    private volatile EnemyStrategy enemyStrategy = EnemyStrategy.defaultProtoss();

    @PostConstruct
    void init() {
        waveSpawnFrame = defaultWaveSpawnFrame;
        waveUnitCount  = defaultWaveUnitCount;
        waveUnitType   = defaultWaveUnitType;
        unitSpeed      = defaultUnitSpeed;
        // Load enemy strategy from file if configured
        strategyFile.filter(f -> !f.isBlank()).ifPresent(path -> {
            try {
                File file = Path.of(path).toFile();
                ObjectMapper mapper = path.endsWith(".yaml") || path.endsWith(".yml")
                    ? new YAMLMapper()
                    : objectMapper;
                enemyStrategy = mapper.readValue(file, EnemyStrategy.class);
                log.infof("[CONFIG] Loaded enemy strategy from %s", path);
            } catch (Exception e) {
                log.warnf("[CONFIG] Could not load strategy file %s — using default. Error: %s",
                    path, e.getMessage());
            }
        });
    }

    public int    getWaveSpawnFrame() { return waveSpawnFrame; }
    public int    getWaveUnitCount()  { return waveUnitCount;  }
    public String getWaveUnitType()   { return waveUnitType;   }
    public double getUnitSpeed()      { return unitSpeed;      }

    public void setWaveSpawnFrame(int v)  { this.waveSpawnFrame = v; }
    public void setWaveUnitCount(int v)   { this.waveUnitCount  = v; }
    public void setWaveUnitType(String v) { this.waveUnitType   = v; }
    public void setUnitSpeed(double v)    { this.unitSpeed      = v; }

    public boolean isActive()                       { return active;         }
    public EnemyStrategy getEnemyStrategy()        { return enemyStrategy; }
    public void setEnemyStrategy(EnemyStrategy s)  { this.enemyStrategy = s; }

    /** Serialisable snapshot for the REST response body. */
    public record Snapshot(boolean active, int waveSpawnFrame, int waveUnitCount,
                           String waveUnitType, double unitSpeed) {}

    public Snapshot snapshot() {
        return new Snapshot(active, waveSpawnFrame, waveUnitCount, waveUnitType, unitSpeed);
    }
}
