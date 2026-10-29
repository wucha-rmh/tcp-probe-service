package dev.tcpprobe.service;

import dev.tcpprobe.probe.ProbeStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 通过 {@code GET /api/v1/stats} 暴露的进程级计数器。
 *
 * <p>只保留单调计数器和 gauge：没有直方图，没有百分位。这足以运维这个服务并回答“是否在限流、
 * 是否已饱和、探测是否在失败”，同时保持无依赖，且从多个线程更新都很廉价。</p>
 */
public final class Metrics {

    private final long startedAtMillis = System.currentTimeMillis();

    private final LongAdder httpRequests = new LongAdder();
    private final LongAdder rateLimited = new LongAdder();
    private final LongAdder badRequests = new LongAdder();
    private final LongAdder serverErrors = new LongAdder();

    private final LongAdder tasksSubmitted = new LongAdder();
    private final LongAdder tasksSucceeded = new LongAdder();
    private final LongAdder tasksFailed = new LongAdder();
    private final LongAdder tasksExpired = new LongAdder();
    private final AtomicLong tasksRunning = new AtomicLong();

    private final LongAdder probesAttempted = new LongAdder();
    private final Map<ProbeStatus, LongAdder> probesByStatus = new LinkedHashMap<>();
    private final AtomicLong probesInFlight = new AtomicLong();

    public Metrics() {
        for (ProbeStatus s : ProbeStatus.values()) {
            probesByStatus.put(s, new LongAdder());
        }
    }

    public void httpRequest() {
        httpRequests.increment();
    }

    public void httpRateLimited() {
        rateLimited.increment();
    }

    public void httpBadRequest() {
        badRequests.increment();
    }

    public void httpServerError() {
        serverErrors.increment();
    }

    public void taskSubmitted() {
        tasksSubmitted.increment();
    }

    public void taskStarted() {
        tasksRunning.incrementAndGet();
    }

    public void taskFinished(boolean success) {
        tasksRunning.decrementAndGet();
        if (success) {
            tasksSucceeded.increment();
        } else {
            tasksFailed.increment();
        }
    }

    public void taskExpired(int count) {
        tasksExpired.add(count);
    }

    public void probeStarted() {
        probesInFlight.incrementAndGet();
        probesAttempted.increment();
    }

    public void probeFinished(ProbeStatus status) {
        probesInFlight.decrementAndGet();
        probesByStatus.get(status).increment();
    }

    public void probeCancelled(ProbeStatus status) {
        probesAttempted.increment();
        probesByStatus.get(status).increment();
    }

    public long uptimeSeconds() {
        return (System.currentTimeMillis() - startedAtMillis) / 1000L;
    }

    public long probesInFlight() {
        return probesInFlight.get();
    }

    public long tasksRunning() {
        return tasksRunning.get();
    }

    /** 可直接用于 JSON 渲染的不可变快照。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> probes = new LinkedHashMap<>();
        probes.put("attempted", probesAttempted.sum());
        for (Map.Entry<ProbeStatus, LongAdder> e : probesByStatus.entrySet()) {
            probes.put(e.getKey().name().toLowerCase(java.util.Locale.ROOT), e.getValue().sum());
        }

        Map<String, Object> tasks = new LinkedHashMap<>();
        tasks.put("submitted", tasksSubmitted.sum());
        tasks.put("succeeded", tasksSucceeded.sum());
        tasks.put("failed", tasksFailed.sum());
        tasks.put("expired", tasksExpired.sum());
        tasks.put("running", tasksRunning.get());

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("requests", httpRequests.sum());
        http.put("badRequests", badRequests.sum());
        http.put("rateLimited", rateLimited.sum());
        http.put("serverErrors", serverErrors.sum());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uptimeSeconds", uptimeSeconds());
        out.put("http", http);
        out.put("tasks", tasks);
        out.put("probes", probes);
        out.put("probesInFlight", probesInFlight.get());
        return out;
    }
}
