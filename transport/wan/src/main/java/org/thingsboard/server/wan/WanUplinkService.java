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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.gen.transport.TransportProtos;

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
    private final WanSessionInfoFactory sessionInfoFactory;
    private final Clock clock;
    private WanTransportMetrics metrics = WanTransportMetrics.noop();

    @Autowired(required = false)
    void setMetrics(WanTransportMetrics metrics) {
        this.metrics = metrics;
    }

    public boolean onMessage(UUID connectionId, JsonNode messageNode) {
        if (!parser.supports(messageNode)) {
            return false;
        }
        try {
            WanUplinkMessage message = parser.parse(messageNode);
            WanDeviceDescriptor device = deviceRouteRegistry.resolve(connectionId, message.deviceEui());
            validateTerminal(device);
            TransportProtos.SessionInfoProto sessionInfo = sessionInfoFactory.create(device, UUID.randomUUID());
            TransportProtos.PostTelemetryMsg telemetry = telemetry(message);
            transportService.process(sessionInfo, telemetry, new TransportServiceCallback<>() {
                @Override
                public void onSuccess(Void ignored) {
                    metrics.recordUplink(true);
                    log.debug("WAN uplink [{}] accepted for device [{}]", message.requestId(), device.deviceId());
                }

                @Override
                public void onError(Throwable error) {
                    metrics.recordUplink(false);
                    log.warn("WAN uplink [{}] failed for device [{}]",
                            message.requestId(), device.deviceId(), error);
                }
            });
        } catch (RuntimeException e) {
            metrics.recordUplink(false);
            throw e;
        }
        return true;
    }

    private void validateTerminal(WanDeviceDescriptor device) {
        if (device.wanDeviceType() != WanDeviceType.TERMINAL || device.gateway()) {
            throw new WanUplinkException("WAN push_uplink target must be a terminal");
        }
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
