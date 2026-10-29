package dev.tcpprobe.service;

import dev.tcpprobe.probe.ProbeResult;
import dev.tcpprobe.probe.ProbeStatus;
import dev.tcpprobe.probe.ProbeTarget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRegistryTest {

    private static ProbeTask finishedTask(String id) {
        ProbeTask task = new ProbeTask(id, List.of(ProbeTarget.parse("127.0.0.1:1")), 100, ProbeTask.Mode.ASYNC);
        task.markRunning();
        task.complete(List.of(ProbeResult.of(task.targets().get(0), ProbeStatus.REFUSED, 1L, null, null)));
        return task;
    }

    @Test
    void storesAndFindsTasks() {
        TaskRegistry registry = new TaskRegistry(10);
        ProbeTask task = finishedTask("p_1");
        registry.put(task);
        assertTrue(registry.get("p_1").isPresent());
        assertTrue(registry.get("missing").isEmpty());
    }

    @Test
    void evictsExpiredFinishedTasks() throws InterruptedException {
        TaskRegistry registry = new TaskRegistry(10);
        registry.put(finishedTask("p_1"));
        // Instant 在 Windows 上精度很粗：TTL=0 时 cutoff 必须严格晚于 finishedAt，
        // 否则比较会变成同一 tick 的平局，什么都驱逐不掉。
        Thread.sleep(20);
        assertEquals(1, registry.evictFinishedOlderThan(Duration.ZERO));
        assertTrue(registry.get("p_1").isEmpty());
    }

    @Test
    void keepsTasksYoungerThanTtl() {
        TaskRegistry registry = new TaskRegistry(10);
        registry.put(finishedTask("p_1"));
        assertEquals(0, registry.evictFinishedOlderThan(Duration.ofHours(1)));
        assertTrue(registry.get("p_1").isPresent());
    }

    @Test
    void capsSizeByDroppingOldestFinishedTasks() {
        TaskRegistry registry = new TaskRegistry(2);
        for (String id : List.of("p_1", "p_2", "p_3")) {
            registry.put(finishedTask(id));
        }
        assertEquals(2, registry.size());
        assertTrue(registry.get("p_1").isEmpty(), "the oldest finished task goes first");
        assertTrue(registry.get("p_3").isPresent());
    }
}
