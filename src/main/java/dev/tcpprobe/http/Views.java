package dev.tcpprobe.http;

import dev.tcpprobe.error.ApiException;
import dev.tcpprobe.probe.ProbeResult;
import dev.tcpprobe.service.ProbeTask;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 领域对象的 JSON 投影。
 *
 * <p>与领域 record 和 HTTP 基础设施都保持分离，这样线上格式可以独立演进
 * （重命名字段、隐藏内部字段）而不必改动二者。只有在任务完成后才会输出结果：
 * 部分结果会让调用方误把一个只填了一半的批量当作最终结果，而完成信号是明确的。</p>
 */
public final class Views {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private Views() {
    }

    public static Map<String, Object> task(ProbeTask task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.id());
        out.put("status", task.status().name());
        out.put("mode", task.mode().name().toLowerCase(java.util.Locale.ROOT));
        out.put("createdAt", iso(task.createdAt()));
        out.put("startedAt", iso(task.startedAt()));
        out.put("finishedAt", iso(task.finishedAt()));
        out.put("timeoutMs", task.timeoutMs());
        out.put("targetCount", task.targets().size());
        if (task.error() != null) {
            out.put("error", task.error());
        }
        if (task.isFinished()) {
            out.put("summary", task.summary());
            List<Map<String, Object>> results = new ArrayList<>(task.results().size());
            for (ProbeResult result : task.results()) {
                results.add(result(result));
            }
            out.put("results", results);
        }
        return out;
    }

    public static Map<String, Object> result(ProbeResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", result.target().display());
        out.put("host", result.target().host());
        out.put("port", result.target().port());
        out.put("status", result.status().name());
        if (result.latencyMs() != null) {
            out.put("latencyMs", result.latencyMs());
        }
        if (result.dnsMs() != null) {
            out.put("dnsMs", result.dnsMs());
        }
        if (result.detail() != null) {
            out.put("detail", result.detail());
        }
        return out;
    }

    public static Map<String, Object> error(ApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        if (!e.details().isEmpty()) {
            body.put("details", e.details());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", e.status());
        out.put("error", body);
        return out;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : ISO.format(instant);
    }
}
