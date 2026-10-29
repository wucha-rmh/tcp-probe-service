package dev.tcpprobe.http.handler;

import dev.tcpprobe.http.Handler;
import dev.tcpprobe.http.Request;
import dev.tcpprobe.http.Response;
import dev.tcpprobe.service.Metrics;

import java.util.Map;

/** {@code GET /api/v1/stats}: counters an operator needs to tell "busy" from "broken". */
public final class StatsHandler implements Handler {

    private final Metrics metrics;

    public StatsHandler(Metrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void handle(Request request, Response response, Map<String, String> pathParams) {
        response.json(metrics.snapshot());
    }
}
