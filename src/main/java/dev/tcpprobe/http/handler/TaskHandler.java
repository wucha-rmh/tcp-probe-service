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
 * <p>任务完成时返回 200 及结果，否则返回 202 及当前状态。
 * {@code ?waitMs=N}（最大 10s）会为等待完成做长轮询，让客户端免于密集的轮询循环，
 * 同时不必引入 websockets 或回调。</p>
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
        // 在查找之前先校验查询参数：无论任务是否存在，格式错误的请求都返回 400，
        // 这也让错误码对客户端来说更可预测。
        int waitMs = request.queryInt("waitMs", 0, 0, MAX_LONG_POLL_MS);
        ProbeTask task = service.find(taskId)
                .orElseThrow(() -> ApiException.notFound("task not found: " + taskId));

        if (waitMs > 0 && !task.isFinished()) {
            try {
                task.completion().get(waitMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
                // 长轮询已超时或任务失败；下面的请求体携带真实状态。
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        response.json(task.isFinished() ? 200 : 202, Views.task(task));
    }
}
