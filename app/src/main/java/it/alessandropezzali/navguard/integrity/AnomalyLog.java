package it.alessandropezzali.navguard.integrity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded, in-memory, FIFO log of significant events.
 * Never persisted, never exported, never sent anywhere: it lives and dies with the process.
 * Only significant events are recorded here; ordinary fixes are not.
 */
public final class AnomalyLog {
    public static final int MAX_EVENTS = 100;

    private final Deque<AnomalyEvent> events = new ArrayDeque<>();

    public void add(AnomalyEvent event) {
        if (event == null) return;
        events.addLast(event);
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
    }

    public int size() {
        return events.size();
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

    public void clear() {
        events.clear();
    }

    /** Oldest first. */
    public List<AnomalyEvent> all() {
        return new ArrayList<>(events);
    }

    /** Newest first, at most {@code limit} entries. */
    public List<AnomalyEvent> recent(int limit) {
        List<AnomalyEvent> out = new ArrayList<>();
        AnomalyEvent[] arr = events.toArray(new AnomalyEvent[0]);
        for (int i = arr.length - 1; i >= 0 && out.size() < limit; i--) {
            out.add(arr[i]);
        }
        return out;
    }

    public AnomalyEvent oldest() {
        return events.peekFirst();
    }

    public AnomalyEvent newest() {
        return events.peekLast();
    }
}
