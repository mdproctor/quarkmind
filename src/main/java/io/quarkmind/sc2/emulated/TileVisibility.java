package io.quarkmind.sc2.emulated;

public enum TileVisibility {
    /** Never entered any friendly observer's vision radius. */
    UNSEEN,
    /** Was visible at some point; terrain remembered, no units shown. */
    MEMORY,
    /** Currently within a friendly observer's vision radius. */
    VISIBLE
}
