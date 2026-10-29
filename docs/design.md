# 服务与探测设计说明

这份文档记录「为什么这样做」，配套 README 的「怎么做」。每条决策都写了备选方案与代价，方便在面试里展开。

## 1. 背景与目标

题目要求：一个 HTTP 接口服务，接受探测任务，后台对指定目标做 TCP 连通性探测并返回结果；支持批量探测和简单限流；**不使用第三方封装好的探测组件**；重点考察工程习惯、项目完整性、需求理解、文档与逻辑缜密性。

我把它拆成了四个必须满足的硬约束：

1. **可验证**：拉下来按 README 就能启动、能测（因此零运行时依赖、单一 jar、内置配置）。
2. **不会被打死**：有限流、有并发上限、有请求体上限，异常输入全部有确定的状态码。
3. **不会悬着**：任何一次探测、任何一批探测、任何一次同步请求都有明确的时间上界。
4. **说得清**：每个设计都有理由、有代价、有测试覆盖。

### 非目标

- 不做 ICMP/HTTP/HTTPs/数据库探测（题目是「简单 TCP 连通性」）。
- 不做认证、TLS、持久化、多实例协调——都会显著扩大范围，且不是题目要考的点，已在 README「已知取舍」中列出。
- 不追求极致吞吐，追求「行为可预测 + 代码可审计」。

## 2. 关键决策

### ADR-1 零第三方运行时依赖

**决定**：HTTP 用 JDK 自带 `com.sun.net.httpserver.HttpServer`，TCP 探测用 `java.nio.channels.SocketChannel`，JSON 自己写，日志自己写。

**备选**：Spring Boot + OkHttp/Netty + Jackson。

**理由**：
- 题目明确排除「第三方封装好的探测组件」；把框架也去掉，整个行为从 `main` 到 socket 系统调用都在本仓库里，逻辑最缜密的部分（超时、并发、错误分类）不会被框架掩盖。
- JDK 的 `HttpServer` 对一个内部工具型服务完全够用：虚拟线程执行器、`HttpExchange` 的流式读写、`sendResponseHeaders` 都能直接用。
- 交付物是一个 80KB 的 jar，`java -jar` 起来，没有任何依赖下载失败的坑。

**代价**：
- 需要自己处理路由、错误信封、请求体大小限制、响应头——这些正是 `http/` 包 500 行的来源。
- `com.sun.net.httpserver` 是 JDK 的 `jdk.httpserver` 模块（`jdk.httpserver` 在 `java.se` 里，不是 `com.sun.*` 的内部 API 问题；它虽然位于 `com.sun.net.httpserver` 包下，但属于受支持的 JDK API）。
- 没有生产级的 HTTP/1.1 keep-alive 调优、限流器、连接队列——`HttpServer` 背后是 `sun.net.httpserver.ServerImpl`，单 acceptor 线程 + 工作线程池，对本服务的连接数（内部工具，几十 QPS）足够。

**如果重来**：如果题目允许第三方 Web 框架，仍然会保留自写 JSON 与探测层，只把 HTTP 层换成框架——因为框架能省的是模板代码，省不掉这个作业真正要考的决策。

### ADR-2 Java 21 + 虚拟线程处理 HTTP

**决定**：`maven.compiler.release=21`；HTTP 请求处理用 `Executors.newVirtualThreadPerTaskExecutor()`；任务调度和探测用固定大小的平台线程池。

**理由**：
- HTTP handler 是 IO 阻塞型（等任务完成、读请求体），用虚拟线程可以让「同步等待」不占平台线程。`sync` 模式因此可以简单地阻塞等待，而不需要 callback/Promise 链。
- 探测连接本身要精确控制并发数（`probe.max-in-flight`），用固定平台线程池才能形成背压；虚拟线程不设上限，反而会让出站连接数失控。
- `release=21` 而不是 `25`：作业要的是「拉下来能跑」，21 是当前 LTS，覆盖面最大。本机 JDK 25 编译 21 target 已验证。

**代价**：`-Xlint:all` 下需要保证没有预览特性；`HttpServer` 与虚拟线程的组合在 JDK 21 上是被测试过的路径，但吞吐没有压测数据（见 README 已知取舍）。

### ADR-3 统一异步执行路径，`sync` 只是「在同一请求里等」

**决定**：所有请求都走 `ProbeService.submit(...)` → 排队 → 由 `task.workers` 线程执行；`mode=sync` 仅表示 handler 在返回前 `task.completion().get(syncMaxWaitMs)`。

**备选**：`sync` 直接在当前线程执行探测，`async` 走线程池（两条路径）。

**理由**：
- 两条路径意味着两套并发控制、两套截止时间、两套错误处理，最容易出现的行为差异是「sync 不尊重 task.max-concurrent」「async 的结果顺序偶尔不同」。
- 单一路径下 `sync` 天然继承限流、并发上限、批截止时间、结果排序，测试也只需要覆盖一套语义。
- 代价是 `sync` 多了一次线程切换（微秒级），对本服务的量级无所谓。

### ADR-4 探测状态语义：`REFUSED` 也是「主机可达」

**决定**：状态集合 `CONNECTED / REFUSED / TIMEOUT / UNRESOLVED / UNREACHABLE / ERROR`；`REFUSED`（收到 RST）判定为主机在线，且**不再尝试该主机的其他 A/AAAA 记录**。

**理由**：
- 探测的目的是「连通性」，`ECONNREFUSED` 明确说明网络路径通、目标主机在线，只是端口没开——这是一个**结论**，不是失败。
- 对多地址主机逐个试是必要的（IPv6 优先但不可达、负载均衡记录部分失效），但一旦收到 RST 就说明路径没问题，继续试只是浪费预算。
- `UNRESOLVED` 与 `TIMEOUT` 分开：前者是配置错误（域名写错），后者是网络问题，运维处置完全不同。

**代价**：`REFUSED` 不区分「防火墙 REJECT」与「没有服务监听」——两者在 TCP 层不可区分，文档里写清楚即可。

### ADR-5 DNS 单独限时（本项目最重要的一个教训）

**决定**：新增 `DnsResolver`：固定大小线程池（`maxConcurrentLookups`，默认 `max(4, probe.max-in-flight)`）+ `Future.get(probe.dns-timeout-ms)`；超时抛 `DnsTimeoutException`，由 `TcpProber` 映射为 `TIMEOUT`。

**问题怎么发现的**：冒烟时 `no-such-host.invalid:80` 返回 `TIMEOUT` 且 detail 是 `batch deadline exceeded before the connection attempt started`，整批耗时 4.5 秒。真实原因不是连接超时，而是 `InetAddress.getAllByName` 在某些网络下对 NXDOMAIN/不存在的域名会挂数秒（取决于 resolver 行为），吃掉了整批预算，后面的目标连尝试的机会都没有。

**备选**：
- 用 `--add-opens` 改 `InetAddress` 缓存/超时——依赖 JDK 内部实现，脆弱。
- 把 DNS 放到调用线程、给整个 task 一个截止时间——仍然无法区分「DNS 慢」与「连接慢」，错误分类不准确。
- 用异步 resolver 库——引入第三方依赖，违背 ADR-1。

**理由**：DNS 是探测瀑布的第一级，必须独立受控。独立线程池同时提供了 DNS 的背压（避免几百个目标同时打 resolver）。

**代价**：
- `Future.cancel(true)` 对阻塞在系统调用里的解析线程通常无效（JDK 解析器不响应中断），超时只是释放了**队列槽位**，线程要等 OS resolver 自己返回。所以池大小必须足够（默认 ≥4 且 ≥探测并发），否则会出现「槽位释放了但线程仍被占用」的短暂膨胀。
- 每个任务多一层线程切换。对一个内部工具可忽略。

**验证**：加入这一层后，同一套测试从 11.8 秒（等真实 DNS 超时）降到 0.8 秒；`DnsResolverTest` 用注入的 stall resolver 断言 100ms 预算内必定返回。这条对比写进了 README，因为它最能说明「为什么不能直接调 `getAllByName`」。

### ADR-6 批级截止时间

**决定**：

```
perTarget  = dnsTimeout + connectTimeout + 500ms
perWave    = ceil(targetCount / maxProbesInFlight) * perTarget
deadline   = min(perWave + 2000ms, syncMaxWaitMs + 5000ms)
```

`ProbeService.execute` 用 `ExecutorService.invokeAll(calls, deadline, NANOSECONDS)`；被取消（还没开始）的探测记为 `TIMEOUT`，detail 说明是批截止。

**理由**：
- 没有批截止，一个 200 目标、每个都超时的请求会拖 `200/64 × 2s ≈ 6.3s`，而客户端（`sync`）最长只等 30s——极端情况下 `sync` 请求会先超时，返回 202 但结果永远没人看。
- `perWave` 的推导：探测线程池大小为 `maxInFlight`，n 个目标需要 `ceil(n/maxInFlight)` 波，每波最坏 `dns+connect+500ms`。多留 2s 给线程调度和结果汇总。
- 截断到 `syncMaxWaitMs + 5s`：保证 `sync` 的等待上限永远大于批截止，`sync` 一定能在自己的等待窗口内拿到结论（成功或批超时）。

**代价**：目标数远超 `maxInFlight` 时，最后几波可能被提前取消（记为 TIMEOUT 而不是真的连接超时）。detail 里明确写 `batch deadline exceeded`，可区分。

### ADR-7 两级令牌桶限流

**决定**：全局桶 + 按客户端 IP 的桶；先 `tryAcquire` 全局，成功后再扣客户端；任一拒绝返回 `429`，`Retry-After` = 还差令牌数 / 生成速率，向上取整。

**备选**：滑动窗口计数、固定窗口、只按 IP 不分全局。

**理由**：
- 令牌桶允许突发（`burst`），符合「探测任务通常是突发提交」的实际模式；滑动窗口实现更复杂且没有明显收益。
- 全局桶保护服务整体（防止 1000 个 IP 各打 10 rps 打爆探测池）；客户端桶防止单个调用方吃掉全部配额。
- **全局拒绝时不扣客户端桶**：否则一次全局拥塞会让所有客户端各自损失一个令牌，恢复后体验更差。
- `Retry-After` 用「缺口/速率」向上取整，客户端按这个值退避刚好能成功，不写死 1 秒。

**代价**：桶是进程内的，多实例部署时每个实例各算各的（README 已列为已知取舍）；客户端标识默认按 socket 源 IP，反代后面需要开 `http.trust-proxy-header`。

### ADR-8 两道背压：信号量守入口，线程池守出口

**决定**：
- `task.max-concurrent` 信号量：同时执行的任务数上限，拿不到 → `503 SERVER_BUSY`。
- `probe.max-in-flight` 固定线程池：同时进行的 TCP 连接上限；`task.workers` 固定线程池：同时执行的任务数上限。

**理由**：
- 只有线程池没有入口限制，突发提交会让任务对象无限堆积（内存风险）且 503 前先 OOM。
- 只有入口限制没有线程池限制，任务之间的探测会互相挤占，单个任务的结果延迟不可预测。
- `429` 与 `503` 语义分开：`429` 是「请求频率超了」，`503` 是「容量满了」，客户端处置方式不同（退避 vs 稍后重试/扩容）。

**代价**：`503` 没有排队机制（直接拒绝）。对内部工具是合理的——排队会让调用方不确定要等多久；需要排队时应由调用方自己重试。

### ADR-9 错误信封与状态码约定

**决定**：所有错误返回 `{"status":N,"error":{"code":"…","message":"…","details":[…]}}`；`code` 是稳定的机器可读字符串；`details` 用于逐条列出目标错误（最多 10 条）。

**理由**：
- 客户端需要区分「我发错了」（400 系列）与「服务的问题」（500 系列），`code` 比 message 稳定，适合写断言与告警。
- 目标校验错误一次性列出多个（`targets[0] …`、`targets[2] …`），避免调用方「改一个、报一个」。
- 未预期异常统一 `INTERNAL_ERROR` 且带 `X-Request-Id` 回日志，不把堆栈泄漏给调用方。

**代价**：`com.sun.net.httpserver` 下要自己保证「只写一次响应」（`writeError` 检查 `getResponseCode() != -1`），否则会出现 `headers already sent`。

### ADR-10 可观测性：三层计数器 + 单行结构化日志

**决定**：`Metrics` 覆盖 HTTP（请求/4xx/429/5xx）、任务（提交/开始/成功/失败/过期/运行中）、探测（尝试/各状态/在飞）；`/api/v1/stats` 输出快照；每个响应带 `X-Request-Id`，日志单行 `ISO-8601Z LEVEL msg`，5xx 用 WARN、4xx 用 INFO、2xx/429 用 DEBUG。

**理由**：
- 有了这三层，任何一次「为什么慢/为什么失败」都能定位到是限流、容量、还是目标本身。
- 5xx 用 WARN 是为了让日志级别本身就有告警意义；4xx 用 INFO 是因为客户端错误常见，不值得 WARN。
- 不引入 Micrometer/Prometheus 客户端，符合 ADR-1；`/api/v1/stats` 的 JSON 可以被任何采集器抓。

**代价**：没有直方图/分位数（`averageLatencyMs` 只是平均值，会在 summary 里被少数极值带偏）；需要分位数时应改用专门的指标库。

### ADR-11 测试策略：确定性优先

**决定**：75 个测试，分四层：
1. 纯单元：`Json`、`ProbeTarget`、`RateLimiter`（注入 `AtomicLong` 时钟）、`TaskRegistry`、`ConfigLoader`。
2. 可注入依赖：`TcpProber`（注入 `DnsResolver`）、`DnsResolver`（注入 `Lookup`，用 `LockSupport.parkNanos` 模拟挂起）。
3. 本地 socket：`TcpProberTest`、`ProbeServiceTest` 自己开 `ServerSocket` 当「活端口」，用关闭的端口当「拒绝端口」。
4. 端到端：`ApiIntegrationTest` 用真实 `HttpServer` + `java.net.http.HttpClient`，覆盖 200/202/400/404/405/413/415/429/503 与长轮询。

**理由**：
- 网络相关代码最容易写出「本机能过、CI 挂」的测试。真实外网目标（如 `example.com`）一律不用；本地 socket 与注入的 DNS 让每个用例都确定。
- 限流与 TTL 依赖时间，必须注入时钟，不能 `Thread.sleep` 等真实时间。
- 端到端测试是唯一能证明「路由、JSON、状态码三者一致」的手段；纯单元测试再多也测不到 `HttpExchange` 的实际行为（比如 405 的 `Allow` 头、413 的提前响应）。

**教训**：Windows 上 `Instant.now()` 分辨率较粗（同一毫秒内两次调用可能相等），`TaskRegistryTest` 的 TTL 用例最初因此失败；改为先 `sleep(20ms)` 再用 `Duration.ZERO` 判定。同理，`evictOldestFinished` 在 `createdAt` 并列时需要 `thenComparing(id)` 才能稳定。

## 3. 一次请求的完整路径

```
POST /api/v1/probes
  → Router 匹配 handler
  → Content-Type 校验（415）
  → RateLimiter.acquire(clientIp)（429 + Retry-After）
  → Request.bodyJson()（400 MALFORMED_JSON / INVALID_BODY）
  → 解析 mode / timeoutMs / targets（400 INVALID_MODE / INVALID_TIMEOUT / INVALID_TARGETS）
  → ProbeService.submit
       → task.max-concurrent 信号量（503 SERVER_BUSY）
       → TaskRegistry.put(task)（id = p_<16 hex>）
       → task.workers 线程池执行 ProbeService.execute
             → probe.max-in-flight 线程池上 submit 每个目标
             → invokeAll(deadline) 等待全部完成或批超时
             → 按输入顺序汇总 ProbeResult，task.complete(...)
       → （sync）handler 等 completion，最多 sync.max-wait-ms
  → 200/202 + Views.task(task) + Location + X-RateLimit-Remaining
```

清理：`App` 里的 janitor 每 30 秒调 `ProbeService.evictExpired()`（按 `task.ttl-seconds`）和 `RateLimiter.sweepIdle(600)`（清掉 10 分钟没活动的 IP 桶）。停机：`Runtime.addShutdownHook` 先停 janitor，再 `HttpServer.stop(2)`，再关探测/任务线程池，最后放开 `main` 的 `await`。

## 4. 如果继续做，我会按这个顺序

1. **持久化**：把 `TaskRegistry` 抽成接口，加一个基于文件的实现；任务 id 与状态机不变。
2. **集中限流**：`RateLimiter` 抽成接口，加 Redis 令牌桶实现（用 Lua 保证原子性）。
3. **分位数指标**：在 `Metrics` 里加 `LongAdder` 分桶直方图，`/api/v1/stats` 输出 p50/p95/p99。
4. **HTTP/ICMP 探测**：`ProbeStatus` 不变，新增 `Prober` 接口与实现，按 `target.scheme` 选择。
5. **压测基线**：`wrk` 打 `sync` 与 `async` 两条路径，把吞吐/延迟数字写进 README。
