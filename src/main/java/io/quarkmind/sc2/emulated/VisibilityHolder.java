package io.quarkmind.sc2.emulated;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * CDI bridge exposing the current VisibilityGrid to GameStateBroadcaster.
 * Updated by EmulatedEngine each tick. Null in mock/replay/SC2 modes.
 * ApplicationScoped (no profile guard) so GameStateBroadcaster can always inject it.
 */
@ApplicationScoped
public class VisibilityHolder {

    private volatile VisibilityGrid current;

    public void set(VisibilityGrid g) { current = g; }

    /** Returns null when not in emulated mode. */
    public VisibilityGrid get() { return current; }
}
