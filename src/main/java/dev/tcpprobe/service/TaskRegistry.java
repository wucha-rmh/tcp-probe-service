package dev.tcpprobe.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已提交任务的内存存储。
 *
 * <p>任务只保留有限时间（{@code task.ttl-seconds}），这样客户端可以先提交、稍后再轮询；
 * 同时 map 有容量上限，一波突发提交不会耗尽堆内存。驱逐时优先选择已结束的任务，最旧的先出。</p>
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

    /** 删除结束时间早于 {@code ttl} 的已结束任务。 */
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
                // 创建时间相同很常见（同一毫秒）；再用 id 做并列时的取舍，让驱逐保持确定性，
                // 而不是依赖 hash-map 的迭代顺序。
                .sorted(java.util.Comparator.comparing(ProbeTask::createdAt).thenComparing(ProbeTask::id))
                .limit(count)
                .forEach(t -> tasks.remove(t.id(), t));
    }
}
