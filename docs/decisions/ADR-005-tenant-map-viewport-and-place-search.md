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

# ADR-005：租户默认地图视角与设备安装位置分离

## 状态

已接受

## 日期

2026-09-08

## 背景

ADR-004 已定义 WAN 设备安装位置：设备的 `SERVER_SCOPE` `latitude`、`longitude` 表示由管理员维护的设备登记位置。实际使用中，仅靠点击或拖动全国地图很难快速找到目标位置；不同租户的设备又通常集中在各自城市或园区，部署级全国默认视角不能为所有租户提供合适的创建体验。

租户维护的位置不能直接继承为设备安装位置。前者只是地图打开时的 UI 上下文，后者是设备事实数据；如果把两者共用一组字段，新建设备会在没有人工确认的情况下产生错误安装位置。

租户管理员当前可以读取自己的租户属性，但没有通用的租户属性写权限。为了保存少量地图配置而开放任意属性写入，会扩大既有权限边界。

## 决策

新增“租户默认地图视角”，由以下租户 `SERVER_SCOPE` 属性保存：

- `mapDefaultCenterLatitude`：默认中心纬度；
- `mapDefaultCenterLongitude`：默认中心经度；
- `mapDefaultZoom`：Leaflet 缩放级别，范围为 1–18；
- `mapDefaultLocationName`：可选的位置名称，仅用于界面说明。

中心经纬度和缩放级别必须同时存在或同时不存在。通过 `/api/tenant/mapSettings` 受控接口读写这些固定字段：租户管理员可以读取和维护自己租户的配置，客户用户只能读取。该接口不改变 `Resource.TENANT` 的通用属性权限。

安装位置选择器按以下顺序决定初始视角：

1. 设备已有安装位置时，以设备位置为中心；
2. 设备没有安装位置时，使用当前租户默认地图视角；
3. 租户没有配置时，回退到 `UI_MAP_TIANDITU_DEFAULT_CENTER_*` 和 `UI_MAP_TIANDITU_DEFAULT_ZOOM`。

租户默认中心只影响地图初始化，不复制到 Device，也不参与 WAN Transport Configuration、NS 同步、遥测或 Geofencing。

地名和 POI 搜索使用天地图地名搜索 V2.0。浏览器只访问平台的 `/api/map/tianditu/search`，后端以固定的天地图服务地址发送有限参数，并将外部响应归一化为最多 10 条结果。关键字、地图边界和缩放级别在平台边界校验；外部响应中的坐标和文本再次校验、限长后才返回 UI。

## 备选方案

### 将租户中心直接保存为 `latitude`、`longitude`

能复用设备属性名，但会使“租户默认视角”和“实体安装位置”具有相同语义外观，增加查询、部件和后续继承逻辑误用的风险，因此使用显式的 `mapDefault*` 属性名。

### 开放租户管理员的通用 `WRITE_ATTRIBUTES`

实现简单，但租户管理员会获得修改全部租户服务端属性的能力，超出本功能所需的最小权限，因此采用字段固定的专用 API。

### 浏览器直接调用天地图搜索接口

能够减少一次平台转发，但会让各个 UI 调用点分别处理协议、错误和响应差异，也难以统一约束查询规模。项目已有服务端运行时 Key 配置，因此使用后端搜索代理集中控制边界。

### 将租户位置复制到新建设备

可以让设备创建后立即出现在地图上，但租户中心并不代表具体设备安装位置，会制造错误业务数据，因此仅用作初始视角。

## 结果

- 租户可以获得稳定、可维护的设备选点初始视角。
- 设备安装位置的既有语义和存储方式不变。
- 不需要数据库迁移；删除租户配置后自动恢复部署级默认值。
- 天地图搜索故障不会阻止点击、拖动或手工输入坐标。
- 天地图 Key 需要在控制台具有地名搜索服务权限。

关联：#28、#26、ADR-004。
