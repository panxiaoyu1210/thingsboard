/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.wan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.rpc.RpcStatus;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanValidation;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.gen.transport.TransportProtos;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "transport.wan", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WanDownlinkService {

    static final String DOWNLINK_METHOD = "wanDownlink";
    static final String BROADCAST_METHOD = "wanBroadcast";
    private static final int DOWNLINK_QOS = 1;

    private final WanConnectionManager connectionManager;
    private final TransportService transportService;
    private final Clock clock;
    private final ConcurrentMap<UUID, AtomicInteger> requestIds = new ConcurrentHashMap<>();
    private WanTransportMetrics metrics = WanTransportMetrics.noop();

    @Autowired(required = false)
    void setMetrics(WanTransportMetrics metrics) {
        this.metrics = metrics;
    }

    public void handle(WanDeviceDescriptor device, TransportProtos.SessionInfoProto sessionInfo,
                       TransportProtos.ToDeviceRpcRequestMsg request) {
        ObjectNode payload;
        long now = clock.millis();
        try {
            if (request.getExpirationTime() > 0 && request.getExpirationTime() <= now) {
                throw new WanDownlinkException("WAN RPC request has expired");
            }
            payload = command(device, request);
            long remainingTime = request.getExpirationTime() > 0
                    ? request.getExpirationTime() - now : Long.MAX_VALUE;
            connectionManager.publish(device.connectionId(),
                    JacksonUtil.toString(payload).getBytes(StandardCharsets.UTF_8),
                    DOWNLINK_QOS, remainingTime);
        } catch (Exception e) {
            metrics.recordDownlink(request.getMethodName(), false);
            reportFailure(device, sessionInfo, request, e);
            return;
        }
        metrics.recordDownlink(request.getMethodName(), true);
        try {
            transportService.process(sessionInfo, request, RpcStatus.SENT, TransportServiceCallback.EMPTY);
        } catch (RuntimeException e) {
            log.warn("Unable to report SENT status for WAN RPC [{}] on device [{}]",
                    request.getMethodName(), device.deviceId(), e);
        }
        if (!request.getOneway()) {
            respond(sessionInfo, request.getRequestId(), true, RpcStatus.SENT, null);
        }
    }

    private void reportFailure(WanDeviceDescriptor device,
                               TransportProtos.SessionInfoProto sessionInfo,
                               TransportProtos.ToDeviceRpcRequestMsg request,
                               Exception exception) {
        String error = exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
        log.warn("WAN RPC [{}] failed for device [{}]: {}",
                request.getMethodName(), device.deviceId(), error);
        if (request.getOneway()) {
            try {
                transportService.process(sessionInfo, request, RpcStatus.FAILED, TransportServiceCallback.EMPTY);
            } catch (RuntimeException statusError) {
                log.warn("Unable to report FAILED status for WAN RPC [{}] on device [{}]",
                        request.getMethodName(), device.deviceId(), statusError);
            }
        } else {
            respond(sessionInfo, request.getRequestId(), false, RpcStatus.FAILED, error);
        }
    }

    private ObjectNode command(WanDeviceDescriptor device,
                               TransportProtos.ToDeviceRpcRequestMsg request) {
        JsonNode params;
        try {
            params = JacksonUtil.toJsonNode(request.getParams());
        } catch (RuntimeException e) {
            throw new WanDownlinkException("WAN RPC params must be valid JSON");
        }
        if (params == null || !params.isObject()) {
            throw new WanDownlinkException("WAN RPC params must be an object");
        }
        ObjectNode body = JacksonUtil.newObjectNode();
        String operation;
        if (DOWNLINK_METHOD.equals(request.getMethodName())) {
            requireRole(device, WanDeviceType.TERMINAL, false);
            operation = "push_downlink";
            body.put("dev_eui", device.externalId());
            body.put("port", requiredPort(params));
            body.put("data", requiredHexData(params, false));
        } else if (BROADCAST_METHOD.equals(request.getMethodName())) {
            requireRole(device, WanDeviceType.GATEWAY, true);
            operation = "push_broadcast";
            boolean broadcastAll = optionalBoolean(params, "broadcastAll", false);
            if (!broadcastAll) {
                body.put("gw_id", device.externalId());
            }
            body.put("data", requiredHexData(params, true));
        } else {
            throw new WanDownlinkException("Unsupported WAN RPC method: " + request.getMethodName());
        }
        ObjectNode result = JacksonUtil.newObjectNode();
        result.put("req_id", nextRequestId(device.connectionId()));
        result.put("req_opt", operation);
        result.set("req_body", body);
        return result;
    }

    private void requireRole(WanDeviceDescriptor device, WanDeviceType role, boolean gateway) {
        if (device.wanDeviceType() != role || device.gateway() != gateway) {
            throw new WanDownlinkException("WAN RPC method does not match target device role");
        }
    }

    private int requiredPort(JsonNode params) {
        JsonNode port = params.get("port");
        if (port == null || !port.isIntegralNumber() || !port.canConvertToInt()
                || !WanValidation.isInRange(port.intValue(), 0, 255)) {
            throw new WanDownlinkException("WAN downlink port must be an integer between 0 and 255");
        }
        return port.intValue();
    }

    private String requiredHexData(JsonNode params, boolean broadcast) {
        JsonNode data = params.get("data");
        if (data == null || !data.isTextual() || data.textValue().isBlank()) {
            throw new WanDownlinkException("WAN RPC data must be a non-empty hexadecimal string");
        }
        String value = data.textValue().trim();
        if ((value.length() & 1) != 0 || !WanValidation.isHex(value, value.length())) {
            throw new WanDownlinkException("WAN RPC data must contain an even number of hexadecimal characters");
        }
        if (broadcast && value.length() > 70) {
            throw new WanDownlinkException("WAN broadcast data must not exceed 70 hexadecimal characters");
        }
        return value;
    }

    private boolean optionalBoolean(JsonNode params, String field, boolean defaultValue) {
        JsonNode value = params.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new WanDownlinkException("WAN RPC " + field + " must be a boolean");
        }
        return value.booleanValue();
    }

    private int nextRequestId(UUID connectionId) {
        return requestIds.computeIfAbsent(connectionId, ignored -> new AtomicInteger())
                .updateAndGet(value -> value == Integer.MAX_VALUE ? 1 : value + 1);
    }

    private void respond(TransportProtos.SessionInfoProto sessionInfo, int requestId,
                         boolean success, RpcStatus status, String error) {
        ObjectNode response = JacksonUtil.newObjectNode();
        response.put("success", success);
        response.put("status", status.name());
        TransportProtos.ToDeviceRpcResponseMsg.Builder message =
                TransportProtos.ToDeviceRpcResponseMsg.newBuilder().setRequestId(requestId);
        if (error == null) {
            message.setPayload(JacksonUtil.toString(response));
        } else {
            response.put("error", error);
            message.setError(JacksonUtil.toString(response));
        }
        try {
            transportService.process(sessionInfo, message.build(), TransportServiceCallback.EMPTY);
        } catch (RuntimeException responseError) {
            log.warn("Unable to report WAN RPC result for request [{}]", requestId, responseError);
        }
    }

}
