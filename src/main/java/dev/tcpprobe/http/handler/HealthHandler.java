package dev.tcpprobe.http.handler;

import dev.tcpprobe.config.Config;
import dev.tcpprobe.http.Handler;
import dev.tcpprobe.http.Request;
import dev.tcpprobe.http.Response;
import dev.tcpprobe.service.Metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code GET /healthz}: process liveness only, so it stays cheap under load. */
public final class HealthHandler implements Handler {

    private final Config config;
    private final Metrics metrics;

    public HealthHandler(Config config, Metrics metrics) {
        this.config = config;
        this.metrics = metrics;
    }

    @Override
    public void handle(Request request, Response response, Map<String, String> pathParams) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("service", "tcp-probe-service");
        out.put("version", config.version());
        out.put("uptimeSeconds", metrics.uptimeSeconds());
        out.put("tasksRunning", metrics.tasksRunning());
        out.put("probesInFlight", metrics.probesInFlight());
        response.json(out);
    }
}
