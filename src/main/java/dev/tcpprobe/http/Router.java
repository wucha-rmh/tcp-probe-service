package dev.tcpprobe.http;

import dev.tcpprobe.error.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small pattern router: {@code /api/v1/probes/{taskId}} style templates, exact method matching and
 * correct 404 / 405 distinction. No reflection, no annotation scanning — explicit registration in
 * {@link ProbeHttpServer} keeps the full API surface readable in one place.
 */
public final class Router {

    private record Route(String method, String[] segments, Handler handler) {

        boolean matches(String[] path, Map<String, String> params) {
            if (segments.length != path.length) {
                return false;
            }
            for (int i = 0; i < segments.length; i++) {
                String expected = segments[i];
                String actual = path[i];
                if (expected.length() > 2 && expected.startsWith("{") && expected.endsWith("}")) {
                    if (actual.isEmpty()) {
                        return false;
                    }
                    params.put(expected.substring(1, expected.length() - 1), actual);
                } else if (!expected.equals(actual)) {
                    return false;
                }
            }
            return true;
        }
    }

    private final List<Route> routes = new ArrayList<>();

    public Router get(String pattern, Handler handler) {
        return add("GET", pattern, handler);
    }

    public Router post(String pattern, Handler handler) {
        return add("POST", pattern, handler);
    }

    private Router add(String method, String pattern, Handler handler) {
        routes.add(new Route(method, segments(pattern), handler));
        return this;
    }

    public void dispatch(Request request, Response response) throws Exception {
        String[] path = segments(request.path());
        boolean pathMatched = false;
        Set<String> allowed = new LinkedHashSet<>();
        for (Route route : routes) {
            Map<String, String> params = new LinkedHashMap<>();
            if (!route.matches(path, params)) {
                continue;
            }
            pathMatched = true;
            if (!route.method().equals(request.method())) {
                allowed.add(route.method());
                continue;
            }
            route.handler().handle(request, response, params);
            return;
        }
        if (pathMatched) {
            response.header("Allow", String.join(", ", allowed));
            throw ApiException.methodNotAllowed(request.method(), request.path());
        }
        throw ApiException.notFound("no route for " + request.method() + " " + request.path());
    }

    static String[] segments(String path) {
        String p = path;
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty()) {
            return new String[]{""};
        }
        return p.split("/", -1);
    }
}
