package dev.tcpprobe.service;

import dev.tcpprobe.probe.ProbeResult;
import dev.tcpprobe.probe.ProbeStatus;
import dev.tcpprobe.probe.ProbeTarget;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 一个已提交的目标批次及其生命周期。
 *
 * <p>任务永远异步执行；{@code mode} 只说明调用方是在同一个 HTTP 请求上等结果（sync），
 * 还是拿着任务 id 轮询（async）。只保留一条执行路径，就不必维护两套有细微差异的代码。</p>
 */
public final class ProbeTask {

    public enum Mode { SYNC, ASYNC }

    public enum State { PENDING, RUNNING, SUCCEEDED, FAILED }

    private final String id;
    private final List<ProbeTarget> targets;
    private final int timeoutMs;
    private final Mode mode;
    private final Instant createdAt = Instant.now();
    /** 完成时触发；sync 模式和长轮询都等它。 */
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    private volatile State state = State.PENDING;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile List<ProbeResult> results = List.of();
    private volatile String error;

    ProbeTask(String id, List<ProbeTarget> targets, int timeoutMs, Mode mode) {
        this.id = id;
        this.targets = List.copyOf(targets);
        this.timeoutMs = timeoutMs;
        this.mode = mode;
    }

    public String id() {
        return id;
    }

    public List<ProbeTarget> targets() {
        return targets;
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    public Mode mode() {
        return mode;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public State state() {
        return state;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public List<ProbeResult> results() {
        return results;
    }

    public String error() {
        return error;
    }

    public boolean isFinished() {
        return state == State.SUCCEEDED || state == State.FAILED;
    }

    void markRunning() {
        if (state == State.PENDING) {
            startedAt = Instant.now();
            state = State.RUNNING;
        }
    }

    void complete(List<ProbeResult> results) {
        this.results = List.copyOf(results);
        this.finishedAt = Instant.now();
        this.state = State.SUCCEEDED;
        completion.complete(null);
    }

    void fail(String error) {
        this.error = error;
        this.finishedAt = Instant.now();
        this.state = State.FAILED;
        completion.complete(null);
    }

    /**
     * 最多等 {@code millis} 毫秒。
     *
     * @return 到期时任务是否已经结束
     */
    public boolean await(long millis) {
        if (isFinished()) {
            return true;
        }
        try {
            completion.get(Math.max(0L, millis), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return isFinished();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // completion 只会正常完成，这个分支理论上不可达。
            return isFinished();
        }
        return isFinished();
    }

    /** 按状态计数的摘要，适合直接放进响应，也方便测试断言。 */
    public Map<String, Object> summary() {
        Map<ProbeStatus, Integer> counts = new EnumMap<>(ProbeStatus.class);
        for (ProbeResult result : results) {
            counts.merge(result.status(), 1, Integer::sum);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        for (ProbeStatus status : ProbeStatus.values()) {
            summary.put(status.name().toLowerCase(Locale.ROOT), counts.getOrDefault(status, 0));
        }
        long samples = results.stream().filter(result -> result.latencyMs() != null).count();
        double average = results.stream()
                .filter(result -> result.latencyMs() != null)
                .mapToLong(ProbeResult::latencyMs)
                .average()
                .orElse(0.0);
        summary.put("averageLatencyMs", samples == 0 ? null : Math.round(average * 1000.0) / 1000.0);
        return summary;
    }
}
