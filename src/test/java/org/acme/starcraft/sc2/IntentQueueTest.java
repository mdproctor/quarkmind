package org.acme.starcraft.sc2;

import org.acme.starcraft.domain.Point2d;
import org.acme.starcraft.domain.UnitType;
import org.acme.starcraft.sc2.intent.AttackIntent;
import org.acme.starcraft.sc2.intent.MoveIntent;
import org.acme.starcraft.sc2.intent.TrainIntent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IntentQueueTest {

    @Test
    void addAndDrainIntents() {
        var queue = new IntentQueue();
        queue.add(new AttackIntent("tag-1", new Point2d(50, 50)));
        queue.add(new TrainIntent("tag-100", UnitType.ZEALOT));
        queue.add(new MoveIntent("tag-2", new Point2d(30, 30)));

        assertThat(queue.pending()).hasSize(3);
        var drained = queue.drainAll();
        assertThat(drained).hasSize(3);
        assertThat(queue.pending()).isEmpty();
    }

    @Test
    void drainEmptyQueueReturnsEmptyList() {
        var queue = new IntentQueue();
        assertThat(queue.drainAll()).isEmpty();
    }

    @Test
    void drainedIntentsAppearInRecentlyDispatched() {
        var queue = new IntentQueue();
        queue.add(new AttackIntent("tag-1", new Point2d(50, 50)));
        queue.add(new MoveIntent("tag-2", new Point2d(30, 30)));
        queue.drainAll();

        var dispatched = queue.recentlyDispatched();
        assertThat(dispatched).hasSize(2);
        assertThat(dispatched.get(0)).isInstanceOf(AttackIntent.class);
        assertThat(dispatched.get(1)).isInstanceOf(MoveIntent.class);
    }

    @Test
    void recentlyDispatchedIsCapppedAt100() {
        var queue = new IntentQueue();
        for (int i = 0; i < 150; i++) {
            queue.add(new MoveIntent("tag-" + i, new Point2d(i, i)));
        }
        queue.drainAll();

        assertThat(queue.recentlyDispatched()).hasSize(100);
        assertThat(queue.pending()).isEmpty();
    }
}
