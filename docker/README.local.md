# 本地 Docker 环境

此开发拓扑运行当前源码构建的 ThingsBoard、Kafka、Zookeeper、Valkey 和独立
`tb-wan-transport`。WAN Transport 只通过 Kafka Transport API 获取连接与设备配置，
不访问 ThingsBoard 数据库。

先使用项目级 JDK 25 构建当前源码镜像：

```bash
tools/mvn-jdk25 -pl msa/tb,msa/transport/wan -am clean install \
  -DskipTests -Ddockerfile.skip=false
```

如需保存带密码的 WAN NS 连接，为本地环境设置独立加密密钥：

```bash
export WAN_CONNECTION_PASSWORD_ENCRYPTION_KEY="<local-development-key>"
```

从仓库根目录启动环境：

```bash
docker compose -f docker/docker-compose.local.yml up -d
```

打开 `http://localhost:19090` 并使用本地测试账号登录。设备 MQTT 端点为
`localhost:11884`。

如需覆盖默认当前源码镜像标签：

```bash
THINGSBOARD_IMAGE=thingsboard/tb-postgres:4.4.0-SNAPSHOT \
WAN_TRANSPORT_IMAGE=thingsboard/tb-wan-transport:4.4.0-SNAPSHOT \
  docker compose -f docker/docker-compose.local.yml up -d
```

项目级 JDK 启动器不会修改全局 Java 版本：

```bash
tools/mvn-jdk25 -version
tools/mvn-jdk25 clean install
```

启动器默认使用 Homebrew JDK
`/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`。如需使用其他
JDK 25，仅为当前命令设置 `THINGSBOARD_JAVA_HOME`。

停止容器但保留数据：

```bash
docker compose -f docker/docker-compose.local.yml down
```

仅在明确需要全新安装时删除本地数据库和队列数据：

```bash
docker compose -f docker/docker-compose.local.yml down -v
```
