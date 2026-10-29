package dev.tcpprobe.web;

import java.util.List;

/**
 * {@code POST /api/v1/probes} 的请求体。
 *
 * @param targets   目标列表，元素是 {@code "host:port"} 字符串，或 {@code {"host":..., "port":...}} 对象
 * @param timeoutMs 单次握手的预算，缺省用服务端配置
 * @param mode      {@code sync} 或 {@code async}，也可用查询参数 {@code ?mode=} 给出
 */
public record ProbeRequest(List<Object> targets, Integer timeoutMs, String mode) {
}
