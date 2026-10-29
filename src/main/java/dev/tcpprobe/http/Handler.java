package dev.tcpprobe.http;

import java.util.Map;

/** 一个被路由的端点。路径参数已由 {@link Router} 预先提取。 */
@FunctionalInterface
public interface Handler {

    void handle(Request request, Response response, Map<String, String> pathParams) throws Exception;
}
