package dev.tcpprobe.probe;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.util.Locale;

/**
 * Hand-written TCP connectivity probe.
 *
 * <p>No third-party probing library is used: the implementation opens a plain
 * {@link SocketChannel}, performs the TCP handshake with a bounded timeout and classifies the
 * outcome. The connection is closed immediately afterwards — the goal is reachability, not
 * payload exchange.</p>
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li><b>DNS is measured and bounded separately.</b> {@link InetAddress#getAllByName} can block
 *       far longer than the connect timeout, and it is not subject to it. A lookup is therefore
 *       run through {@link DnsResolver} with its own budget, and reported as {@code dnsMs} so that
 *       "slow resolver" stays distinguishable from "slow network".</li>
 *   <li><b>Every resolved address is tried.</b> A host with several A/AAAA records, one of which is
 *       filtered, still reports {@code CONNECTED} when another address answers. The per-address
 *       connect timeout equals the requested timeout, so the worst case is
 *       {@code addresses × timeout}; batch-level deadlines cut that off.</li>
 *   <li><b>No retries.</b> Retrying inside the probe would blur the latency measurement; callers
 *       who need retries can re-submit the task, which keeps the API honest.</li>
 * </ul>
 */
public final class TcpProber {

    /** How many resolved addresses are attempted before giving up. */
    private static final int MAX_ADDRESS_ATTEMPTS = 4;

    /**
     * @param connectTimeoutMs default handshake timeout, used when a caller does not override it
     */
    public TcpProber(int connectTimeoutMs) {
        this(connectTimeoutMs, new DnsResolver(8), 2000);
    }

    /**
     * @param connectTimeoutMs default handshake timeout
     * @param resolver         owned by the prober; {@link #close()} shuts it down
     * @param dnsTimeoutMs     budget for one name lookup
     */
    public TcpProber(int connectTimeoutMs, DnsResolver resolver, int dnsTimeoutMs) {
        if (connectTimeoutMs < 1) {
            throw new IllegalArgumentException("connectTimeoutMs must be positive");
        }
        if (dnsTimeoutMs < 1) {
            throw new IllegalArgumentException("dnsTimeoutMs must be positive");
        }
        this.defaultConnectTimeoutMs = connectTimeoutMs;
        this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
        this.dnsTimeoutMs = dnsTimeoutMs;
    }

    private final int defaultConnectTimeoutMs;
    private final DnsResolver resolver;
    private final int dnsTimeoutMs;

    public int defaultConnectTimeoutMs() {
        return defaultConnectTimeoutMs;
    }

    public int dnsTimeoutMs() {
        return dnsTimeoutMs;
    }

    /** Releases the resolver pool; the prober must not be used afterwards. */
    public void close() {
        resolver.close();
    }

    public ProbeResult probe(ProbeTarget target) {
        return probe(target, defaultConnectTimeoutMs);
    }

    /**
     * Probes {@code target} and never throws: every failure mode is mapped onto a
     * {@link ProbeStatus} so that a batch of targets always yields a result per target.
     */
    public ProbeResult probe(ProbeTarget target, int connectTimeoutMs) {
        if (connectTimeoutMs < 1) {
            connectTimeoutMs = defaultConnectTimeoutMs;
        }

        long dnsStart = System.nanoTime();
        InetAddress[] addresses;
        boolean literal = isLiteral(target.host());
        try {
            addresses = resolver.resolve(target.host(), dnsTimeoutMs);
        } catch (UnknownHostException e) {
            return ProbeResult.of(target, ProbeStatus.UNRESOLVED, null, null,
                    "unknown host: " + target.host());
        } catch (DnsTimeoutException e) {
            return ProbeResult.of(target, ProbeStatus.TIMEOUT, null, null, e.getMessage());
        } catch (RuntimeException e) {
            return ProbeResult.of(target, ProbeStatus.ERROR, null, null, safeMessage(e));
        }
        long dnsMs = Math.max(0L, (System.nanoTime() - dnsStart) / 1_000_000L);

        ProbeStatus lastStatus = null;
        String lastDetail = null;
        for (int i = 0; i < Math.min(addresses.length, MAX_ADDRESS_ATTEMPTS); i++) {
            InetAddress address = addresses[i];
            long start = System.nanoTime();
            try (SocketChannel channel = SocketChannel.open()) {
                Socket socket = channel.socket();
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(address, target.port()), connectTimeoutMs);
                long latencyMs = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
                return ProbeResult.of(target, ProbeStatus.CONNECTED, latencyMs, literal ? null : dnsMs, null);
            } catch (SocketTimeoutException e) {
                lastStatus = ProbeStatus.TIMEOUT;
                lastDetail = "connect timed out after " + connectTimeoutMs + " ms";
            } catch (NoRouteToHostException e) {
                lastStatus = ProbeStatus.UNREACHABLE;
                lastDetail = "no route to host: " + address.getHostAddress();
            } catch (PortUnreachableException e) {
                lastStatus = ProbeStatus.UNREACHABLE;
                lastDetail = "port unreachable: " + address.getHostAddress();
            } catch (SocketException e) {
                lastStatus = classifySocketException(e);
                lastDetail = safeMessage(e);
                if (lastStatus == ProbeStatus.REFUSED) {
                    // A refused connection already proves the host is reachable; no point in
                    // burning the remaining timeout on the other addresses of the same name.
                    break;
                }
            } catch (IOException e) {
                lastStatus = ProbeStatus.ERROR;
                lastDetail = e.getClass().getSimpleName() + ": " + safeMessage(e);
            } catch (RuntimeException e) {
                lastStatus = ProbeStatus.ERROR;
                lastDetail = e.getClass().getSimpleName() + ": " + safeMessage(e);
            }
        }
        // At least one address has been attempted (getAllByName never returns an empty array).
        return ProbeResult.of(target, lastStatus == null ? ProbeStatus.ERROR : lastStatus,
                null, literal ? null : dnsMs, lastDetail);
    }

    /** Maps a socket-level failure onto the API-visible status. Visible for tests. */
    static ProbeStatus classifySocketException(SocketException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (e instanceof ConnectException
                && (msg.contains("refused") || msg.contains("拒绝") || msg.isEmpty())) {
            return ProbeStatus.REFUSED;
        }
        if (msg.contains("refused") || msg.contains("拒绝")) {
            return ProbeStatus.REFUSED;
        }
        if (msg.contains("unreachable") || msg.contains("no route")) {
            return ProbeStatus.UNREACHABLE;
        }
        if (msg.contains("timed out") || msg.contains("timeout")) {
            return ProbeStatus.TIMEOUT;
        }
        return ProbeStatus.UNREACHABLE;
    }

    private static boolean isLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$");
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
