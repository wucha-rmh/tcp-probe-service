package dev.tcpprobe.http.handler;

import dev.tcpprobe.error.ApiException;
import dev.tcpprobe.http.Handler;
import dev.tcpprobe.http.Request;
import dev.tcpprobe.http.Response;
import dev.tcpprobe.http.Views;
import dev.tcpprobe.service.ProbeService;
import dev.tcpprobe.service.ProbeTask;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /api/v1/probes/{taskId}}.
 *
 * <p>Returns 200 with results when the task is finished, 202 with the current state otherwise.
 * {@code ?waitMs=N} (up to 10s) long-polls for completion, which spares clients a tight polling
 * loop without introducing websockets or callbacks.</p>
 */
public final class TaskHandler implements Handler {

    private static final int MAX_LONG_POLL_MS = 10_000;

    private final ProbeService service;

    public TaskHandler(ProbeService service) {
        this.service = service;
    }

    @Override
    public void handle(Request request, Response response, Map<String, String> pathParams) {
        String taskId = pathParams.get("taskId");
        // Validate the query before the lookup: a malformed request is a 400 no matter
        // whether the task exists, and it keeps error codes predictable for clients.
        int waitMs = request.queryInt("waitMs", 0, 0, MAX_LONG_POLL_MS);
        ProbeTask task = service.find(taskId)
                .orElseThrow(() -> ApiException.notFound("task not found: " + taskId));

        if (waitMs > 0 && !task.isFinished()) {
            try {
                task.completion().get(waitMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
                // Long poll expired or the task failed; the body below carries the real state.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        response.json(task.isFinished() ? 200 : 202, Views.task(task));
    }
}
