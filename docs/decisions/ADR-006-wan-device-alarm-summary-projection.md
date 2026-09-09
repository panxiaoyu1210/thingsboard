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

# ADR-006：以设备告警摘要投影驱动 WAN 安装地图状态

## 状态

已接受

## 日期

2026-09-09

## 背景

ADR-004 定义了 WAN 设备安装位置及其地图部件。地图需要把存在未清除告警的设备标记为红色，并在最后一个告警清除后恢复正常颜色。

地图实体数据源能够实时订阅属性和最新遥测，但不能为别名中的每台设备直接订阅独立的 Alarm Count。若地图按设备逐个查询告警，将产生 N+1 查询和额外的前端状态合并；若在告警事件上简单执行计数加减，则重复事件、并发事件、进程中断及同设备多告警都会导致状态漂移。

## 决策

为 WAN 设备维护只读派生的 `SERVER_SCOPE` 告警摘要：

- `activeAlarmCount`：当前由该设备作为 originator 的未清除告警数量；
- `highestActiveAlarmSeverity`：活动告警中的最高等级；没有活动告警时删除该属性。

Alarm 实体仍是权威数据源。摘要仅供地图和其他实时界面订阅，不参与告警审计、统计、确认或清除判断。

Alarm 创建、更新、等级变化、确认、清除和删除完成后，由统一的告警订阅生命周期回调请求刷新。该入口覆盖 REST、规则链、Calculated Field 和 Edge；其中确认等不改变活动状态的操作会被后续幂等比较消除。刷新不执行 `+1/-1`，而是按 originator 在数据库中按严重级别聚合未清除告警，再计算总数和最高等级。

刷新请求按设备去重，并由单线程工作器串行执行。这样同一设备的连续告警事件最终总是以数据库最新状态覆盖摘要，避免异步属性写入乱序。写入前比较现有摘要，相同结果不重复写入或广播。

系统启动 30 秒后在同一工作器中分页扫描 WAN 设备注册表，随后默认每小时校正一次，并只处理当前 `tb-core` 分区负责的设备，用于首次部署和异常中断后的存量校正。每页读取后直接处理，不将全量设备压入内存队列；删除流程中的注册项不参与周期扫描。增量刷新前再次确认设备仍属于 WAN 注册表。

系统部件通过 `activeAlarmCount` 的颜色范围控制单个标记：大于零时使用红色，零或缺失时保持网关蓝色或终端绿色。聚合标记检查所有子标记，只要任一设备的计数大于零就使用红色。Tooltip 同时显示计数与最高等级。

## 备选方案

### 浏览器逐设备查询活动告警

无需派生属性，但会造成 N+1 请求、复杂订阅生命周期和较慢的地图加载，且聚合标记仍需额外合并，因此不采用。

### 告警事件直接维护 `+1/-1` 计数

写入成本较低，但无法可靠抵御重复事件、乱序、重试或进程中断，也容易在多告警清除时错误恢复正常颜色，因此不采用。

### 只保存 `hasActiveAlarm` 布尔值

足以控制红色，但不能展示活动告警数量，也无法表达最高等级。重新聚合的成本已经确定，因此保留更有用的摘要字段。

### 让规则链维护摘要

适合只存在单一告警类型的简单部署，但通用规则链在清除一个告警时无法安全判断是否还存在其他活动告警。该行为属于地图的产品能力，因此由平台服务统一维护。

## 结果

- 地图通过普通属性订阅实时更新，不增加每设备告警查询。
- 多告警、已确认未清除告警和最后一个告警清除具有明确行为。
- 摘要具有最终一致性；告警事务完成后异步刷新，周期任务负责异常恢复。
- 周期校正按页、按核心分区执行，查询结果按严重级别聚合，单设备最多返回五行。
- 新增摘要字段必须保持只读派生语义，业务逻辑不能将其作为 Alarm 权威状态。

关联：#29、#26、ADR-004。
