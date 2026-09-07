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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;

import java.time.Clock;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "transport.wan", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WanUplinkService {

    private final WanUplinkMessageParser parser;
    private final WanDeviceRouteRegistry deviceRouteRegistry;
    private final TransportService transportService;
    private final TbServiceInfoProvider serviceInfoProvider;
    private final Clock clock;

    public boolean onMessage(UUID connectionId, JsonNode messageNode) {
        if (!parser.supports(messageNode)) {
            return false;
        }
        WanUplinkMessage message = parser.parse(messageNode);
        WanDeviceDescriptor device = deviceRouteRegistry.resolve(connectionId, message.deviceEui());
        validateTerminal(device);
        TransportProtos.SessionInfoProto sessionInfo = sessionInfo(device);
        TransportProtos.PostTelemetryMsg telemetry = telemetry(message);
        transportService.process(sessionInfo, telemetry, new TransportServiceCallback<>() {
            @Override
            public void onSuccess(Void ignored) {
                log.debug("WAN uplink [{}] accepted for device [{}]", message.requestId(), device.deviceId());
            }

            @Override
            public void onError(Throwable error) {
                log.warn("WAN uplink [{}] failed for device [{}]",
                        message.requestId(), device.deviceId(), error);
            }
        });
        return true;
    }

    private void validateTerminal(WanDeviceDescriptor device) {
        if (device.wanDeviceType() != WanDeviceType.TERMINAL || device.gateway()) {
            throw new WanUplinkException("WAN push_uplink target must be a terminal");
        }
        if (device.tenantId() == null || device.customerId() == null
                || device.deviceProfileId() == null || device.deviceName() == null
                || device.deviceType() == null) {
            throw new WanUplinkException("WAN terminal routing information is incomplete");
        }
    }

    private TransportProtos.SessionInfoProto sessionInfo(WanDeviceDescriptor device) {
        UUID sessionId = UUID.randomUUID();
        return TransportProtos.SessionInfoProto.newBuilder()
                .setNodeId(serviceInfoProvider.getServiceId())
                .setSessionIdMSB(sessionId.getMostSignificantBits())
                .setSessionIdLSB(sessionId.getLeastSignificantBits())
                .setTenantIdMSB(device.tenantId().getMostSignificantBits())
                .setTenantIdLSB(device.tenantId().getLeastSignificantBits())
                .setDeviceIdMSB(device.deviceId().getMostSignificantBits())
                .setDeviceIdLSB(device.deviceId().getLeastSignificantBits())
                .setDeviceName(device.deviceName())
                .setDeviceType(device.deviceType())
                .setDeviceProfileIdMSB(device.deviceProfileId().getMostSignificantBits())
                .setDeviceProfileIdLSB(device.deviceProfileId().getLeastSignificantBits())
                .setCustomerIdMSB(device.customerId().getMostSignificantBits())
                .setCustomerIdLSB(device.customerId().getLeastSignificantBits())
                .build();
    }

    private TransportProtos.PostTelemetryMsg telemetry(WanUplinkMessage message) {
        TransportProtos.TsKvListProto.Builder values = TransportProtos.TsKvListProto.newBuilder()
                .setTs(clock.millis())
                .addKv(stringValue("wanData", message.data()))
                .addKv(longValue("wanPort", message.port()))
                .addKv(longValue("rssi", message.rssi()))
                .addKv(longValue("snr", message.snr()));
        return TransportProtos.PostTelemetryMsg.newBuilder().addTsKvList(values).build();
    }

    private TransportProtos.KeyValueProto stringValue(String key, String value) {
        return TransportProtos.KeyValueProto.newBuilder()
                .setKey(key)
                .setType(TransportProtos.KeyValueType.STRING_V)
                .setStringV(value)
                .build();
    }

    private TransportProtos.KeyValueProto longValue(String key, long value) {
        return TransportProtos.KeyValueProto.newBuilder()
                .setKey(key)
                .setType(TransportProtos.KeyValueType.LONG_V)
                .setLongV(value)
                .build();
    }

}
