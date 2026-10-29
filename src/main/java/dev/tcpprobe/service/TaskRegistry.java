package dev.tcpprobe.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of submitted tasks.
 *
 * <p>Tasks are kept for a bounded time ({@code task.ttl-seconds}) so that a client can submit and
 * poll later, and the map is capped so that a burst of submissions cannot exhaust the heap.
 * Eviction prefers finished tasks, oldest first.</p>
 */
public final class TaskRegistry {

    private final ConcurrentHashMap<String, ProbeTask> tasks = new ConcurrentHashMap<>();
    private final int maxTasks;

    public TaskRegistry(int maxTasks) {
        if (maxTasks < 1) {
            throw new IllegalArgumentException("maxTasks must be positive");
        }
        this.maxTasks = maxTasks;
    }

    public void put(ProbeTask task) {
        tasks.put(task.id(), task);
        if (tasks.size() > maxTasks) {
            evictOldestFinished(tasks.size() - maxTasks);
        }
    }

    public Optional<ProbeTask> get(String id) {
        return Optional.ofNullable(id == null ? null : tasks.get(id));
    }

    public int size() {
        return tasks.size();
    }

    /** Removes finished tasks whose completion time is older than {@code ttl}. */
    public int evictFinishedOlderThan(Duration ttl) {
        Instant cutoff = Instant.now().minus(ttl);
        int removed = 0;
        for (ProbeTask t : tasks.values()) {
            if (!t.isFinished()) {
                continue;
            }
            Instant finishedAt = t.finishedAt();
            Instant reference = finishedAt != null ? finishedAt : t.createdAt();
            if (reference.isBefore(cutoff) && tasks.remove(t.id(), t)) {
                removed++;
            }
        }
        return removed;
    }

    private void evictOldestFinished(int count) {
        if (count <= 0) {
            return;
        }
        tasks.values().stream()
                .filter(ProbeTask::isFinished)
                // created-at ties are common (same millisecond); the id tie-break keeps eviction
                // deterministic instead of depending on hash-map iteration order
                .sorted(java.util.Comparator.comparing(ProbeTask::createdAt).thenComparing(ProbeTask::id))
                .limit(count)
                .forEach(t -> tasks.remove(t.id(), t));
    }
}
