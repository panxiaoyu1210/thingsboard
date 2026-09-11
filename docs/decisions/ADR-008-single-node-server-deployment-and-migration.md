# ADR-008：单机服务器采用独立数据库的 Docker 部署与迁移方案

## 状态

已接受

## 日期

2026-09-11

## 背景

当前开发和验收环境使用 `docker/docker-compose.local.yml`。该拓扑为了降低本地启动成本，
将 PostgreSQL 12 与 ThingsBoard 应用放在同一个 `tb-postgres` 镜像和数据卷内，并包含模拟
NS、空密码 Valkey、1 秒 WAN 同步轮询及面向本机的调试端口。

服务器部署需要保留当前 PostgreSQL 中的租户、设备、遥测、规则链、告警、仪表板、邮件
设置、WAN NS 连接以及加密凭据，同时需要让后续数据库备份、恢复和应用升级不依赖应用
容器内部的 PostgreSQL 版本。

## 决策

单服务器生产基线使用以下容器：

- 自定义 `tb-node` 以 `monolith` 模式运行 ThingsBoard Core、规则引擎和内置传输；
- 自定义 `tb-wan-transport` 独立运行 WAN Transport；
- PostgreSQL 16 独立持久化全部业务数据；
- Kafka 保存服务间待处理消息，但不作为业务数据备份来源；
- Valkey 只保存缓存数据；
- Zookeeper 负责 ThingsBoard 服务发现和协调；
- 宿主机 Nginx 终止 HTTPS，并代理到仅监听 `127.0.0.1` 的 ThingsBoard Web 端口。

源环境使用 `pg_dump` 自定义格式生成逻辑备份，目标 PostgreSQL 使用 `pg_restore` 恢复。
不复制 PostgreSQL 12 的物理数据目录，也不迁移 Kafka、Valkey 或 Zookeeper 卷。

`WAN_CONNECTION_PASSWORD_ENCRYPTION_KEY` 不进入镜像或数据库迁移包，通过独立安全渠道
原样配置到 Core 和 WAN Transport。该密钥遗失或改变会使迁移后的 NS 密码和终端根密钥
无法解密。

## 备选方案

### 复制当前 `thingsboard-data` 物理卷

优点是操作步骤少。缺点是数据目录与 PostgreSQL 12、镜像用户、文件权限和宿主机架构
强耦合，不能直接恢复到 PostgreSQL 16，也难以验证备份完整性。因此不采用。

### 继续使用内置 PostgreSQL 的 `tb-postgres` 镜像

优点是容器数量少。缺点是应用发布与数据库生命周期绑定，恢复、扩容和升级风险集中在
同一容器。因此只保留为本地验收方式，不作为服务器基线。

### 完整 ThingsBoard 微服务集群

优点是可以独立扩缩 Core、规则引擎和各传输服务。缺点是单服务器上资源占用和运维复杂度
明显增加，当前规模没有足够收益。设备量和吞吐量达到集群阈值时再单独评估。

## 后果

- 服务器至少需要交付两个自定义镜像、数据库备份、Compose、环境变量和反向代理配置；
- 数据库可以独立备份、恢复和升级；
- 首次恢复已有数据库时禁止执行 ThingsBoard 初始化或加载演示数据；
- 最终割接仍需要短暂禁止数据写入，避免逻辑备份完成后的新数据遗漏；
- 单机故障仍会导致整体不可用，生产环境必须将备份复制到另一台机器或对象存储。
