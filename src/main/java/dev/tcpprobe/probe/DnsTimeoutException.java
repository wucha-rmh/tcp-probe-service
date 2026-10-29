package dev.tcpprobe.probe;

/** 当域名解析超出预算时抛出；由探测器映射为 {@code TIMEOUT}。 */
public class DnsTimeoutException extends Exception {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public DnsTimeoutException(String message) {
        super(message);
    }
}
