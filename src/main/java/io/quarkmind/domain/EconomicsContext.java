package io.quarkmind.domain;

/**
 * Quarkus Flow workflow context for the economics workflow.
 *
 * <p>Carries phase state and nexus count across ticks. Phase drives which
 * decisions are active; nexusCount determines probeTarget (22 × nexusCount)
 * for multi-base probe management (initial implementation uses 1 base).
 */
public record EconomicsContext(Phase phase, int nexusCount) {

    public enum Phase {
        /** Normal operation: probe saturation + supply management. */
        SATURATION,
        /** Saturation reached: expansion nexus queued, awaiting build. */
        EXPANDING
    }

    /** Probe target scales with active nexuses. */
    public int probeTarget() {
        return nexusCount * 22;
    }

    public static EconomicsContext initial() {
        return new EconomicsContext(Phase.SATURATION, 1);
    }
}
