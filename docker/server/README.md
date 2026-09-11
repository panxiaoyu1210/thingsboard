# ThingsBoard WAN 单机服务器迁移与部署

本目录用于将当前本地 ThingsBoard WAN 环境的数据迁移到一台 Linux Docker 服务器。
服务器使用独立 PostgreSQL 16 保存业务数据，自定义 `tb-node` 运行单体 ThingsBoard，
自定义 `tb-wan-transport` 连接真实 NS。生产拓扑不启动模拟 NS。

## 交付内容

| 文件 | 用途 |
| --- | --- |
| `compose.yml` | PostgreSQL、Kafka、Zookeeper、Valkey、ThingsBoard、WAN Transport 编排 |
| `.env.example` | 不含真实密钥的服务器配置模板 |
| `scripts/build-images.sh` | 构建指定 CPU 架构的两个自定义镜像并生成离线包 |
| `scripts/backup-current.sh` | 从当前本地内置 PostgreSQL 创建逻辑备份 |
| `scripts/restore.sh` | 校验备份、保护性恢复数据库并按顺序启动服务 |
| `scripts/validate.sh` | 检查必填配置、文件权限、Compose 和镜像架构 |
| `scripts/backup-server.sh` | 上线后的日常 PostgreSQL 备份 |
| `nginx/thingsboard.conf.example` | 宿主机 Nginx HTTPS 与 WebSocket 反向代理示例 |

数据库迁移包与服务器 `.env` 必须分开传输和保存。所有脚本默认不输出密码或 WAN 加密
密钥。

## 数据边界

PostgreSQL 逻辑备份包含以下业务数据：

- 租户、用户、客户、设备、资产和设备配置；
- 遥测、属性、告警和事件；
- 规则链、JavaScript/TBEL 脚本和节点配置；
- 仪表板、部件、资源和通知配置；
- 系统邮件设置、WAN NS 连接、同步状态和加密凭据。

Kafka 是待处理消息队列，Valkey 是缓存，Zookeeper 是服务协调数据，三者不作为业务数据
迁移。最终割接前需要停止外部写入并等待消息处理完成，之后重新生成最终数据库备份。

## 必须准备

- Ubuntu 22.04 或 24.04 LTS 64 位服务器；
- Docker Engine、Docker Buildx 和 Docker Compose V2；
- 建议至少 4 核 CPU、16 GB 内存和 100 GB SSD；
- 指向服务器公网 IP 的正式域名；
- Nginx 和有效 HTTPS 证书；
- 能从服务器访问的真实 NS MQTT Broker；
- Docker 镜像仓库，或使用本目录生成的离线镜像包；
- 服务器之外的数据库备份存储。

执行以下命令确认目标 CPU 架构：

```bash
uname -m
```

`x86_64` 对应 `linux/amd64`，`aarch64` 对应 `linux/arm64`。镜像架构必须与服务器一致。

## 构建和交付镜像

在源码机器的仓库根目录执行。示例服务器为 `x86_64`，使用提交号作为不可变版本标签：

```bash
TB_MVN_CMD=tools/mvn-jdk25 \
  docker/server/scripts/build-images.sh \
  registry.example.com/iot "$(git rev-parse --short=12 HEAD)" linux/amd64
```

如果 Docker Hub 或配置的国内镜像代理无法下载 `thingsboard/openjdk25:trixie-slim`，可以
使用 AWS Public ECR 中的官方 Debian Trixie 镜像在本机构造等价的 OpenJDK 25 基础镜像，
并继续完成业务镜像构建：

```bash
TB_BUILD_LOCAL_BASE=true \
TB_DEBIAN_MIRROR=http://mirrors.aliyun.com/debian \
TB_DEBIAN_SECURITY_MIRROR=http://mirrors.aliyun.com/debian-security \
TB_MVN_CMD=tools/mvn-jdk25 \
  docker/server/scripts/build-images.sh \
  local/thingsboard "$(git rev-parse --short=12 HEAD)" linux/amd64
```

该路径不会修改 Docker Desktop 的全局镜像代理。基础镜像构造对齐 ThingsBoard 官方
`thingsboard/docker` 仓库的 UID/GID 799、OpenJDK 25.0.4.1 和 DNS 缓存配置；Debian
基础镜像使用固定 digest。`TB_DEBIAN_MIRROR` 和 `TB_DEBIAN_SECURITY_MIRROR` 只改变软件
包下载地址，APT 仍使用 Debian archive keyring 校验签名。也可以单独执行
`scripts/build-base-image.sh`，再通过 `TB_DOCKER_BASE_IMAGE` 指定已经存在的可信基础镜像。

输出位于 `docker/server/release/`，包含两个镜像的压缩离线包和 SHA-256 校验文件。也可以
将脚本输出的两个固定标签推送到镜像仓库：

```bash
docker push registry.example.com/iot/tb-node:<版本>
docker push registry.example.com/iot/tb-wan-transport:<版本>
```

生产环境禁止使用 `latest` 或 `SNAPSHOT`，镜像标签必须能对应到一个 Git 提交。构建脚本在
存在已修改的跟踪文件时默认拒绝执行，避免镜像内容无法追溯。仅制作明确标记的临时测试
镜像时才可以设置 `ALLOW_DIRTY_BUILD=true`。

## 创建源端备份

脚本默认读取当前容器 `thingsboard-local-thingsboard-1` 中的 `thingsboard` 数据库：

```bash
docker/server/scripts/backup-current.sh
```

输出位于 `docker/server/backups/`：

```text
thingsboard-<UTC时间>.dump
thingsboard-<UTC时间>.dump.sha256
```

该备份使用 PostgreSQL 自定义格式，具备事务一致性。它不会包含备份开始之后的新写入。
因此推荐先做一次演练备份；正式割接时停止 WAN Transport、阻止设备接入和用户修改，等待
当前消息处理结束，再执行一次最终备份。

如源容器、数据库或用户名称不同，可临时指定：

```bash
SOURCE_TB_CONTAINER=<容器名> \
SOURCE_TB_DATABASE=<数据库名> \
SOURCE_TB_DATABASE_USER=<数据库用户> \
  docker/server/scripts/backup-current.sh
```

不要复制当前 PostgreSQL 12 的 `/data/db` 物理目录到 PostgreSQL 16。

## 服务器配置

将整个 `docker/server` 目录复制到目标服务器，例如 `/opt/thingsboard`：

```bash
cd /opt/thingsboard
cp .env.example .env
chmod 600 .env
```

### 必须修改

| 配置 | 修改方式 | 说明 |
| --- | --- | --- |
| `THINGSBOARD_IMAGE` | 改为实际固定镜像标签 | 自定义 `tb-node` |
| `WAN_TRANSPORT_IMAGE` | 改为实际固定镜像标签 | 自定义 WAN Transport |
| `POSTGRES_PASSWORD` | 生成新的强密码 | 同时供 PostgreSQL 和 Core 使用 |
| `VALKEY_PASSWORD` | 生成新的强密码 | 同时供 Valkey、Core 和 WAN 使用 |
| `UI_MAP_TIANDITU_API_KEY` | 填写生产 Key | 正式域名/IP需要在天地图白名单内 |
| Nginx `CHANGE_ME_DOMAIN` | 改为正式域名 | 同时修改证书路径 |

### 必须原样迁移

| 配置 | 来源 | 原因 |
| --- | --- | --- |
| `WAN_CONNECTION_PASSWORD_ENCRYPTION_KEY` | 当前仓库根目录 `.env` | 解密数据库中的 NS 密码和终端根密钥 |

不要在新服务器重新生成 WAN 加密密钥。该值应通过密码管理器、Secret 管理服务或其他独立
安全渠道传输，不能提交到 Git、Issue、镜像或数据库备份包。

### 按需修改

| 配置 | 默认值 | 何时修改 |
| --- | --- | --- |
| `TB_WEB_BIND_ADDRESS` | `127.0.0.1` | Nginx 不在宿主机时 |
| `TB_HTTP_PORT` | `8080` | 宿主机端口冲突时 |
| `TB_MQTT_BIND_ADDRESS` | `127.0.0.1` | 普通 MQTT 设备确需公网直连时改为 `0.0.0.0` |
| `TB_MQTT_PORT` | `1883` | MQTT 端口冲突时 |
| `WAN_METRICS_BIND_ADDRESS` | `127.0.0.1` | 外部 Prometheus 需要访问时 |
| `TB_JAVA_OPTS` | 最大堆 4 GB | 根据服务器内存调整 |
| `WAN_JAVA_OPTS` | 最大堆 1 GB | 根据终端和连接数量调整 |
| `WAN_*_INTERVAL_MS` | 30 秒 | 明确需要更快同步且评估 NS 压力后 |

`POSTGRES_IMAGE`、`KAFKA_IMAGE`、`ZOOKEEPER_IMAGE` 和 `VALKEY_IMAGE` 已提供与当前环境兼容
的默认值。正式上线前建议将这些第三方镜像同步到自己的镜像仓库，并固定经过验证的版本或
镜像 digest，避免上游标签变化影响重建。

生成新数据库和缓存密码时可使用：

```bash
openssl rand -hex 24
```

填写完成后执行：

```bash
./scripts/validate.sh
```

校验失败时不要继续恢复数据库。

## 加载镜像

目标服务器可访问镜像仓库时：

```bash
docker compose --env-file .env -f compose.yml pull
```

使用离线包时，先核对校验和再加载：

```bash
cd release
sha256sum -c thingsboard-images-<版本>-amd64.tar.gz.sha256
docker load --input thingsboard-images-<版本>-amd64.tar.gz
cd ..
docker compose --env-file .env -f compose.yml pull postgres kafka zookeeper valkey
```

## 恢复数据库并启动

将最终 `.dump` 和 `.sha256` 放进 `/opt/thingsboard/backups/`，然后执行：

```bash
./scripts/restore.sh backups/thingsboard-<UTC时间>.dump
```

恢复脚本要求同目录存在对应的 `.dump.sha256`，缺少校验文件或校验失败时会拒绝恢复。

脚本会：

1. 校验 `.env`、Compose、镜像架构和备份 SHA-256；
2. 停止 Core 与 WAN Transport；
3. 启动 PostgreSQL、Kafka、Zookeeper 和 Valkey；
4. 检查目标数据库是否已有业务表；
5. 恢复数据库并执行统计信息更新；
6. 先启动 ThingsBoard Core，再启动 WAN Transport；
7. 输出最终容器状态。

如果目标数据库已经有表，脚本默认拒绝覆盖。确认覆盖时使用：

```bash
./scripts/restore.sh backups/thingsboard-<UTC时间>.dump --confirm-overwrite
```

覆盖前脚本会自动在 `backups/` 创建目标数据库安全备份。恢复已有数据库时不要运行
`INSTALL_TB=true`、ThingsBoard 安装脚本或 `--loadDemo`。

## 配置 HTTPS

复制 `nginx/thingsboard.conf.example` 到宿主机 Nginx 配置目录，替换其中所有
`CHANGE_ME_DOMAIN`，确认 HTTPS 证书路径后加载配置：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Compose 默认将 Web 端口限制在 `127.0.0.1:8080`。Nginx 示例已包含 ThingsBoard
WebSocket 所需的 Upgrade 请求头和长连接超时。

## 数据库恢复后必须检查

数据库会带回旧配置，但以下项目依赖新服务器环境，必须逐项检查：

1. 使用系统管理员登录，在“设置 → 常规设置”中将 Base URL 改为正式 HTTPS 域名；
2. 在“设置 → 邮件服务器”重新发送腾讯企业邮箱测试邮件；
3. 使用租户管理员登录，进入“WAN NS 连接”，将 `mock-ns:1883` 或旧内网地址改成真实
   NS MQTT 地址；
4. 检查 NS 用户名和密码能否正常解密并连接；
5. 检查天地图生产域名/IP白名单；
6. 检查 WAN 设备配置绑定的规则链、终端同步状态和告警；
7. 修改默认系统管理员、租户管理员和演示账号密码；
8. 只开放 `22`、`80`、`443` 以及实际需要的设备接入端口。

## 验收

```bash
docker compose --env-file .env -f compose.yml ps
curl -fsS http://127.0.0.1:8086/actuator/health
curl -fsS http://127.0.0.1:8086/actuator/prometheus \
  | grep '^tb_wan_connections_active'
```

还需通过页面验证登录、设备、遥测、规则链、告警、地图、邮件、终端上行和下行。不要只以
容器状态为上线成功依据。

## 上线后的备份

```bash
./scripts/backup-server.sh
```

脚本创建数据库快照及 SHA-256 文件。必须定期把两者复制到另一台服务器或对象存储，并
定期进行恢复演练。Docker named volume 不是备份。

## 回滚

迁移过程中保持源服务器不变。验收失败时：

1. 停止目标服务器的 `wan-transport` 和 `thingsboard`；
2. 将 DNS 或入口流量切回源服务器；
3. 恢复设备和用户写入；
4. 保留目标日志、数据库及覆盖前安全备份用于排查。

如果目标服务器已经接收新数据，切回源服务器前必须明确处理这部分数据差异，不能同时让
两套环境连接同一个真实 NS 并接收写入。
