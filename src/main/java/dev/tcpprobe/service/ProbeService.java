package dev.tcpprobe.service;

import dev.tcpprobe.ProbeProperties;
import dev.tcpprobe.probe.ProbeResult;
import dev.tcpprobe.probe.ProbeStatus;
import dev.tcpprobe.probe.ProbeTarget;
import dev.tcpprobe.probe.TcpProber;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 批量探测的调度与状态存储。
 *
 * <p>线程模型只有两层：</p>
 * <ul>
 *   <li>{@code taskPool}（大小 {@code workers}）每个任务占一个线程，直到整批结束。
 *       这同时是“同时有多少批在跑”的上限。</li>
 *   <li>{@code probePool}（大小 {@code maxInFlight}）执行单个目标的探测。池的队列就是背压：
 *       目标一多，队列变长，批级截止时间会把还没轮到的目标变成 {@code TIMEOUT}，
 *       而不是让请求无限期地堆着。</li>
 * </ul>
 *
 * <p>{@code maxConcurrentTasks} 个准入许可在提交时拿，拿不到直接返回 503，
 * 这是一个诚实的“稍后再来”，比悄悄排队直到老化更强。</p>
 */
@Service
public class ProbeService {

    private static final Logger log = LoggerFactory.getLogger(ProbeService.class);

    private final ProbeProperties properties;
    private final TcpProber prober;
    private final RateLimiter rateLimiter;
    private final ExecutorService taskPool;
    private final ExecutorService probePool;
    private final Semaphore taskSlots;
    private final ConcurrentHashMap<String, ProbeTask> tasks = new ConcurrentHashMap<>();

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    /** 因为并发任务已满被拒（503）的提交次数；429 由限流器自己统计。 */
    private final AtomicLong rejectedBusy = new AtomicLong();
    private final AtomicInteger tasksRunning = new AtomicInteger();
    private final AtomicInteger probesInFlight = new AtomicInteger();
    private final ConcurrentHashMap<ProbeStatus, AtomicLong> probesByStatus = new ConcurrentHashMap<>();

    public ProbeService(ProbeProperties properties, TcpProber prober, RateLimiter rateLimiter) {
        this.properties = properties;
        this.prober = prober;
        this.rateLimiter = rateLimiter;
        this.taskSlots = new Semaphore(properties.maxConcurrentTasks());
        this.taskPool = Executors.newFixedThreadPool(properties.workers(), named("task", false));
        this.probePool = Executors.newFixedThreadPool(properties.maxInFlight(), named("probe", true));
        log.info("probe service ready: {}", properties.describe());
    }

    /**
     * 提交一批目标，立即返回任务句柄。
     *
     * @throws dev.tcpprobe.web.ApiException 目标为空/超量时为 400，任务槽位耗尽时为 503
     */
    public ProbeTask submit(List<ProbeTarget> targets, int timeoutMs, ProbeTask.Mode mode) {
        if (targets.isEmpty()) {
            throw dev.tcpprobe.web.ApiException.badRequest("EMPTY_TARGETS", "at least one target is required");
        }
        if (targets.size() > properties.maxTargetsPerRequest()) {
            throw dev.tcpprobe.web.ApiException.badRequest("TOO_MANY_TARGETS",
                    "at most " + properties.maxTargetsPerRequest() + " targets per request");
        }
        if (!taskSlots.tryAcquire()) {
            rejectedBusy.incrementAndGet();
            throw dev.tcpprobe.web.ApiException.serverBusy();
        }

        evictExpired();
        ProbeTask task = new ProbeTask(nextId(), targets, timeoutMs, mode);
        tasks.put(task.id(), task);
        submitted.incrementAndGet();
        try {
            taskPool.execute(() -> execute(task));
        } catch (RejectedExecutionException e) {
            taskSlots.release();
            task.fail("probe service is shutting down");
            tasks.remove(task.id(), task);
            failed.incrementAndGet();
        }
        return task;
    }

    /** 执行一批目标；结果顺序与请求中的目标顺序一致。 */
    void execute(ProbeTask task) {
        tasksRunning.incrementAndGet();
        task.markRunning();
        try {
            List<Callable<ProbeResult>> calls = new ArrayList<>(task.targets().size());
            for (ProbeTarget target : task.targets()) {
                calls.add(() -> {
                    probesInFlight.incrementAndGet();
                    try {
                        ProbeResult result = prober.probe(target, task.timeoutMs());
                        probesByStatus.computeIfAbsent(result.status(), ignored -> new AtomicLong()).incrementAndGet();
                        return result;
                    } finally {
                        probesInFlight.decrementAndGet();
                    }
                });
            }

            long deadlineMs = properties.batchDeadlineMs(calls.size());
            List<Future<ProbeResult>> futures =
                    probePool.invokeAll(calls, deadlineMs, TimeUnit.MILLISECONDS);

            List<ProbeResult> results = new ArrayList<>(futures.size());
            for (int i = 0; i < futures.size(); i++) {
                Future<ProbeResult> future = futures.get(i);
                if (future.isCancelled()) {
                    ProbeTarget target = task.targets().get(i);
                    results.add(ProbeResult.failed(target, ProbeStatus.TIMEOUT,
                            "batch deadline of " + deadlineMs + " ms exceeded before this target was probed"));
                    probesByStatus.computeIfAbsent(ProbeStatus.TIMEOUT, ignored -> new AtomicLong()).incrementAndGet();
                } else {
                    results.add(future.get());
                }
            }
            task.complete(results);
            succeeded.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.fail("probe batch was interrupted");
            failed.incrementAndGet();
        } catch (ExecutionException | RuntimeException e) {
            log.error("probe batch {} failed", task.id(), e);
            task.fail(e instanceof ExecutionException && e.getCause() != null
                    ? String.valueOf(e.getCause()) : String.valueOf(e));
            failed.incrementAndGet();
        } finally {
            tasksRunning.decrementAndGet();
            taskSlots.release();
        }
    }

    /** 按 id 取任务；顺带清理过期任务。 */
    public Optional<ProbeTask> find(String taskId) {
        evictExpired();
        return Optional.ofNullable(tasks.get(taskId));
    }

    public RateLimiter rateLimiter() {
        return rateLimiter;
    }

    /** {@code /api/v1/stats} 的内容。 */
    public Map<String, Object> stats() {
        Map<String, Object> tasksView = new LinkedHashMap<>();
        tasksView.put("submitted", submitted.get());
        tasksView.put("running", tasksRunning.get());
        tasksView.put("succeeded", succeeded.get());
        tasksView.put("failed", failed.get());
        tasksView.put("rejectedBusy", rejectedBusy.get());
        tasksView.put("stored", tasks.size());

        Map<String, Object> probesView = new LinkedHashMap<>();
        long attempted = 0;
        for (ProbeStatus status : ProbeStatus.values()) {
            AtomicLong counter = probesByStatus.get(status);
            long value = counter == null ? 0 : counter.get();
            attempted += value;
            probesView.put(status.name().toLowerCase(java.util.Locale.ROOT), value);
        }
        probesView.put("attempted", attempted);
        probesView.put("inFlight", probesInFlight.get());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("tasks", tasksView);
        stats.put("probes", probesView);

        Map<String, Object> rateLimitView = new LinkedHashMap<>();
        rateLimitView.put("denied", rateLimiter.deniedRequests());
        rateLimitView.put("global", rateLimiter.deniedByGlobal());
        rateLimitView.put("client", rateLimiter.deniedByClient());
        stats.put("rateLimit", rateLimitView);
        // 保留旧字段：它是「当前跟踪的客户端桶数量」，和拒绝次数是两回事。
        stats.put("rateLimitedClients", rateLimiter.trackedClients());
        return stats;
    }

    /** 删除超过 TTL 的已结束任务；提交和查询时顺手做，省掉一个定时线程。 */
    void evictExpired() {
        Duration ttl = Duration.ofSeconds(properties.taskTtlSeconds());
        java.time.Instant cutoff = java.time.Instant.now().minus(ttl);
        tasks.entrySet().removeIf(entry -> {
            ProbeTask task = entry.getValue();
            return task.isFinished()
                    && task.finishedAt() != null
                    && task.finishedAt().isBefore(cutoff);
        });
    }

    private static String nextId() {
        return "p_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static ThreadFactory named(String prefix, boolean daemon) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(daemon);
            return thread;
        };
    }

    @PreDestroy
    public void shutdown() {
        taskPool.shutdownNow();
        probePool.shutdownNow();
        prober.close();
    }
}
