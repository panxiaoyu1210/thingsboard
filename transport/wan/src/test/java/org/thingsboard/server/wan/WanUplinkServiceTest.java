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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanUplinkServiceTest {

    private WanDeviceRouteRegistry deviceRouteRegistry;
    private TransportService transportService;
    private WanUplinkService service;
    private UUID connectionId;

    @BeforeEach
    void setUp() {
        deviceRouteRegistry = Mockito.mock(WanDeviceRouteRegistry.class);
        transportService = Mockito.mock(TransportService.class);
        TbServiceInfoProvider serviceInfoProvider = Mockito.mock(TbServiceInfoProvider.class);
        when(serviceInfoProvider.getServiceId()).thenReturn("wan-uplink-test");
        service = new WanUplinkService(new WanUplinkMessageParser(), deviceRouteRegistry,
                transportService, serviceInfoProvider,
                Clock.fixed(Instant.ofEpochMilli(1_234_567L), ZoneOffset.UTC));
        connectionId = UUID.randomUUID();
    }

    @Test
    void routesTerminalAndWritesCanonicalTelemetry() {
        WanDeviceDescriptor device = descriptor(WanDeviceType.TERMINAL, false);
        when(deviceRouteRegistry.resolve(connectionId, "0000000000001002")).thenReturn(device);

        assertThat(service.onMessage(connectionId, message())).isTrue();

        ArgumentCaptor<TransportProtos.SessionInfoProto> sessionCaptor =
                ArgumentCaptor.forClass(TransportProtos.SessionInfoProto.class);
        ArgumentCaptor<TransportProtos.PostTelemetryMsg> telemetryCaptor =
                ArgumentCaptor.forClass(TransportProtos.PostTelemetryMsg.class);
        verify(transportService).process(sessionCaptor.capture(), telemetryCaptor.capture(),
                Mockito.<TransportServiceCallback<Void>>any());
        TransportProtos.SessionInfoProto session = sessionCaptor.getValue();
        assertThat(new UUID(session.getDeviceIdMSB(), session.getDeviceIdLSB())).isEqualTo(device.deviceId());
        assertThat(new UUID(session.getTenantIdMSB(), session.getTenantIdLSB())).isEqualTo(device.tenantId());
        assertThat(new UUID(session.getDeviceProfileIdMSB(), session.getDeviceProfileIdLSB()))
                .isEqualTo(device.deviceProfileId());
        assertThat(session.getDeviceName()).isEqualTo("Terminal One");

        TransportProtos.TsKvListProto values = telemetryCaptor.getValue().getTsKvList(0);
        assertThat(values.getTs()).isEqualTo(1_234_567L);
        Map<String, TransportProtos.KeyValueProto> telemetry = values.getKvList().stream()
                .collect(Collectors.toMap(TransportProtos.KeyValueProto::getKey, value -> value));
        assertThat(telemetry.get("wanData").getStringV()).isEqualTo("01020304");
        assertThat(telemetry.get("wanPort").getLongV()).isZero();
        assertThat(telemetry.get("rssi").getLongV()).isEqualTo(-54);
        assertThat(telemetry.get("snr").getLongV()).isEqualTo(18);
    }

    @Test
    void rejectsUnknownAmbiguousOrGatewayTargetsWithoutWritingTelemetry() {
        when(deviceRouteRegistry.resolve(connectionId, "0000000000001002"))
                .thenThrow(new WanUplinkException("unknown"));
        assertThatThrownBy(() -> service.onMessage(connectionId, message()))
                .isInstanceOf(WanUplinkException.class)
                .hasMessage("unknown");

        doReturn(descriptor(WanDeviceType.GATEWAY, true)).when(deviceRouteRegistry)
                .resolve(connectionId, "0000000000001002");
        assertThatThrownBy(() -> service.onMessage(connectionId, message()))
                .isInstanceOf(WanUplinkException.class)
                .hasMessageContaining("terminal");
        verify(transportService, never()).process(any(), any(TransportProtos.PostTelemetryMsg.class), any());
    }

    private com.fasterxml.jackson.databind.JsonNode message() {
        return JacksonUtil.toJsonNode("""
                {"req_id":1,"req_opt":"push_uplink","req_body":{
                  "dev_eui":"0000000000001002","rssi":-54,"snr":18,"port":0,"data":"01020304"
                }}
                """);
    }

    private WanDeviceDescriptor descriptor(WanDeviceType type, boolean gateway) {
        return new WanDeviceDescriptor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), connectionId, "Terminal One", "default", gateway,
                type, "0000000000001002", new byte[]{1});
    }

}
