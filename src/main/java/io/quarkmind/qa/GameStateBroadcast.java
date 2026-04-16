package io.quarkmind.qa;

import io.quarkmind.domain.GameState;

/**
 * WebSocket broadcast envelope wrapping GameState with an optional visibility string.
 * visibility is a 4096-char flat string ('0'=UNSEEN, '1'=MEMORY, '2'=VISIBLE),
 * encoded as tiles[y*64+x]. Null in non-emulated modes.
 */
public record GameStateBroadcast(GameState state, String visibility) {}
