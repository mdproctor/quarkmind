package io.quarkmind.qa;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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

    @ConfigProperty(name = "emulated.wave.spawn-frame", defaultValue = "200")
    int defaultWaveSpawnFrame;

    @ConfigProperty(name = "emulated.wave.unit-count", defaultValue = "4")
    int defaultWaveUnitCount;

    @ConfigProperty(name = "emulated.wave.unit-type", defaultValue = "ZEALOT")
    String defaultWaveUnitType;

    @ConfigProperty(name = "emulated.unit.speed", defaultValue = "0.5")
    double defaultUnitSpeed;

    // Volatile for thread safety (REST thread writes, scheduler thread reads)
    private volatile int    waveSpawnFrame;
    private volatile int    waveUnitCount;
    private volatile String waveUnitType;
    private volatile double unitSpeed;

    @PostConstruct
    void init() {
        waveSpawnFrame = defaultWaveSpawnFrame;
        waveUnitCount  = defaultWaveUnitCount;
        waveUnitType   = defaultWaveUnitType;
        unitSpeed      = defaultUnitSpeed;
    }

    public int    getWaveSpawnFrame() { return waveSpawnFrame; }
    public int    getWaveUnitCount()  { return waveUnitCount;  }
    public String getWaveUnitType()   { return waveUnitType;   }
    public double getUnitSpeed()      { return unitSpeed;      }

    public void setWaveSpawnFrame(int v)  { this.waveSpawnFrame = v; }
    public void setWaveUnitCount(int v)   { this.waveUnitCount  = v; }
    public void setWaveUnitType(String v) { this.waveUnitType   = v; }
    public void setUnitSpeed(double v)    { this.unitSpeed      = v; }

    /** Serialisable snapshot for the REST response body. */
    public record Snapshot(int waveSpawnFrame, int waveUnitCount,
                           String waveUnitType, double unitSpeed) {}

    public Snapshot snapshot() {
        return new Snapshot(waveSpawnFrame, waveUnitCount, waveUnitType, unitSpeed);
    }
}
