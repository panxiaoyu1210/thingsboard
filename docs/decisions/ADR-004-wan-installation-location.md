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

# ADR-004：WAN 设备安装位置使用服务端属性并统一由天地图展示

## 状态

已接受

## 日期

2026-09-08

## 背景

WAN 网关和终端都可能具有固定安装位置。创建人需要在设备创建时选填位置，后续管理员需要修改或清除位置，并在 Dashboard 中统一查看两类设备。现有 ThingsBoard 地图、属性和 Geofencing 能力已经约定使用 `latitude`、`longitude`，但 WAN 创建流程没有形成位置维护闭环。

安装位置与设备实时位置含义不同。实时位置由设备持续上报，具有时间序列；安装位置由平台管理员维护，是设备登记信息。位置还不能进入 WAN Transport Configuration，因为它不属于 NS 协议配置，也不应参与平台权威的 NS 同步和重建。

## 决策

WAN 网关和终端使用相同的可选安装位置模型，以设备 `SERVER_SCOPE` 属性保存：

- `latitude`：数值，范围为 `-90` 到 `90`；
- `longitude`：数值，范围为 `-180` 到 `180`。

两个属性必须同时存在或同时不存在。创建请求仍先保存 Device 和凭证，取得 Device ID 后再保存位置属性。未填写位置时不发起属性写入。设备创建成功但属性写入失败时保留已创建设备，并明确提示部分成功，避免重复创建设备。

位置选点和 Dashboard 展示继续复用项目已有 Leaflet，不引入第二套地图引擎。新增天地图 provider，将矢量、影像或地形底图与相应注记组合成一个逻辑图层。天地图 Key 通过受认证的 UI 运行时配置接口读取，由环境变量 `UI_MAP_TIANDITU_API_KEY` 注入，不写入源代码、日志或系统部件定义。

系统提供 WAN 设备安装地图部件预设。网关和终端使用独立实体别名与不同 Marker 样式，但读取同一组服务端位置属性；没有完整经纬度的设备不绘制。

## 备选方案

### 把位置写入 Device.additionalInfo

能够随 Device 一次保存，但通用属性页、地图数据键和 Geofencing 无法按标准方式直接使用，且会把业务扩展字段混入设备基本信息，因此不采用。

### 把位置写入 WAN Transport Configuration

可以随 WAN 配置一起保存，但安装位置不是 NS 协议配置。NS 同步或平台权威重建会产生不必要的所有权冲突，因此不采用。

### 新建 Location 实体和数据库表

适合需要站点主数据、区域层级和多设备共享位置的场景，但当前需求仅是设备选填坐标。新实体会引入数据库迁移、权限和生命周期管理，超出本期范围，因此不采用。

### 引入天地图 JavaScript SDK

能够直接使用天地图组件，但项目已经以 Leaflet 作为地图交互层。并存两套地图引擎会增加包体、交互差异和维护成本，因此只接入天地图瓦片服务。

## 结果

- 不需要数据库迁移，现有设备和 WAN 同步协议保持兼容。
- 安装位置可直接被属性查询、地图和 Geofencing 使用。
- Device 与属性写入不是单一数据库事务，UI 必须处理部分成功。
- 动态终端轨迹仍使用 Time Series；如果同一设备同时时具有安装位置和实时位置，应通过属性作用域与数据键类型区分。
- 如果未来需要区域、站点或多设备共享位置，应另行设计 Asset/Location 关系模型，不扩展本 ADR 的坐标属性语义。
