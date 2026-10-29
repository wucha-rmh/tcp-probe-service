package dev.tcpprobe.http;

import java.util.Map;

/** A single routed endpoint. Path parameters are pre-extracted by the {@link Router}. */
@FunctionalInterface
public interface Handler {

    void handle(Request request, Response response, Map<String, String> pathParams) throws Exception;
}
