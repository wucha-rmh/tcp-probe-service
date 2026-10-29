package dev.tcpprobe;

import dev.tcpprobe.probe.TcpProber;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * 应用入口：Spring Boot 负责 HTTP 服务、JSON 与配置绑定，探测核心在 {@link TcpProber}。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProbeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProbeApplication.class, args);
    }

    /**
     * 探测器的 DNS 线程池大小随在途探测数走：每个探测同一时刻最多占用一个解析线程，
     * 池子因此就是解析侧的上限，不会因为域名解析卡住而无限起线程。
     *
     * <p>实现 {@link AutoCloseable}，容器关闭时会自动调用 {@code close()} 停掉线程池。</p>
     */
    @Bean
    public TcpProber tcpProber(ProbeProperties properties) {
        return new TcpProber(
                properties.timeoutMs(),
                properties.dnsTimeoutMs(),
                Math.max(4, properties.maxInFlight()));
    }
}
