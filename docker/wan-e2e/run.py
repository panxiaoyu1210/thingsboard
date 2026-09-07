#
# Copyright © 2016-2026 The Thingsboard Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""ThingsBoard WAN 本地环境黑盒验收。仅通过 REST、MQTT 模拟 NS 和指标端点观察行为。"""

import json
import os
import pathlib
import re
import subprocess
import sys
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


TB_URL = os.getenv("TB_URL", "http://localhost:19090").rstrip("/")
MOCK_NS_URL = os.getenv("MOCK_NS_URL", "http://localhost:18080").rstrip("/")
WAN_METRICS_URL = os.getenv("WAN_METRICS_URL", "http://localhost:18086").rstrip("/")
TB_USERNAME = os.getenv("TB_USERNAME", "tenant@thingsboard.org")
TB_PASSWORD = os.getenv("TB_PASSWORD", "tenant")
PASSWORD_CANARY = "WAN_E2E_PASSWORD_CANARY_7F4A"
ROOT_KEY_CANARY = "A1" * 16
GATEWAY_ID = "8C3F74C81C703000"
TERMINAL_EUI = "0000000000001002"
WAIT_SECONDS = int(os.getenv("WAN_E2E_WAIT_SECONDS", "90"))
REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
COMPOSE_FILE = REPO_ROOT / "docker" / "docker-compose.local.yml"


class ApiError(RuntimeError):
    pass


class HttpClient:

    def __init__(self, base_url, token=None):
        self.base_url = base_url
        self.token = token

    def request(self, method, path, body=None, expected=(200,), parse_json=True):
        data = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
        headers = {"Accept": "application/json" if parse_json else "text/plain"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["X-Authorization"] = "Bearer " + self.token
        request = Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=30) as response:
                payload = response.read()
                if response.status not in expected:
                    raise ApiError(f"{method} {path} 返回 HTTP {response.status}")
        except HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:500]
            raise ApiError(f"{method} {path} 返回 HTTP {error.code}: {detail}") from error
        except URLError as error:
            raise ApiError(f"{method} {path} 无法连接: {error.reason}") from error
        if not payload:
            return None
        return json.loads(payload) if parse_json else payload.decode("utf-8")

    def get(self, path):
        return self.request("GET", path)

    def post(self, path, body):
        return self.request("POST", path, body, expected=(200, 201, 202))

    def delete(self, path):
        return self.request("DELETE", path, expected=(200, 204))


def stage(message):
    print(f"[WAN E2E] {message}", flush=True)


def wait_until(description, callback, timeout=WAIT_SECONDS, interval=1.0):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            value = callback()
            if value:
                return value
        except (ApiError, AssertionError, KeyError, TypeError) as error:
            last_error = error
        time.sleep(interval)
    suffix = f"，最后错误：{last_error}" if last_error else ""
    raise AssertionError(f"等待{description}超时（{timeout} 秒）{suffix}")


def entity_id(entity):
    return entity["id"]["id"]


def login():
    anonymous = HttpClient(TB_URL)
    response = wait_until("ThingsBoard 登录接口就绪", lambda: anonymous.post(
        "/api/auth/login", {"username": TB_USERNAME, "password": TB_PASSWORD}
    ))
    token = response.get("token")
    if not token:
        raise AssertionError("登录响应缺少 token")
    return HttpClient(TB_URL, token)


def connection_payload(suffix):
    return {
        "name": f"WAN 本地验收连接 {suffix}",
        "brokerHost": "mock-ns",
        "brokerPort": 1883,
        "tls": False,
        "clientId": f"wan-e2e-{suffix}",
        "username": "wan-e2e",
        "password": PASSWORD_CANARY,
        "nsPublishTopic": "turmass/ns/responses",
        "nsSubscribeTopic": "turmass/ns/requests",
        "qos": 1,
        "enabled": True,
        "requestTimeoutMs": 3000,
        "syncEnabled": True,
        "syncIntervalHours": 24,
    }


def profile_payload(name, connection_id):
    return {
        "name": name,
        "description": "WAN 本地黑盒验收",
        "default": False,
        "type": "DEFAULT",
        "transportType": "WAN",
        "profileData": {
            "configuration": {"type": "DEFAULT"},
            "transportConfiguration": {"type": "WAN", "connectionId": connection_id},
        },
    }


def gateway_payload(name, profile_id):
    return {
        "name": name,
        "type": "default",
        "deviceProfileId": {"id": profile_id, "entityType": "DEVICE_PROFILE"},
        "additionalInfo": {"gateway": True},
        "deviceData": {
            "configuration": {"type": "DEFAULT"},
            "transportConfiguration": {
                "type": "WAN",
                "deviceType": "GATEWAY",
                "gateway": {
                    "gwId": GATEWAY_ID,
                    "freqMajor": 1,
                    "freqMinor": 2,
                    "nwkNum": 3,
                    "tddNum": 4,
                    "rateNum": 1,
                    "rateCfgs": [{"rateMode": 0, "uplinkLen": 100, "downlinkLen": 120}],
                },
            },
        },
    }


def terminal_payload(name, profile_id):
    return {
        "name": name,
        "type": "default",
        "deviceProfileId": {"id": profile_id, "entityType": "DEVICE_PROFILE"},
        "additionalInfo": {"gateway": False},
        "deviceData": {
            "configuration": {"type": "DEFAULT"},
            "transportConfiguration": {
                "type": "WAN",
                "deviceType": "TERMINAL",
                "terminal": {
                    "devEui": TERMINAL_EUI,
                    "devType": 0,
                    "securityMode": 0,
                },
            },
        },
    }


def active_registry(client, device_id):
    registry = client.get(f"/api/wan/device/{device_id}/sync")
    status = registry.get("syncStatus")
    if status == "FAILED":
        raise AssertionError(f"设备 {device_id} 同步失败：{registry.get('error')}")
    return registry if status == "ACTIVE" else None


def ns_state(mock):
    return mock.get("/state")


def has_ns_device(mock, collection, external_id):
    field = "gw_id" if collection == "gateways" else "dev_eui"
    return any(item.get(field, "").upper() == external_id for item in ns_state(mock)[collection])


def telemetry_has(client, device_id, key, value):
    end = int(time.time() * 1000) + 60_000
    query = urlencode({"keys": "wanData,wanPort,rssi,snr", "startTs": 0, "endTs": end})
    telemetry = client.get(f"/api/plugins/telemetry/DEVICE/{device_id}/values/timeseries?{query}")
    samples = telemetry.get(key, [])
    return bool(samples) and str(samples[0].get("value")) == str(value)


def downlinks_received(mock):
    messages = ns_state(mock)["downlinks"]
    downlink = next((item for item in messages if item["operation"] == "push_downlink"), None)
    targeted = next((item for item in messages
                     if item["operation"] == "push_broadcast" and "gw_id" in item["body"]), None)
    network = next((item for item in messages
                    if item["operation"] == "push_broadcast" and "gw_id" not in item["body"]), None)
    if not (downlink and targeted and network):
        return None
    assert downlink["body"] == {"dev_eui": TERMINAL_EUI, "port": 3, "data": "05060708"}
    assert targeted["body"] == {"gw_id": GATEWAY_ID, "data": "01020304"}
    assert network["body"] == {"data": "0102030405"}
    return messages


def prometheus_value(text, name, labels=None):
    labels = labels or {}
    for line in text.splitlines():
        if line.startswith("#"):
            continue
        match = re.match(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+([^\s]+)$", line)
        if not match or match.group(1) != name:
            continue
        actual_labels = dict(re.findall(r'(\w+)="([^"]*)"', match.group(2) or ""))
        if all(actual_labels.get(key) == value for key, value in labels.items()):
            return float(match.group(3))
    raise AssertionError(f"指标 {name}{labels} 不存在")


def assert_metrics(metrics_client):
    text = metrics_client.request("GET", "/actuator/prometheus", parse_json=False)
    assert prometheus_value(text, "tb_wan_connections_active") >= 1
    assert prometheus_value(text, "tb_wan_ns_requests_total") >= 1
    assert prometheus_value(text, "tb_wan_ns_request_timeouts_total") >= 0
    assert prometheus_value(text, "tb_wan_synchronizations_total", {"result": "success"}) >= 1
    assert prometheus_value(text, "tb_wan_uplinks_total", {"result": "success"}) >= 1
    assert prometheus_value(text, "tb_wan_downlinks_total",
                            {"operation": "downlink", "result": "success"}) >= 1
    assert prometheus_value(text, "tb_wan_downlinks_total",
                            {"operation": "broadcast", "result": "success"}) >= 2


def assert_logs_redacted():
    command = ["docker", "compose", "-f", str(COMPOSE_FILE), "logs", "--no-color",
               "thingsboard", "wan-transport", "mock-ns"]
    result = subprocess.run(command, cwd=REPO_ROOT, check=True, capture_output=True, text=True)
    logs = result.stdout + result.stderr
    if PASSWORD_CANARY in logs or ROOT_KEY_CANARY in logs:
        raise AssertionError("容器日志泄漏了 WAN 密码或终端根密钥测试值")


def safe_delete(client, path):
    try:
        client.delete(path)
    except ApiError as error:
        if "HTTP 404" not in str(error):
            stage(f"清理警告：{error}")


def run():
    stage("等待模拟 NS 和 WAN 指标端点")
    mock = HttpClient(MOCK_NS_URL)
    metrics = HttpClient(WAN_METRICS_URL)
    wait_until("模拟 NS 健康", lambda: mock.get("/health").get("status") == "UP")
    wait_until("WAN Transport 健康", lambda: metrics.get("/actuator/health").get("status") == "UP")
    mock.post("/reset", {})
    mock.post("/seed", {
        "gateways": [{
            "gw_id": GATEWAY_ID,
            "freq_major": 5,
            "freq_minor": 6,
            "nwk_num": 7,
            "tdd_num": 8,
            "rate_num": 1,
            "rate_cfgs": [{"rate_mode": 4, "uplink_len": 300, "downlink_len": 301}],
            "description": "模拟 NS 已有网关",
        }],
        "terminals": [{
            "dev_eui": "000000000000DEAD",
            "security_mode": 1,
            "root_key": ROOT_KEY_CANARY,
        }],
    })

    stage("登录 ThingsBoard 并验证 NS 连接配置")
    client = login()
    suffix = uuid.uuid4().hex[:8]
    connection = None
    profile = None
    gateway = None
    terminal = None
    try:
        connection_request = connection_payload(suffix)
        test_result = client.post("/api/wan/connection/test", connection_request)
        assert test_result == {
            "success": True,
            "code": "CONNECTED",
            "message": "WAN NS broker connection succeeded",
        }
        connection = client.post("/api/wan/connection", connection_request)
        assert connection.get("password") == "********"
        assert connection.get("passwordSet") is True
        connection_id = connection["id"]
        profile = client.post("/api/deviceProfile", profile_payload(
            f"WAN 本地验收配置 {suffix}", connection_id
        ))
        profile_id = entity_id(profile)

        stage("验证已有网关以 NS 状态覆盖平台配置")
        gateway = client.post("/api/device", gateway_payload(f"WAN 验收网关 {suffix}", profile_id))
        gateway_id = entity_id(gateway)
        wait_until("已有网关同步为 ACTIVE", lambda: active_registry(client, gateway_id))
        gateway_detail = client.get(f"/api/device/{gateway_id}")
        synchronized_gateway = gateway_detail["deviceData"]["transportConfiguration"]["gateway"]
        assert synchronized_gateway["freqMajor"] == 5
        assert synchronized_gateway["freqMinor"] == 6

        stage("验证空查询后创建终端")
        terminal = client.post("/api/device", terminal_payload(f"WAN 验收终端 {suffix}", profile_id))
        terminal_id = entity_id(terminal)
        wait_until("新终端同步为 ACTIVE", lambda: active_registry(client, terminal_id))
        wait_until("模拟 NS 创建终端", lambda: has_ns_device(mock, "terminals", TERMINAL_EUI))

        stage("验证 MQTT 上行进入 ThingsBoard telemetry")
        uplink = mock.post("/uplink", {
            "dev_eui": TERMINAL_EUI,
            "rssi": -54,
            "snr": 18,
            "port": 0,
            "data": "01020304",
        })
        assert uplink["subscribers"] >= 1
        wait_until("wanData telemetry", lambda: telemetry_has(client, terminal_id, "wanData", "01020304"))
        assert telemetry_has(client, terminal_id, "wanPort", "0")
        assert telemetry_has(client, terminal_id, "rssi", "-54")
        assert telemetry_has(client, terminal_id, "snr", "18")

        stage("验证终端下行、定向广播和全网广播")
        downlink_response = client.post(f"/api/rpc/twoway/{terminal_id}", {
            "method": "wanDownlink", "params": {"port": 3, "data": "05060708"}, "timeout": 10_000,
        })
        targeted_response = client.post(f"/api/rpc/twoway/{gateway_id}", {
            "method": "wanBroadcast", "params": {"data": "01020304"}, "timeout": 10_000,
        })
        network_response = client.post(f"/api/rpc/twoway/{gateway_id}", {
            "method": "wanBroadcast",
            "params": {"broadcastAll": True, "data": "0102030405"},
            "timeout": 10_000,
        })
        for response in (downlink_response, targeted_response, network_response):
            assert response == {"success": True, "status": "SENT"}
        wait_until("模拟 NS 收到三类下行", lambda: downlinks_received(mock))

        stage("验证删除后重建终端")
        recreate = client.post(f"/api/wan/device/{terminal_id}/sync/recreate", {})
        assert recreate["syncStatus"] == "RECREATING"
        wait_until("终端重建为 ACTIVE", lambda: active_registry(client, terminal_id))
        operations = ns_state(mock)["operationCounts"]
        assert operations.get("delete_terminal", 0) >= 1
        assert operations.get("add_terminal", 0) >= 2

        stage("验证删除同步到模拟 NS")
        client.delete(f"/api/device/{terminal_id}")
        terminal = None
        client.delete(f"/api/device/{gateway_id}")
        gateway = None
        wait_until("模拟 NS 删除终端", lambda: not has_ns_device(mock, "terminals", TERMINAL_EUI))
        wait_until("模拟 NS 删除网关", lambda: not has_ns_device(mock, "gateways", GATEWAY_ID))

        stage("验证 WAN 运行指标与敏感日志保护")
        wait_until("WAN 指标更新", lambda: (assert_metrics(metrics) or True))
        assert_logs_redacted()
    finally:
        if terminal:
            safe_delete(client, f"/api/device/{entity_id(terminal)}")
        if gateway:
            safe_delete(client, f"/api/device/{entity_id(gateway)}")
        if profile:
            safe_delete(client, f"/api/deviceProfile/{entity_id(profile)}")
        if connection:
            safe_delete(client, f"/api/wan/connection/{connection['id']}")

    stage("全部主链路黑盒验收通过")


if __name__ == "__main__":
    try:
        run()
    except (AssertionError, ApiError) as error:
        print(f"[WAN E2E] 失败：{error}", file=sys.stderr, flush=True)
        sys.exit(1)
