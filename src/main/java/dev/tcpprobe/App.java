package dev.tcpprobe;

import dev.tcpprobe.config.Config;
import dev.tcpprobe.config.ConfigLoader;
import dev.tcpprobe.config.ConfigurationException;
import dev.tcpprobe.http.ProbeHttpServer;
import dev.tcpprobe.probe.DnsResolver;
import dev.tcpprobe.probe.TcpProber;
import dev.tcpprobe.service.Metrics;
import dev.tcpprobe.service.ProbeService;
import dev.tcpprobe.service.RateLimiter;
import dev.tcpprobe.service.TaskRegistry;
import dev.tcpprobe.util.Log;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Entry point: wires configuration, the probe service and the HTTP server together. */
public final class App {

    /** How often expired tasks and idle rate-limit buckets are swept. */
    private static final long JANITOR_INTERVAL_SECONDS = 30;
    /** Idle time after which a client's rate-limit bucket is forgotten. */
    private static final long RATE_LIMIT_IDLE_SECONDS = 600;

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) {
        Config config;
        try {
            config = ConfigLoader.load(args);
        } catch (ConfigurationException e) {
            System.err.println("configuration error: " + e.getMessage());
            return 2;
        }

        Metrics metrics = new Metrics();
        // One bounded resolver pool shared by every probe; the prober (and thus ProbeService) owns it.
        TcpProber prober = new TcpProber(config.probeTimeoutMs(),
                new DnsResolver(Math.max(4, config.maxProbesInFlight())), config.probeDnsTimeoutMs());
        // Generous cap: finished tasks are evicted by TTL anyway, this only bounds pathological bursts.
        TaskRegistry registry = new TaskRegistry(Math.max(1024, config.maxConcurrentTasks() * 32));
        ProbeService service = new ProbeService(config, prober, registry, metrics);
        RateLimiter rateLimiter = new RateLimiter(
                config.globalRateRps(), config.globalRateBurst(),
                config.clientRateRps(), config.clientRateBurst());
        ProbeHttpServer server = new ProbeHttpServer(config, service, rateLimiter, metrics);

        try {
            server.start();
        } catch (IOException e) {
            Log.error("cannot start HTTP server on " + config.bindAddress() + ":" + config.port()
                    + " — " + e.getMessage());
            service.shutdown(Duration.ofSeconds(1));
            return 1;
        }

        Log.info("tcp-probe-service %s listening on http://%s:%d",
                config.version(), config.bindAddress(), server.port());
        Log.info("configuration: %s", ConfigLoader.summary(config));

        ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "janitor");
            thread.setDaemon(true);
            return thread;
        });
        janitor.scheduleAtFixedRate(() -> {
            try {
                int evicted = service.evictExpired();
                rateLimiter.sweepIdle(RATE_LIMIT_IDLE_SECONDS);
                if (evicted > 0) {
                    Log.debug("janitor evicted %d expired task(s)", evicted);
                }
            } catch (RuntimeException e) {
                Log.warn("janitor iteration failed: %s", e.getMessage());
            }
        }, JANITOR_INTERVAL_SECONDS, JANITOR_INTERVAL_SECONDS, TimeUnit.SECONDS);

        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean shutdownRequested = new AtomicBoolean();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!shutdownRequested.compareAndSet(false, true)) {
                return;
            }
            Log.info("shutting down");
            janitor.shutdownNow();
            server.stop(2);
            service.shutdown(Duration.ofSeconds(2));
            stopped.countDown();
        }, "shutdown"));

        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }
}
