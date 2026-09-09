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

# ADR-007：WAN 用户下行入口复用持久化 RPC 并保留命令来源

## 状态

已接受

## 日期

2026-09-09

## 背景

ADR-003 已定义 WAN 下行协议及 `SENT` 的语义，但用户此前只能通过通用 RPC
接口或部件手工构造请求。消防 AS 平台需要在设备详情和告警地图中直接对当前
终端或网关执行控制，同时还需要一个可配置目标设备的 Dashboard 系统部件。

本期开放原始十六进制 `data`。消防命令模板需要在模型上预留，但在模板内容、
版本、权限和发布流程确定前不能向用户开放一个看似可用但无法治理的入口。

## 决策

所有入口复用同一个 WAN 下发弹窗和现有服务端 RPC API，不新增专用下行 REST
端点。弹窗加载目标 Device 并根据 WAN Transport Configuration 决定命令：

- 终端使用 `wanDownlink`，参数为 `port`（0–255）和 `data`；
- 网关使用 `wanBroadcast`，默认限定当前网关；
- 只有用户明确选择“全网广播”时才发送 `broadcastAll=true`；
- 广播载荷继续遵守协议的 35 字节上限；
- 空格和换行在 UI 边界移除，最终载荷转换为大写十六进制。

UI 使用持久化双向 RPC，`retries=0`。持久化记录的 `additionalInfo` 保存：

- `commandSource`：当前固定为 `RAW_HEX`；模型同时保留 `TEMPLATE` 枚举；
- `commandOrigin`：`DEVICE_DETAILS`、`DASHBOARD` 或 `MAP`；
- `downlinkMode`：单播、当前网关广播或全网广播；
- 可选 `reason`；
- 为后续模板审计预留的 `templateId` 和 `templateVersion`，当前均为 `null`。

模板没有路由、菜单、表单、服务或系统部件配置，因此当前版本无法启用模板命令。
后续开放模板必须另行定义模板版本不可变性、适用设备范围和授权边界。

设备详情按钮固定当前 Device。Dashboard 系统部件通过目标设备别名确定 Device。
WAN 安装地图仅在单设备存在活动告警时于 Tooltip 显示下发动作；聚合标记仍用于
提示范围内存在告警，用户需放大拆分后才能对具体设备执行控制。悬浮 Tooltip
允许鼠标移入，以保证动作按钮可点击。

UI 成功提示仍以 `SENT` 为边界，文案只表示消息已发送到 NS。是否由无线终端执行
不在本期保证范围内，继续遵守 ADR-003。通用 Device Actor 仅在设备 Transport
类型为 WAN、方法为 `wanDownlink` 或 `wanBroadcast`，且响应明确为
`{"success":true,"status":"SENT"}` 时保留持久化终态 `SENT`；其他设备响应仍按
既有逻辑记录为 `SUCCESSFUL`。

## 备选方案

### 新增 WAN 专用 REST 端点

可以把 UI 校验复制到服务端控制器，但现有 RPC API 已提供设备访问校验、审计和
持久化状态，Transport 又是协议角色与载荷校验的权威边界。新增端点会形成两条
权限和状态链路，因此不采用。

### 直接使用通用 RPC Button

无需开发新界面，但用户必须理解方法名、角色和 `broadcastAll` 的危险语义，且无法
稳定记录入口与操作原因，因此不采用。

### 聚合标记直接下发

聚合标记可能代表多个不同位置和状态的设备，无法确定唯一目标。把任一成员告警
解释为整个聚合目标会扩大控制范围，因此不允许在聚合标记上执行下发。

## 结果

- 三个入口具有相同的角色判断、十六进制校验、确认和状态语义；
- 每次 UI 下发都可在持久化 RPC 中查询，并能区分入口和广播范围；
- 后端 Transport 仍是设备角色、端口、载荷及广播长度的最终校验者；
- 现有 Dashboard 中的 WAN 安装地图由系统部件运行时脚本补齐动作，无需重新添加；
- 模板命令仍不可用，开放时必须完成独立需求和安全评审；
- 业务执行回执仍需协议提供可关联的确认消息后另行设计。

关联：#31、ADR-003、ADR-006。
