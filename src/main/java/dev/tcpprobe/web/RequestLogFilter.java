package dev.tcpprobe.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 一个请求留一行日志：{@code POST /api/v1/probes -> 200 (2103 ms)}。
 *
 * <p>为什么不用 Spring 自带的访问日志？它按容器格式化，和应用的日志时间/线程对不上；这里一行就够定位问题：
 * 谁在什么时候打了什么、结果如何、慢在哪。5xx 用 WARN，其余用 INFO——默认 50 rps 的限额下这点日志量不值得省，
 * 真嫌吵可以把 {@code logging.level.dev.tcpprobe.http} 调到 WARN。</p>
 */
@Component
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("dev.tcpprobe.http");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - start) / 1_000_000L;
            String line = request.getMethod() + " " + request.getRequestURI() + " -> " + response.getStatus()
                    + " (" + millis + " ms)";
            int status = response.getStatus();
            if (status >= 500) {
                log.warn(line);
            } else {
                log.info(line);
            }
        }
    }
}
