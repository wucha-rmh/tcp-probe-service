package dev.tcpprobe.http.handler;

import dev.tcpprobe.http.Handler;
import dev.tcpprobe.http.Request;
import dev.tcpprobe.http.Response;
import dev.tcpprobe.service.Metrics;

import java.util.Map;

/** {@code GET /api/v1/stats}：运维人员用来区分“繁忙”与“故障”所需的计数器。 */
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
