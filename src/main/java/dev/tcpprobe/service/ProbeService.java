package dev.tcpprobe.service;

import dev.tcpprobe.config.Config;
import dev.tcpprobe.error.ApiException;
import dev.tcpprobe.probe.ProbeResult;
import dev.tcpprobe.probe.ProbeStatus;
import dev.tcpprobe.probe.ProbeTarget;
import dev.tcpprobe.probe.TcpProber;
import dev.tcpprobe.util.Log;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在后台执行探测批次，并把结果记录为 {@link ProbeTask}。
 *
 * <p>并发模型：</p>
 * <ul>
 *   <li>{@code taskWorkers} 个平台线程运行整个批次。一个批次在等待其探测时会阻塞，因此这也
 *       限制了在途批次的个数。</li>
 *   <li>{@code maxProbesInFlight} 个线程组成探测池。池队列本身就是天然的背压：批次一多，
 *       队列就会增长，而按批次的截止时间会把长期得不到执行的探测变成 {@code TIMEOUT}
 *       结果，而不是无上限增长的延迟。</li>
 *   <li>{@code maxConcurrentTasks} 个许可在提交时获取。许可耗尽时，API 返回
 *       {@code 503 SERVER_BUSY}，这是一个诚实的“稍后再来”信号，而不是一个悄悄排队直到
 *       老化成超时的队列。</li>
 * </ul>
 */
public final class ProbeService {

    private final Config config;
    private final TcpProber prober;
    private final TaskRegistry registry;
    private final Metrics metrics;
    private final ExecutorService taskExecutor;
    private final ExecutorService probeExecutor;
    private final Semaphore taskSlots;

    public ProbeService(Config config, TcpProber prober, TaskRegistry registry, Metrics metrics) {
        this.config = config;
        this.prober = prober;
        this.registry = registry;
        this.metrics = metrics;
        this.taskSlots = new Semaphore(config.maxConcurrentTasks(), true);
        this.taskExecutor = Executors.newFixedThreadPool(config.taskWorkers(), named("task", false));
        this.probeExecutor = Executors.newFixedThreadPool(config.maxProbesInFlight(), named("probe", true));
    }

    /**
     * 不做任何校验，直接排期：处理器已经校验过目标。
     *
     * @throws ApiException 当任务槽位预算耗尽时抛出 {@code 503 SERVER_BUSY}
     */
    public ProbeTask submit(List<ProbeTarget> targets, int timeoutMs, ProbeTask.Mode mode) {
        if (targets == null || targets.isEmpty()) {
            throw ApiException.badRequest("EMPTY_TARGETS", "at least one target is required");
        }
        if (targets.size() > config.maxTargetsPerRequest()) {
            throw ApiException.badRequest("TOO_MANY_TARGETS",
                    "at most " + config.maxTargetsPerRequest() + " targets per request, got " + targets.size());
        }
        if (!taskSlots.tryAcquire()) {
            throw ApiException.serverBusy("too many concurrent probe tasks, retry later");
        }
        ProbeTask task = new ProbeTask(newTaskId(), targets, timeoutMs, mode);
        registry.put(task);
        metrics.taskSubmitted();
        try {
            taskExecutor.execute(() -> {
                try {
                    execute(task);
                } catch (RuntimeException e) {
                    Log.error("task %s failed unexpectedly".formatted(task.id()), e);
                    task.fail("internal error: " + e.getMessage());
                    metrics.taskFinished(false);
                } finally {
                    taskSlots.release();
                }
            });
        } catch (RuntimeException e) {
            taskSlots.release();
            throw ApiException.serverBusy("probe executor rejected the task: " + e.getMessage());
        }
        return task;
    }

    /**
     * 运行一个批次。包级可见性使执行细节留在服务内部，同时仍然允许测试同步驱动一个批次。
     */
    void execute(ProbeTask task) {
        task.markRunning();
        metrics.taskStarted();
        boolean success = false;
        try {
            List<ProbeTarget> targets = task.targets();
            List<Callable<ProbeResult>> calls = new ArrayList<>(targets.size());
            for (ProbeTarget target : targets) {
                calls.add(() -> probeOne(target, task.timeoutMs()));
            }

            long deadlineNanos = TimeUnit.MILLISECONDS.toNanos(config.overallDeadlineMs(targets.size()));
            List<Future<ProbeResult>> futures = probeExecutor.invokeAll(calls, deadlineNanos, TimeUnit.NANOSECONDS);

            List<ProbeResult> results = new ArrayList<>(targets.size());
            for (int i = 0; i < futures.size(); i++) {
                ProbeTarget target = targets.get(i);
                Future<ProbeResult> future = futures.get(i);
                if (future.isCancelled()) {
                    metrics.probeCancelled(ProbeStatus.TIMEOUT);
                    results.add(ProbeResult.of(target, ProbeStatus.TIMEOUT, null, null,
                            "batch deadline exceeded before the connection attempt started"));
                    continue;
                }
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    results.add(ProbeResult.of(target, ProbeStatus.ERROR, null, null,
                            cause.getClass().getSimpleName() + ": " + cause.getMessage()));
                }
            }
            task.complete(results);
            success = true;
            Log.debug("task %s finished: %d targets in %d ms", task.id(), results.size(),
                    Duration.between(task.createdAt(), java.time.Instant.now()).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.fail("interrupted while probing");
        } catch (RuntimeException e) {
            task.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            metrics.taskFinished(success);
        }
    }

    private ProbeResult probeOne(ProbeTarget target, int timeoutMs) {
        metrics.probeStarted();
        ProbeResult result;
        try {
            result = prober.probe(target, timeoutMs);
        } catch (RuntimeException e) {
            result = ProbeResult.of(target, ProbeStatus.ERROR, null, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        metrics.probeFinished(result.status());
        return result;
    }

    public Optional<ProbeTask> find(String taskId) {
        return registry.get(taskId);
    }

    /** 驱逐已过期的任务和空闲的限流器状态；由调度器周期性调用。 */
    public int evictExpired() {
        int removed = registry.evictFinishedOlderThan(Duration.ofSeconds(config.taskTtlSeconds()));
        metrics.taskExpired(removed);
        return removed;
    }

    public Metrics metrics() {
        return metrics;
    }

    public Config config() {
        return config;
    }

    public void shutdown(Duration grace) {
        taskExecutor.shutdownNow();
        probeExecutor.shutdownNow();
        try {
            if (!taskExecutor.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                Log.warn("task workers did not terminate within %d ms", grace.toMillis());
            }
            if (!probeExecutor.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                Log.warn("probe workers did not terminate within %d ms", grace.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 服务拥有 prober，而 prober 又拥有 DNS resolver 池。
            prober.close();
        }
    }

    private String newTaskId() {
        return "p_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static ThreadFactory named(String prefix, boolean daemon) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(daemon);
            t.setUncaughtExceptionHandler((thread, ex) -> Log.error("uncaught exception in " + thread.getName(), ex));
            return t;
        };
    }
}
