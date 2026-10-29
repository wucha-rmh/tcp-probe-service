package dev.tcpprobe.probe;

import java.util.regex.Pattern;

/**
 * 一个待探测的 TCP 端点：{@code host:port}。
 *
 * <p>只接受最朴素的 {@code host:port}。如果在这里接受 URL，就得回答“是 http 还是 https”，
 * 所以 API 只保留最小的接口面，端口由调用方显式给出。</p>
 *
 * @param host 主机名或 IP 字面量（IPv6 不带方括号存储）
 * @param port 1..65535
 */
public record ProbeTarget(String host, int port) {

    /** 允许出现的字符：主机名、IPv4、IPv6 字面量与百分号作用域。 */
    private static final Pattern HOST_CHARS = Pattern.compile("^[A-Za-z0-9._:%-]+$");
    private static final int MAX_HOST_LENGTH = 253;

    public ProbeTarget {
        host = host == null ? "" : host.trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("host must not be empty");
        }
        if (!HOST_CHARS.matcher(host).matches()) {
            throw new IllegalArgumentException("host contains characters that are not allowed: " + host);
        }
        if (host.length() > MAX_HOST_LENGTH) {
            throw new IllegalArgumentException("host is longer than " + MAX_HOST_LENGTH + " characters");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535: " + port);
        }
    }

    /**
     * 解析 {@code host:port}、{@code [ipv6]:port}、{@code ipv4:port}。
     *
     * @throws IllegalArgumentException 形式不对时抛出，消息可直接回给调用方
     */
    public static ProbeTarget parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        String value = text.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("target must not be empty");
        }
        if (value.contains("/") || value.contains("://")) {
            throw new IllegalArgumentException("target must be host:port, not a URL: " + value);
        }

        String host;
        String portText;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0) {
                throw new IllegalArgumentException("unterminated IPv6 literal: " + value);
            }
            host = value.substring(1, end);
            String rest = value.substring(end + 1);
            if (!rest.startsWith(":")) {
                throw new IllegalArgumentException("missing port in target: " + value);
            }
            portText = rest.substring(1);
        } else {
            int colon = value.lastIndexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("target must be host:port: " + value);
            }
            host = value.substring(0, colon);
            portText = value.substring(colon + 1);
            if (host.indexOf(':') >= 0) {
                throw new IllegalArgumentException(
                        "IPv6 literals must be bracketed, e.g. [::1]:80: " + value);
            }
        }

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("port is not a number: " + portText);
        }
        return new ProbeTarget(host, port);
    }

    /** 规范形式，用作映射键并出现在 API 响应里，例如 {@code example.com:443}、{@code [::1]:80}。 */
    public String display() {
        return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
    }

    /** IP 字面量不会走 DNS，因此不该报 {@code dnsMs}。 */
    public boolean isIpLiteral() {
        return host.indexOf(':') >= 0 || host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$");
    }
}
