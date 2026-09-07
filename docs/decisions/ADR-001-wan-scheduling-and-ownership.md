<!--
Copyright © 2016-2026 The Thingsboard Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# ADR-001：WAN 调度任务与连接所有权使用数据库租约

## 状态

已接受

## 日期

2026-09-04

## 背景

WAN Transport 可以部署多个实例，但同一设备操作不能被重复执行，同一 NS 连接也不能同时建立多个有效订阅。系统还必须在实例异常退出后自动恢复首次同步、每日同步、重建和删除任务。

协调方案需要满足以下约束：

- WAN Transport 不直接访问平台数据库，只能通过 ThingsBoard Transport API 领取和回写任务。
- `delete_gateway`、`delete_terminal` 和平台权威重建具有外部副作用，不能仅依靠单实例内存去重。
- 每日同步需要持久化 `next_sync_time`，支持批量领取、连接级并发和错峰。
- 本地 Compose 已包含 PostgreSQL、Kafka 和 ZooKeeper，但 WAN 功能不应再引入新的协调依赖。

## 决策

使用 PostgreSQL 租约同时管理 WAN 设备任务和 NS 连接所有权。

WAN Device Registry 保存 `lock_owner_id` 和 `lock_until`。Core 在事务中使用 `FOR UPDATE SKIP LOCKED` 按批选择候选记录，再写入 Transport 实例 `serviceId` 和租约截止时间。只有仍持有对应 NS 连接有效租约的实例才能领取设备任务，避免广播触发或并行轮询被非连接 owner 抢占。候选顺序为可靠删除、重建、首次/按需同步、崩溃恢复，最后才是到期的每日同步。

到期 `ACTIVE` 或 `UNKNOWN` 在领取时转为 `PENDING`；为兼容升级前已完成同步但尚无 `next_sync_time` 的记录，空值也按立即到期处理。租约过期的 `SYNCING` 或 `CREATING` 同样转为 `PENDING`；`RECREATING` 和 `DELETING` 保留原状态，依靠查询前置和幂等删除恢复。`FAILED` 不进入候选集。所有状态回写都携带 owner 和操作时间，Core 持有行锁验证租约后才允许修改。

WAN Connection 保存独立的 `ownership_owner_id` 和 `ownership_until`。配置刷新同时领取或续租连接，只返回当前实例拥有的连接。所有启用的同租户连接具有实例亲和性：只要该租户仍有一个有效 owner，其他实例就不能领取该租户新建、失效或尚未归属的连接。这样设备跨 NS 重建时，旧连接的删除请求和新连接的创建请求始终可由同一 Transport 完成。租约列对通用 JPA 保存只读，只能由专用原子 SQL 更新，避免管理员保存业务配置时覆盖并发续租；纯续租也不会修改业务版本或触发 MQTT 重连。正常停机主动释放所有权；续租失败时实例立即关闭本地 MQTT 客户端，避免租约过期后的双消费。

默认任务租约为 15 分钟，覆盖最慢协议请求序列；默认连接租约为 90 秒，并由 30 秒配置刷新续租。两者均可通过 Transport 配置调整，但 Core 限制单次租约不超过 1 小时。

每次非失败同步完成后，Core 按 WAN Connection 的间隔计算下一次时间。默认间隔为 24 小时。关闭自动同步只会暂停周期任务，不清除设备原有的下一次同步时间；重新开启后，已到期设备会立即恢复领取。错峰值由 Device UUID 均匀映射到不超过 1 小时且不超过间隔 10% 的窗口；它具有随机分布但对同一设备保持稳定，便于测试和运维推断。

Transport 使用每个连接独立的信号量执行本地并发限制。批次内无法立即获得并发槽的任务释放数据库租约，由后续轮次重新领取。

## 备选方案

### ZooKeeper 临时锁

ZooKeeper 临时节点适合连接所有权，但设备任务仍需要 `next_sync_time`、状态和超时恢复。混用两套协调机制会增加故障模式，而且禁用 ZooKeeper 时无法保证互斥，因此不采用。

### Kafka 分区所有权

按连接或设备键分区可以减少重复消费，但无法单独表达按时间到期的任务领取、外部操作重试和管理员立即触发，也不能替代持久化任务状态，因此不采用。

### PostgreSQL advisory lock

advisory lock 能提供互斥，但不保存 owner、截止时间和诊断状态；数据库会话断开语义也不适合作为可观察的任务租约，因此不采用。

## 结果

- 多实例共享一个可审计、可超时恢复的协调状态。
- Core 是唯一数据库访问方，WAN Transport 继续遵守现有服务边界。
- PostgreSQL 查询使用 `SKIP LOCKED`，当前实现明确依赖 SQL DAO；非 SQL DAO 需要提供等价领取语义。
- 租约超时必须大于最慢的单次 NS 操作序列，否则旧实例的最终回写会被拒绝并由新实例恢复。
- 连接续租失败采用可用性换一致性的 fail-closed 行为：短暂 Core 故障期间可能断开 NS，但不会产生两个有效消费者。
