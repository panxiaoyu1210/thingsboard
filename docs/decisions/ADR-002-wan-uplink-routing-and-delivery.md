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

# ADR-002：WAN 上行使用连接内终端路由并继承 MQTT 交付语义

## 状态

已接受

## 日期

2026-09-07

## 背景

TurMass WAN NS 通过连接的上行主题发送 `push_uplink`。不同租户可以配置不同 NS，相同 `dev_eui` 也可能合法地出现在不同连接中。WAN Transport 必须把消息交给正确终端，同时让设备活动、Device Profile 规则链、API 限额和时序存储继续使用 ThingsBoard 的标准处理链路。

协议中的 `req_id` 是普通整数，没有全局唯一性、生成方标识或时间窗口。NS 发起的上行可能复用 ThingsBoard 尚未完成请求的编号，也可能在设备持续上报时重复使用编号，因此 `wanRequestId` 只用于诊断和协议关联，不能作为数据库主键或可靠的上行幂等键。

## 决策

WAN Transport 在每次配置刷新时构建只读路由表，路由键为 `connectionId + dev_eui`。设备信息由 Core 的 Transport API 返回，并同时携带租户、客户、Device Profile、设备名称和设备类型。只有以下条件全部满足时才写入遥测：

- 路由键只对应一个设备；
- 设备租户与当前 NS 连接租户一致；
- WAN 配置角色和 ThingsBoard 网关标记都表明目标是终端；
- 上行字段通过协议边界校验。

每个有效 `push_uplink` 转换为一个 `PostTelemetryMsg`，包含字符串 `wanData` 和整数 `wanRequestId`、`wanPort`、`rssi`、`snr`，并调用标准 `TransportService.process`。五个键使用同一个接收时间戳，构成设备的一条 WAN 上行记录。同一 WAN Transport 实例内，每台设备的接收时间戳分别保持单调；同一设备在同一毫秒内的连续上行依次递增 1 毫秒，避免标准时序点覆盖且不改变其他设备的接收时间。该短期状态按最近访问时间过期，不永久保留已删除设备。该入口会记录设备活动，按 Session 中的 Device Profile 路由规则链，再由现有规则链保存时序数据。第一版保留十六进制载荷，不解析业务字段。

设备详情按该时间戳重新聚合五个键并提供 WAN 上行历史视图。历史数据继续使用标准 Time Series API、设备访问权限和租户时序 TTL，不新建第二套消息表。升级前没有 `wanRequestId` 的记录仍可展示，其请求编号为空。

ThingsBoard 的通用 JSON 类型转换继续支持把普通数字字符串转换为数值，但带显著前导零的字符串必须保持字符串。该无损规则适用于所有遥测和属性，可防止纯数字十六进制载荷或其他带前导零标识在规则链保存时被改写。

响应关联器只消费带 `rsp_code` 的响应消息。`push_uplink` 即使复用了待处理请求的 `req_id`，也会进入上行分发，不会错误终止平台请求。

重复处理继承连接配置的 MQTT QoS 和 Paho 客户端行为，不增加应用层去重：

- QoS 0 是至多一次；Transport 处理客户端实际交付的每条消息一次。
- QoS 1 是至少一次；Broker 重投的消息会再次写入遥测，以避免没有可靠幂等键时误删真实上行。
- QoS 2 的协议级去重由 Paho 和 Broker 完成；Transport 对最终交付的消息处理一次。
- 当前 MQTT 连接使用 clean session，断线期间的离线消息保留不在本版本保证范围内。

## 备选方案

### 仅按 dev_eui 路由

实现简单，但同一 EUI 出现在其他连接或租户时会误写设备，违反租户隔离，因此不采用。

### 使用 req_id 做持久化去重

协议没有声明 `req_id` 全局唯一或单调递增。持久化编号会把正常复用误判为重复，并且需要额外定义无依据的过期窗口，因此不采用。

### 绕过 TransportService 直接写时序数据

这会跳过设备活动、租户限额、Device Profile 规则链和统一错误处理，使 WAN 成为特殊数据通道，因此不采用。

## 结果

- 上行隔离边界与 NS 连接所有权边界一致。
- 未知、重复映射、跨租户和角色错误会被拒绝并记录诊断日志，不会回退到其他设备。
- QoS 1 可能产生重复时序点；如协议未来提供稳定消息唯一标识，可另写 ADR 引入有界幂等策略。
- 业务载荷解析应在规则链或后续独立功能中完成，不进入第一版 Transport 解析器。
- 上行记录表示消息已通过 WAN Transport 校验并提交标准遥测链路，不保证后续租户规则链业务处理成功；无法解析、无法路由或角色不匹配的消息继续只记录失败指标和受限日志。
