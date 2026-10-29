# 演示脚本

一场 8 分钟的演示，目标是让面试官看到三件事：**接口能用**、**探测是真的自己写的**、**边界情况都想过**。
按顺序走，每一步都有「跑什么」和「说什么」。

## 0. 开场（30 秒）

先不跑代码，先说清这是什么：

> 一个 TCP 连通性探测服务：调用方一次提交一批目标，服务在后台并行做 TCP 握手，返回每个目标的状态和耗时。
> 题目禁止用现成的探测组件，所以基础设施（HTTP、JSON、配置、错误映射）交给 Spring Boot，**探测本身全部手写**，
> 主代码 13 个文件约 1200 行，核心在 `probe/TcpProber.java`。

同时打开 `README.md` 让人扫一眼目录：快速开始 / 验证 / 探测是怎么做的 / API / 配置 / 测试。

## 1. 起服务（1 分钟）

```bash
mvn -q clean package -DskipTests
java -jar target/tcp-probe-service.jar
```

指着启动日志的两行说：

- `probe service ready: [timeoutMs=2000, dnsTimeoutMs=2000, maxInFlight=64, …]` —— 启动时把生效配置打出来，
  排查「为什么线上是 2 秒」这类问题时不用猜。
- 空一行 `curl localhost:8080/healthz`：健康检查只查内存，不触发探测（避免探活把探测线程池打满）。

## 2. 核心演示：一次批量拿到四种状态（3 分钟）

这是整场演示的重点，一定要讲透。

```bash
cat > /tmp/batch.json <<'JSON'
{"mode":"sync","targets":["example.com:443","127.0.0.1:1","192.0.2.1:9","no-such-host.invalid:80"]}
JSON
time curl -s -H 'Content-Type: application/json' --data-binary @/tmp/batch.json localhost:8080/api/v1/probes
```

结果里四个 `status` 分别是：

| 目标 | 状态 | 说明什么 |
| --- | --- | --- |
| `example.com:443` | `CONNECTED` | 通；`latencyMs` 216、`dnsMs` 112，两个耗时分开报 |
| `127.0.0.1:1` | `REFUSED` | 主机在、端口没开；`detail` 是 `Connection refused` |
| `192.0.2.1:9` | `TIMEOUT` | TCP 握手在预算内没完成（`connect timed out after 1999 ms`） |
| `no-such-host.invalid:80` | `TIMEOUT` | **DNS 超时**，`dns resolution timed out after 2000 ms` |

逐条讲三个设计点：

1. **状态要分得清。** `REFUSED` 能证明主机可达、只是端口未监听，所以它一旦出现就停止重试其它 A 记录；
   `TIMEOUT` 和 `UNREACHABLE` 是两种不同的「没连上」，运维看这两个词的处置方式完全不同。
2. **DNS 必须单独限时。** `InetAddress.getAllByName` 自己不会超时。如果直接调，一个解析不出来的域名会把整批任务
   拖住——我在调试时就踩过：DNS 卡住吃光批预算，后面本来能连通的目标被整批取消，报成 TIMEOUT，
   真实原因被掩盖。现在解析走固定大小的 DNS 池 + `Future.get(dnsTimeoutMs)`，超时就是超时，不污染其它目标。
   这个改动让同一套测试从 11.8 秒降到 0.8 秒。
3. **整批共享一个截止时间。** `time` 显示 2 秒左右返回，而不是「4 个目标各自 2 秒 = 8 秒」：
   探测线程池是 `maxInFlight=64`，批级 `invokeAll(..., batchDeadline)`，被取消的目标不会丢，
   而是产出一条 TIMEOUT 结果，**返回顺序始终与提交顺序一致**。

## 3. 异步与长轮询（1 分钟）

```bash
ID=$(curl -s -H 'Content-Type: application/json' -d '{"targets":["example.com:443"]}' \
     localhost:8080/api/v1/probes | python3 -c 'import sys,json;print(json.load(sys.stdin)["taskId"])')
curl -s "localhost:8080/api/v1/probes/$ID?waitMs=5000"
```

说三句话：

- 提交返回 `202` + `PENDING`，**未完成时不返回 `results` 字段**，不会让人把半成品当最终结果。
- `sync` 不是另一条代码路径：它同样走任务模型，只是调用方在同一个 HTTP 请求上等 `completion`，
  所以两种模式的语义、状态、错误处理完全一致。
- `?waitMs` 是长轮询，结果一出来立刻返回，不用客户端 sleep 轮询。

## 4. 限流与背压（1 分钟）

```bash
# 用一个进程连打，否则 curl 每次新进程间隔太久，追得上令牌回填
for i in $(seq 1 30); do curl -s -o /dev/null -w '%{http_code} ' \
  -H 'Content-Type: application/json' -d '{"targets":["127.0.0.1:1"]}' localhost:8080/api/v1/probes; done; echo
```

预期 `202` 若干次后出现 `429`，响应头 `Retry-After: 1`、`X-RateLimit-Remaining: 0`，body 里 `code: RATE_LIMITED`。

讲清三种「不服务」的区别——这是面试官最容易追问的点：

| 情况 | 状态码 | 含义 |
| --- | --- | --- |
| 被限流 | `429 RATE_LIMITED` | 你打太快了，等一下再来 |
| 并发任务占满 | `503 SERVER_BUSY` | 服务整体忙，换谁都得等 |
| 目标太多 | `400 TOO_MANY_TARGETS` | 请求本身不合法 |

另外说明两级令牌桶：全局桶保护整个服务，客户端桶按 IP 分桶；**先扣全局桶，失败就不动客户端桶**，
免得一个被限流的客户端把别人的额度也吃掉。空闲的客户端桶会被周期性清理，不会因为源地址不断变化导致 map 无限增长。

## 5. 错误与边界（1 分钟）

五条命令，看的是「错误信息够不够用来定位问题」：

```bash
curl -si -H 'Content-Type: application/json' -d '{"targets":[' localhost:8080/api/v1/probes        # 400 MALFORMED_JSON
curl -si -H 'Content-Type: application/json' -d '{"targets":["nope:0"]}' localhost:8080/api/v1/probes # 400 INVALID_TARGETS
curl -si -H 'Content-Type: text/plain' -d x localhost:8080/api/v1/probes                              # 415
curl -si localhost:8080/api/v1/probes/p_deadbeef                                                      # 404
curl -si -X DELETE localhost:8080/api/v1/probes                                                       # 405 + Allow
```

重点指这一条：`{"details":["targets[0]: port must be between 1 and 65535: 0"]}` ——
**每一条非法目标都单独报错并带上下标**，200 个目标里错 3 个时，调用方知道改哪三个，而不是只收到一句「参数错误」。

请求体过大还有 `413 PAYLOAD_TOO_LARGE`（`probe.max-body-bytes`，默认 1 MB）。

## 6. 观测（30 秒）

```bash
curl -s localhost:8080/api/v1/stats
```

三层视角：HTTP 层（一行日志一条请求）、任务层（提交/成功/失败/在途/保留）、探测层（按六种状态分别计数 + 在途）
外加 `rateLimitedClients`（当前被限流的客户端 IP 数）。`/api/v1/stats` 返回的是任务层与探测层：

```json
{"tasks":{"submitted":2,"running":0,"succeeded":2,"failed":0,"stored":2},
 "probes":{"connected":3,"refused":1,"timeout":2,"unresolved":0,"unreachable":0,"error":0,"attempted":6,"inFlight":0},
 "rateLimitedClients":1}
```

配合日志：一个请求一行，`POST /api/v1/probes -> 200 (488 ms)`，5xx 用 WARN、其余 INFO；
嫌吵就把 `--logging.level.dev.tcpprobe.http=WARN` 调掉。

## 7. 代码走读（3 分钟）

只走一条路，不要念文件树：

1. `web/ProbeController.java` —— 校验与视图组装，`submit` 的前 30 行：限流 → 请求体大小 → 解析 targets → 模式/超时。
2. `service/ProbeService.java` —— 两层调度：任务线程池（`workers`）+ 探测线程池（`maxInFlight`，队列即背压），
   `invokeAll(..., batchDeadline)` 保证批级截止。
3. `probe/TcpProber.java` —— 三步：解析（DNS 池 + 超时）→ 连接（逐地址共享剩余预算，最多 4 个地址）→ 分类
   （`SocketTimeoutException` / `PortUnreachable` / `SocketException` 消息归类）。
4. `src/test/java/.../web/ProbeApiTest.java` —— 13 个端到端用例，真起 Spring 容器，真开 `ServerSocket`
   当「通的端口」、真关一个当「拒绝的端口」。指出 `TcpProberTest.reportsTimeoutWhenDnsStallsInsteadOfWaitingForEver`：
   注入一个会 `parkNanos(3s)` 的假解析器 + 100ms 预算，断言快速返回 TIMEOUT —— 这东西测不了就说明代码不可测。

## 8. 收尾（30 秒）

主动说三条取舍，比等面试官问出来更好：

- 任务只在内存里，进程重启即丢 —— 这是「探测一次、拿结果」的工具，长期留档应该在调用方存储；
- 只做 TCP 握手，不做 ICMP 和 HTTP 层探测 —— 题目要的是连通性，ICMP 在容器/云环境常被禁；
- DNS 超时只能"放弃等待"、不能真正杀掉解析线程 —— JDK 解析器不响应中断，超时只是释放队列槽位，
  线程由 OS 兜底；如果要更彻底，得换异步 DNS 库（这属于题目禁止的"现成探测组件"之外，但会引入依赖）。

## 演示前检查清单

- [ ] 端口 8080 空闲（`lsof -i :8080` / `netstat -ano | findstr 8080`）
- [ ] 有外网就带上 `example.com:443`；没有外网全用 `127.0.0.1:<内部端口>` 和 `192.0.2.1:9`（TEST-NET，必然超时）
- [ ] 限流演示用**单进程脚本**（`ab`、`hey` 或本文的循环），curl 循环容易因为进程启动间隔而不触发 429
- [ ] Windows PowerShell 传 JSON：写文件 + `--data-binary "@file"`，因为 PowerShell 5.1 会把内嵌双引号吃掉
- [ ] 演示前 `curl /api/v1/stats` 看一眼，确认没有历史任务干扰计数讲解
