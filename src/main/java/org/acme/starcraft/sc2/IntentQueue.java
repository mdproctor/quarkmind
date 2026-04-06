package org.acme.starcraft.sc2;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.starcraft.sc2.intent.Intent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@ApplicationScoped
public class IntentQueue {
    private final ConcurrentLinkedQueue<Intent> queue = new ConcurrentLinkedQueue<>();
    private final List<Intent> dispatched = new ArrayList<>();
    private static final int DISPATCHED_BUFFER_SIZE = 100;

    public void add(Intent intent) {
        queue.add(intent);
    }

    public List<Intent> drainAll() {
        List<Intent> drained = new ArrayList<>();
        Intent intent;
        while ((intent = queue.poll()) != null) {
            drained.add(intent);
        }
        synchronized (dispatched) {
            dispatched.addAll(drained);
            if (dispatched.size() > DISPATCHED_BUFFER_SIZE) {
                dispatched.subList(0, dispatched.size() - DISPATCHED_BUFFER_SIZE).clear();
            }
        }
        return drained;
    }

    public List<Intent> pending() {
        return List.copyOf(queue);
    }

    public List<Intent> recentlyDispatched() {
        synchronized (dispatched) {
            return List.copyOf(dispatched);
        }
    }
}
