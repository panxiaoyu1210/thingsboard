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
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.device.data.WanDeviceTransportConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanTerminalConfiguration;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanTerminalSyncServiceTest {

    private static final String DEVICE_EUI = "0000000000001001";
    private static final String ROOT_KEY = "0102030405060708090A0B0C0D0E0F10";
    private static final String GATEWAY_ID = "8C3F74C81C703000";

    private WanDeviceRegistryClient registryClient;
    private WanNsRequestClient requestClient;
    private WanDeviceSyncService syncService;
    private UUID deviceId;
    private UUID connectionId;

    @BeforeEach
    void setUp() {
        registryClient = Mockito.mock(WanDeviceRegistryClient.class);
        WanConnectionManager connectionManager = Mockito.mock(WanConnectionManager.class);
        requestClient = Mockito.mock(WanNsRequestClient.class);
        syncService = new WanDeviceSyncService(registryClient, connectionManager, requestClient,
                new WanGatewayCommandFactory(), new WanTerminalCommandFactory());
        deviceId = UUID.randomUUID();
        connectionId = UUID.randomUUID();
        when(registryClient.get(deviceId)).thenReturn(registry());
        when(connectionManager.hasConnection(connectionId)).thenReturn(true);
    }

    @Test
    void createsMissingTerminalWithResolvedGatewayAndProtectedCredentials() {
        when(requestClient.execute(eq(connectionId), any()))
                .thenReturn(json("{\"rsp_code\":0,\"rsp_body\":[]}"),
                        json("{\"rsp_code\":[0],\"rsp_desc\":[\"终端添加成功\"]}"));

        syncService.synchronize(deviceId);

        InOrder order = Mockito.inOrder(registryClient);
        order.verify(registryClient).update(deviceId, WanDeviceSyncStatus.SYNCING, null, null);
        order.verify(registryClient).update(deviceId, WanDeviceSyncStatus.CREATING, null, null);
        order.verify(registryClient).update(deviceId, WanDeviceSyncStatus.ACTIVE, null, null);
        ArgumentCaptor<WanNsRequest> requestCaptor = ArgumentCaptor.forClass(WanNsRequest.class);
        verify(requestClient, Mockito.times(2)).execute(eq(connectionId), requestCaptor.capture());
        assertThat(requestCaptor.getAllValues()).extracting(WanNsRequest::operation)
                .containsExactly("get_terminal", "add_terminal");
        JsonNode add = requestCaptor.getAllValues().get(1).body().path(0);
        assertThat(add.path("root_key").asText()).isEqualTo(ROOT_KEY);
        assertThat(add.path("related_id").asText()).isEqualTo(GATEWAY_ID);
    }

    @Test
    void adoptsExistingNsTerminalConfiguration() {
        when(requestClient.execute(eq(connectionId), any())).thenReturn(json("""
                {"rsp_code":0,"rsp_body":[{"dev_eui":"0000000000001001","dev_type":0,
                 "addr_mode":1,"nwk_id":"0001","nwk_addr":"1001","security_mode":4,
                 "root_key":"11111111111111111111111111111111",
                 "related_id":"8C3F74C81C703000","description":"NS Terminal"}]}
                """));

        syncService.synchronize(deviceId);

        ArgumentCaptor<WanTerminalConfiguration> configuration =
                ArgumentCaptor.forClass(WanTerminalConfiguration.class);
        verify(registryClient).updateTerminal(eq(deviceId), eq(WanDeviceSyncStatus.ACTIVE),
                configuration.capture(), eq("11111111111111111111111111111111"), eq(GATEWAY_ID));
        assertThat(configuration.getValue().getDevType()).isZero();
        assertThat(configuration.getValue().getSecurityMode()).isEqualTo(4);
        verify(registryClient, never()).update(eq(deviceId), eq(WanDeviceSyncStatus.CREATING),
                any(), any());
    }

    @Test
    void failsWithoutCreatingWhenQueryIsNotExplicitlySuccessfulAndEmpty() {
        when(requestClient.execute(eq(connectionId), any()))
                .thenReturn(json("{\"rsp_code\":7,\"rsp_desc\":\"NS unavailable\",\"rsp_body\":[]}"));

        syncService.synchronize(deviceId);

        verify(registryClient).update(eq(deviceId), eq(WanDeviceSyncStatus.FAILED),
                Mockito.contains("NS unavailable"), Mockito.isNull());
        verify(registryClient, never()).update(eq(deviceId), eq(WanDeviceSyncStatus.CREATING),
                any(), any());
        verify(requestClient, Mockito.times(1)).execute(eq(connectionId), any());
    }

    private WanDeviceRegistrySnapshot registry() {
        WanDeviceTransportConfiguration configuration = new WanDeviceTransportConfiguration();
        configuration.setDeviceType(WanDeviceType.TERMINAL);
        configuration.setTerminal(terminal());
        return new WanDeviceRegistrySnapshot(deviceId, UUID.randomUUID(), connectionId,
                WanDeviceType.TERMINAL, DEVICE_EUI, "Terminal One", JacksonUtil.toString(configuration),
                WanDeviceSyncStatus.PENDING, null, null, null, 1L, GATEWAY_ID, ROOT_KEY);
    }

    private WanTerminalConfiguration terminal() {
        WanTerminalConfiguration configuration = new WanTerminalConfiguration();
        configuration.setDevEui(DEVICE_EUI);
        configuration.setDevType(1);
        configuration.setSecurityMode(5);
        return configuration;
    }

    private JsonNode json(String value) {
        return JacksonUtil.toJsonNode(value);
    }
}
