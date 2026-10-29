package dev.tcpprobe.service;

import dev.tcpprobe.probe.ProbeResult;
import dev.tcpprobe.probe.ProbeStatus;
import dev.tcpprobe.probe.ProbeTarget;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个已提交的探测目标批次及其生命周期。
 *
 * <p>任务总是异步执行；{@code mode} 只记录调用方是在同一个 HTTP 请求上等待结果（sync），
 * 还是拿走任务 id 之后轮询（async）。只保留一条执行路径，可以避免为同样的工作维护两条
 * 有微妙差异的代码路径。</p>
 */
public final class ProbeTask {

    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public enum Mode {
        SYNC,
        ASYNC
    }

    private final String id;
    private final List<ProbeTarget> targets;
    private final int timeoutMs;
    private final Mode mode;
    private final Instant createdAt = Instant.now();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final AtomicBoolean finished = new AtomicBoolean();

    private volatile Status status = Status.PENDING;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile List<ProbeResult> results = List.of();
    private volatile String error;

    public ProbeTask(String id, List<ProbeTarget> targets, int timeoutMs, Mode mode) {
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

    public Status status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
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
        return status == Status.SUCCEEDED || status == Status.FAILED;
    }

    /** 当任务到达终态时完成；长轮询和 sync 模式会用到它。 */
    public CompletableFuture<Void> completion() {
        return completion;
    }

    void markRunning() {
        if (status == Status.PENDING) {
            startedAt = Instant.now();
            status = Status.RUNNING;
        }
    }

    void complete(List<ProbeResult> probeResults) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        this.results = List.copyOf(probeResults);
        this.finishedAt = Instant.now();
        this.status = Status.SUCCEEDED;
        completion.complete(null);
    }

    void fail(String message) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        this.error = message;
        this.finishedAt = Instant.now();
        this.status = Status.FAILED;
        completion.complete(null);
    }

    /** 聚合后的计数，适合仪表盘，也适合对某个批次做断言的测试。 */
    public Map<String, Object> summary() {
        Map<ProbeStatus, Integer> counts = new LinkedHashMap<>();
        for (ProbeStatus s : ProbeStatus.values()) {
            counts.put(s, 0);
        }
        long latencySum = 0;
        int latencyCount = 0;
        for (ProbeResult r : results) {
            counts.merge(r.status(), 1, Integer::sum);
            if (r.latencyMs() != null) {
                latencySum += r.latencyMs();
                latencyCount++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connected", counts.get(ProbeStatus.CONNECTED));
        out.put("refused", counts.get(ProbeStatus.REFUSED));
        out.put("timeout", counts.get(ProbeStatus.TIMEOUT));
        out.put("unresolved", counts.get(ProbeStatus.UNRESOLVED));
        out.put("unreachable", counts.get(ProbeStatus.UNREACHABLE));
        out.put("error", counts.get(ProbeStatus.ERROR));
        out.put("averageLatencyMs", latencyCount == 0 ? null : Math.round((double) latencySum / latencyCount * 1000.0) / 1000.0);
        return out;
    }
}
