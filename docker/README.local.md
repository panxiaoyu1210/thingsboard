# ThingsBoard WAN 本地环境

本地拓扑用于从当前 `4.4.0-SNAPSHOT` 源码交付并验收 ThingsBoard 与独立
`tb-wan-transport`。它包含 ThingsBoard、WAN Transport、Kafka、Zookeeper、Valkey，
以及一个有状态的 TurMass WAN 模拟 NS。WAN Transport 只通过 Kafka Transport API
访问 ThingsBoard Core，不直接访问数据库。

## 1. 前置条件

- Docker Desktop 已启动，建议至少分配 6 GB 内存；
- 当前项目使用 JDK 25；
- 本地租户管理员账号可登录，默认是 `tenant@thingsboard.org` / `tenant`；
- 仓库根目录存在项目级启动器 `tools/mvn-jdk25`。

项目级启动器只对当前命令设置 Java，不会修改全局 JDK：

```bash
tools/mvn-jdk25 -version
```

默认使用 Homebrew JDK
`/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`。如需覆盖，仅为当前命令
设置 `THINGSBOARD_JAVA_HOME`。

## 2. 从当前源码构建镜像

必须先生成当前源码的安装包和 Docker 构建目录，再由 Compose 构建镜像：

```bash
tools/mvn-jdk25 -pl msa/tb,msa/transport/wan -am clean install \
  -DskipTests -Ddockerfile.skip=true

docker compose -f docker/docker-compose.local.yml build \
  thingsboard wan-transport
```

Compose 固定使用本地 `4.4.0-SNAPSHOT` 标签并设置 `pull_policy: never`，不会静默
回退到 Docker Hub 的旧 `latest` 镜像。生成目录分别是：

- `msa/tb/target/docker-postgres`；
- `msa/transport/wan/target`。

模拟 NS 复用同一个当前源码 WAN Transport 镜像，以独立 Java 主类启动，不引入额外运行库。

如需指定自定义本地标签，可在启动时设置 `THINGSBOARD_IMAGE` 和
`WAN_TRANSPORT_IMAGE`，但镜像必须已存在于本机。

## 3. 启动与停止

Core 与 WAN Transport 必须使用同一连接密码加密密钥。请为本地环境设置一个稳定密钥，
不要把密钥提交到仓库或复制到 Issue、日志：

```bash
export WAN_CONNECTION_PASSWORD_ENCRYPTION_KEY="<仅用于本机的稳定随机密钥>"
```

启动全部服务：

```bash
docker compose -f docker/docker-compose.local.yml up -d
docker compose -f docker/docker-compose.local.yml ps
```

本地端点：

| 服务 | 地址 | 用途 |
| --- | --- | --- |
| ThingsBoard | `http://localhost:19090` | Web 与 REST API |
| ThingsBoard MQTT | `localhost:11884` | 普通设备 MQTT |
| 模拟 NS MQTT | `localhost:11883` | TurMass WAN MQTT Broker |
| 模拟 NS 控制面 | `http://localhost:18080` | 健康、状态、播发上行 |
| WAN 指标 | `http://localhost:18086/actuator/prometheus` | Prometheus 指标 |

停止并保留数据：

```bash
docker compose -f docker/docker-compose.local.yml down
```

仅在明确需要全新安装时删除数据库、队列和日志卷：

```bash
docker compose -f docker/docker-compose.local.yml down -v
```

## 4. 主链路黑盒验收

服务稳定后，从仓库根目录执行：

```bash
python3 docker/wan-e2e/run.py
```

验收脚本只通过公开边界观察系统，不读取数据库或调用 Java 内部服务。它会：

1. 调用 REST 验证 NS 连接并保存带掩码密码的连接；
2. 创建 WAN 设备配置，验证已有网关由 NS 状态覆盖；
3. 创建终端，验证 NS 空查询后执行 `add_terminal`；
4. 由模拟 NS 通过 MQTT 发送上行，验证 `wanData`、`wanRequestId`、`wanPort`、`rssi`、`snr` telemetry；
5. 通过服务端 RPC 验证终端下行、定向广播和全网广播；
6. 验证终端删除后重建，以及平台删除同步到 NS；
7. 验证 WAN 指标存在且已累计，并扫描容器日志中的密码和根密钥测试值。

脚本使用唯一名称创建临时连接、设备配置和设备，并在结束时清理。它会重置模拟 NS，
因此不要把此模拟 NS 当作持久测试数据源。自定义登录信息：

```bash
TB_USERNAME="<租户管理员账号>" TB_PASSWORD="<密码>" \
  python3 docker/wan-e2e/run.py
```

## 5. 指标与日志

WAN Transport 暴露以下低基数指标，不包含租户、设备、密码或根密钥标签：

- `tb_wan_connections_active`：当前活跃 NS 连接数；
- `tb_wan_ns_requests_total`：同步类 NS 请求数；
- `tb_wan_ns_request_timeouts_total`：NS 请求超时数；
- `tb_wan_synchronizations_total{result=...}`：同步成功/失败数；
- `tb_wan_uplinks_total{result=...}`：上行成功/失败数；
- `tb_wan_downlinks_total{operation=...,result=...}`：下行与广播成功/失败数。

查看指标：

```bash
curl -fsS http://localhost:18086/actuator/prometheus | grep '^tb_wan_'
```

查看服务日志：

```bash
docker compose -f docker/docker-compose.local.yml logs -f thingsboard wan-transport mock-ns
docker compose -f docker/docker-compose.local.yml logs --since=10m wan-transport
```

日志只记录连接 ID、设备 ID、请求 ID、操作名和结果，不记录 NS 密码、MQTT 消息体或终端
根密钥。模拟 NS 的 `/state` 也会移除 `root_key`。

## 6. 常见故障定位

### ThingsBoard 无法登录

先检查容器状态和最近日志：

```bash
docker compose -f docker/docker-compose.local.yml ps
docker compose -f docker/docker-compose.local.yml logs --tail=200 thingsboard
```

首次启动要初始化 PostgreSQL，登录页出现不代表后端已完全就绪；等待日志出现启动完成后重试。

### WAN 连接一直不活跃

确认模拟 NS 健康、WAN 已订阅响应主题，并检查连接配置中的容器内地址必须是
`mock-ns:1883`：

```bash
curl -fsS http://localhost:18080/health
curl -fsS http://localhost:18080/state
docker compose -f docker/docker-compose.local.yml logs --tail=200 wan-transport mock-ns
```

`responseSubscribers` 应大于 0。主机上的 `localhost:11883` 只供主机工具访问，容器之间
不能使用该地址。

### 保存过密码后 WAN 无法解密

Core 与 WAN Transport 的 `WAN_CONNECTION_PASSWORD_ENCRYPTION_KEY` 不一致，或重启时换了
密钥。恢复创建连接时使用的本地密钥，再重启两个服务；不要在日志中打印密钥。

### 设备长时间停留在 PENDING、SYNCING 或 DELETING

检查模拟 NS `operationCounts` 与 WAN 的同步/超时指标。默认本地轮询间隔为 1 秒；如果
`tb_wan_ns_request_timeouts_total` 增长，重点检查 MQTT 主题、Broker 地址和模拟 NS 日志。

### Compose 意外使用旧代码

重新执行第 2 节的 Maven 与 Compose build 命令，然后确认镜像创建时间：

```bash
docker image inspect thingsboard/tb-postgres:4.4.0-SNAPSHOT \
  --format '{{.Created}} {{.Id}}'
docker image inspect thingsboard/tb-wan-transport:4.4.0-SNAPSHOT \
  --format '{{.Created}} {{.Id}}'
```

不要把 `latest` 用作本地源码验收标签。
