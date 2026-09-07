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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.device.data.WanDeviceTransportConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanRateConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanTerminalConfiguration;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanRecreateServiceTest {

    private static final String OLD_ID = "8C3F74C81C703000";
    private static final String NEW_ID = "8C3F74C81C703001";

    private WanDeviceRegistryClient registryClient;
    private WanNsRequestClient requestClient;
    private WanDeviceSyncService service;
    private UUID deviceId;
    private UUID connectionId;
    private UUID oldConnectionId;

    @BeforeEach
    void setUp() {
        registryClient = Mockito.mock(WanDeviceRegistryClient.class);
        WanConnectionManager connectionManager = Mockito.mock(WanConnectionManager.class);
        requestClient = Mockito.mock(WanNsRequestClient.class);
        service = new WanDeviceSyncService(registryClient, connectionManager, requestClient,
                new WanGatewayCommandFactory(), new WanTerminalCommandFactory());
        deviceId = UUID.randomUUID();
        connectionId = UUID.randomUUID();
        oldConnectionId = UUID.randomUUID();
        when(connectionManager.hasConnection(connectionId)).thenReturn(true);
        when(registryClient.claim(deviceId)).thenReturn(registry());
    }

    @Test
    void deletesOldAndPartiallyCreatedDestinationBeforeActivatingRecreatedGateway() {
        when(requestClient.execute(any(), any())).thenReturn(
                json("{\"rsp_code\":0,\"rsp_body\":[{\"gw_id\":\"" + OLD_ID + "\"}]}"),
                json("{\"rsp_code\":0,\"rsp_desc\":\"deleted\"}"),
                json(existingGateway(NEW_ID)),
                json("{\"rsp_code\":0,\"rsp_desc\":\"deleted\"}"),
                json("{\"rsp_code\":[0],\"rsp_desc\":[\"added\"]}"),
                json(existingGateway(NEW_ID)));

        service.synchronize(deviceId);

        ArgumentCaptor<WanNsRequest> requests = ArgumentCaptor.forClass(WanNsRequest.class);
        verify(requestClient, Mockito.times(6)).execute(any(), requests.capture());
        assertThat(requests.getAllValues()).extracting(WanNsRequest::operation)
                .containsExactly("get_gateway", "delete_gateway", "get_gateway", "delete_gateway",
                        "add_gateway", "get_gateway");
        assertThat(requests.getAllValues().get(1).body().path("gw_ids").path(0).asText()).isEqualTo(OLD_ID);
        assertThat(requests.getAllValues().get(3).body().path("gw_ids").path(0).asText()).isEqualTo(NEW_ID);
        assertThat(requests.getAllValues().get(4).body().path(0).path("gw_id").asText()).isEqualTo(NEW_ID);
        verify(registryClient).update(eq(deviceId), eq(WanDeviceSyncStatus.ACTIVE),
                Mockito.isNull(), any(WanGatewayConfiguration.class));
    }

    @Test
    void exposesMissingNsRiskWhenAddFailsAfterDelete() {
        when(requestClient.execute(any(), any())).thenReturn(
                json("{\"rsp_code\":0,\"rsp_body\":[{\"gw_id\":\"" + OLD_ID + "\"}]}"),
                json("{\"rsp_code\":0}"),
                json("{\"rsp_code\":0,\"rsp_body\":[]}"),
                json("{\"rsp_code\":[9],\"rsp_desc\":[\"add rejected\"]}"));

        service.synchronize(deviceId);

        verify(registryClient).update(eq(deviceId), eq(WanDeviceSyncStatus.FAILED),
                Mockito.contains("was deleted"), Mockito.isNull());
        verify(registryClient).update(eq(deviceId), eq(WanDeviceSyncStatus.FAILED),
                Mockito.contains("add rejected"), Mockito.isNull());
    }

    @Test
    void recreatesTerminalWithProtectedPlatformParameters() {
        String rootKey = "0102030405060708090A0B0C0D0E0F10";
        String gatewayId = "8C3F74C81C703010";
        WanTerminalConfiguration terminal = new WanTerminalConfiguration();
        terminal.setDevEui("0000000000001001");
        terminal.setDevType(1);
        terminal.setSecurityMode(5);
        WanDeviceTransportConfiguration deviceConfiguration = new WanDeviceTransportConfiguration();
        deviceConfiguration.setDeviceType(WanDeviceType.TERMINAL);
        deviceConfiguration.setTerminal(terminal);
        WanDeviceRegistrySnapshot terminalRegistry = new WanDeviceRegistrySnapshot(
                deviceId, UUID.randomUUID(), connectionId, WanDeviceType.TERMINAL,
                terminal.getDevEui(), "Terminal Recreated", JacksonUtil.toString(deviceConfiguration),
                WanDeviceSyncStatus.RECREATING, 1L, null, null, 1L,
                gatewayId, rootKey, 1L, oldConnectionId, terminal.getDevEui(), 0);
        when(registryClient.claim(deviceId)).thenReturn(terminalRegistry);
        when(requestClient.execute(any(), any())).thenReturn(
                json("{\"rsp_code\":0,\"rsp_body\":[{\"dev_eui\":\"0000000000001001\"}]}"),
                json("{\"rsp_code\":0}"),
                json("{\"rsp_code\":0,\"rsp_body\":[]}"),
                json("{\"rsp_code\":[0]}"),
                json("{\"rsp_code\":0,\"rsp_body\":[{\"dev_eui\":\"0000000000001001\","
                        + "\"dev_type\":1,\"security_mode\":5,\"root_key\":\"" + rootKey
                        + "\",\"related_id\":\"" + gatewayId + "\",\"description\":\"Terminal\"}]}"));

        service.synchronize(deviceId);

        ArgumentCaptor<WanNsRequest> requests = ArgumentCaptor.forClass(WanNsRequest.class);
        verify(requestClient, Mockito.times(5)).execute(any(), requests.capture());
        assertThat(requests.getAllValues()).extracting(WanNsRequest::operation)
                .containsExactly("get_terminal", "delete_terminal", "get_terminal", "add_terminal", "get_terminal");
        JsonNode add = requests.getAllValues().get(3).body().path(0);
        assertThat(add.path("root_key").asText()).isEqualTo(rootKey);
        assertThat(add.path("related_id").asText()).isEqualTo(gatewayId);
        verify(registryClient).updateTerminal(eq(deviceId), eq(WanDeviceSyncStatus.ACTIVE),
                any(WanTerminalConfiguration.class), eq(rootKey), eq(gatewayId));
    }

    private WanDeviceRegistrySnapshot registry() {
        return new WanDeviceRegistrySnapshot(deviceId, UUID.randomUUID(), connectionId,
                WanDeviceType.GATEWAY, NEW_ID, "Gateway Recreated",
                JacksonUtil.toString(deviceConfiguration()), WanDeviceSyncStatus.RECREATING,
                1L, null, null, 1L, null, null, 1L, oldConnectionId, OLD_ID, 0);
    }

    private WanDeviceTransportConfiguration deviceConfiguration() {
        WanRateConfiguration rate = new WanRateConfiguration();
        rate.setRateMode(0);
        rate.setUplinkLen(100);
        rate.setDownlinkLen(120);
        WanGatewayConfiguration gateway = new WanGatewayConfiguration();
        gateway.setGwId(NEW_ID);
        gateway.setFreqMajor(1);
        gateway.setFreqMinor(2);
        gateway.setNwkNum(3);
        gateway.setTddNum(4);
        gateway.setRateNum(1);
        gateway.setRateCfgs(List.of(rate));
        WanDeviceTransportConfiguration configuration = new WanDeviceTransportConfiguration();
        configuration.setDeviceType(WanDeviceType.GATEWAY);
        configuration.setGateway(gateway);
        return configuration;
    }

    private String existingGateway(String gatewayId) {
        return "{\"rsp_code\":0,\"rsp_body\":[{\"gw_id\":\"" + gatewayId
                + "\",\"freq_major\":1,\"freq_minor\":2,\"nwk_num\":3,\"tdd_num\":4,"
                + "\"rate_num\":1,\"rate_cfgs\":[{\"rate_mode\":0,\"uplink_len\":100,"
                + "\"downlink_len\":120}]}]}";
    }

    private JsonNode json(String value) {
        return JacksonUtil.toJsonNode(value);
    }
}
