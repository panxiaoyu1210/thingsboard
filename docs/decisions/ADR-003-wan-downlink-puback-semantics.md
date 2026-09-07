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

# ADR-003：WAN 下行以 MQTT PUBACK 作为发送成功边界

## 状态

已接受

## 日期

2026-09-07

## 背景

ThingsBoard 服务端 RPC 需要向 TurMass WAN 终端发送 `push_downlink`，并允许 WAN 网关发送指定网关或全网 `push_broadcast`。NS 接口没有为这两类消息定义业务响应，用户也明确要求发送成功后立即返回，不等待 NS 是否处理或无线终端是否收到。

如果使用连接的可配置 QoS 0，客户端写入本地网络缓冲后就可能被视为成功，无法证明 Broker 已接收消息。如果等待不存在的 NS 响应，则所有正常请求最终都会超时。

## 决策

所有 WAN RPC 下行固定使用 MQTT QoS 1，不继承 NS 连接用于普通请求的 QoS。Paho 发布令牌在 Broker 返回 PUBACK 后完成；这个完成点定义为 WAN RPC 的 `SENT`：

- 两路 RPC 返回 `{"success":true,"status":"SENT"}`。
- 持久化 RPC 同时收到 `SENT` 状态更新。
- 不等待 NS 业务响应，也不等待无线侧确认。
- Broker 发布失败、连接断开或 PUBACK 等待超时均返回 `FAILED`，不能伪装成已发送。
- PUBACK 等待时间取 NS 连接请求超时和 RPC 剩余有效期中的较小值。

终端仅接受 `wanDownlink`，参数为 `port` 和 `data`，目标 `dev_eui` 从当前 ThingsBoard 设备配置取得。网关仅接受 `wanBroadcast`：默认把当前网关 ID 写入 `gw_id`；只有显式传入布尔值 `broadcastAll=true` 时才省略 `gw_id` 并执行全网广播。缺少网关参数不会意外扩大广播范围。

WAN Transport 为当前实例持有连接的 WAN 设备注册异步 RPC Session。Session 使用真实 Transport `serviceId`，设备或连接路由变化时立即重新注册，并以定时刷新作为恢复保障；连接所有权丢失时先撤销旧 RPC Session，避免故障转移窗口内两个实例同时接收下行。即使并发中的旧 RPC 已到达，发布也会因连接不可用而明确失败。

## 备选方案

### 使用连接配置的 QoS

QoS 0 不提供 PUBACK，不能满足“Broker 接收发布才成功”的边界；QoS 2 增加握手但没有额外业务价值，因此不采用。

### 等待 NS 或终端确认

现有协议没有定义 `push_downlink` 和 `push_broadcast` 的业务响应，等待会把成功发送误判为超时，因此不采用。

### 缺少 gw_id 时默认全网广播

该行为会把参数遗漏放大为全网副作用，风险不可接受，因此不采用。

## 结果

- `SENT` 只证明 Broker 已接收 QoS 1 消息，不代表 NS 或终端完成处理。
- RPC 调用者能区分参数错误、连接错误和发布超时。
- 全网广播必须主动声明，指定网关广播是安全默认值。
- 如果未来协议增加 NS 业务回执，应作为新的可选状态设计，不能改变 `SENT` 的既有含义。
