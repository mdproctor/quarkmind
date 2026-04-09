package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;

import java.util.Optional;

/**
 * A resolved SC2 command ready for dispatch via ActionInterface.
 * Package-private — only SC2BotAgent and ActionTranslator interact with this type.
 *
 * target is present for position-targeted commands (build, attack, move)
 * and empty for non-positional commands (train).
 */
record ResolvedCommand(Tag tag, Abilities ability, Optional<Point2d> target) {}
