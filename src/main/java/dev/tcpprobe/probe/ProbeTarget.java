package dev.tcpprobe.probe;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A single TCP endpoint to probe: {@code host:port}.
 *
 * <p>Only the bare {@code host:port} form is accepted. Accepting a URL here would invite
 * ambiguity ("is it http or https?"), so the API keeps the smallest possible surface and the
 * caller decides which port carries the service.</p>
 */
public record ProbeTarget(String host, int port) {

    /** Hostnames: letters, digits, dot, dash, underscore; IP literals are validated by the resolver. */
    private static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9._:%-]+$");
    private static final int MAX_HOST_LENGTH = 253;

    public ProbeTarget {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        host = host.trim();
        if (host.length() > MAX_HOST_LENGTH) {
            throw new IllegalArgumentException("host must be at most " + MAX_HOST_LENGTH + " characters");
        }
        if (!HOST_PATTERN.matcher(host).matches()) {
            throw new IllegalArgumentException("host contains unsupported characters: \"" + host + "\"");
        }
        if (host.startsWith(".") || host.endsWith(".") || host.contains("..")) {
            throw new IllegalArgumentException("malformed host: \"" + host + "\"");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be within [1, 65535] but was " + port);
        }
    }

    /** Canonical form used as map key and in the API payload, e.g. {@code example.com:443} or {@code [::1]:80}. */
    public String display() {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    @Override
    public String toString() {
        return display();
    }

    /** Parses {@code host:port}, {@code [ipv6]:port} and {@code ipv4:port}. */
    public static ProbeTarget parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        if (s.contains("/") || s.contains(" ") || s.contains("://")) {
            throw new IllegalArgumentException("target must be host:port without scheme or path: \"" + s + "\"");
        }

        String hostPart;
        String portPart;
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("missing ']' in IPv6 target: \"" + s + "\"");
            }
            hostPart = s.substring(1, close);
            String rest = s.substring(close + 1);
            if (!rest.startsWith(":")) {
                throw new IllegalArgumentException("port is required, e.g. \"[::1]:80\"");
            }
            portPart = rest.substring(1);
        } else {
            int lastColon = s.lastIndexOf(':');
            if (lastColon < 0) {
                throw new IllegalArgumentException("port is required, e.g. \"example.com:443\"");
            }
            if (s.indexOf(':') != lastColon) {
                throw new IllegalArgumentException("IPv6 literals must be bracketed, e.g. \"[::1]:80\"");
            }
            hostPart = s.substring(0, lastColon);
            portPart = s.substring(lastColon + 1);
        }

        if (hostPart.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        int port;
        try {
            port = Integer.parseInt(portPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("port must be numeric but was \"" + portPart + "\"");
        }
        return new ProbeTarget(hostPart, port);
    }

    static String normalize(String host) {
        return host.toLowerCase(Locale.ROOT);
    }
}
