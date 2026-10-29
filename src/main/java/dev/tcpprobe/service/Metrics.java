package dev.tcpprobe.service;

import dev.tcpprobe.probe.ProbeStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide counters exposed through {@code GET /api/v1/stats}.
 *
 * <p>Only monotonic counters and gauges are kept: no histograms, no percentiles. That is enough to
 * operate the service and answer "is it rate limiting, is it saturated, are probes failing?" while
 * staying dependency-free and cheap to update from many threads.</p>
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

    /** Immutable snapshot ready for JSON rendering. */
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
