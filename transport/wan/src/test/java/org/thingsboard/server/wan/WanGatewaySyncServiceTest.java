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
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanRateConfiguration;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanGatewaySyncServiceTest {

    private WanDeviceRegistryClient registryClient;
    private WanConnectionManager connectionManager;
    private WanNsRequestClient requestClient;
    private WanDeviceSyncService syncService;
    private UUID deviceId;
    private UUID connectionId;

    @BeforeEach
    void setUp() {
        registryClient = Mockito.mock(WanDeviceRegistryClient.class);
        connectionManager = Mockito.mock(WanConnectionManager.class);
        requestClient = Mockito.mock(WanNsRequestClient.class);
        syncService = new WanDeviceSyncService(registryClient, connectionManager, requestClient,
                new WanGatewayCommandFactory(), new WanTerminalCommandFactory());
        deviceId = UUID.randomUUID();
        connectionId = UUID.randomUUID();
        when(registryClient.claim(deviceId)).thenReturn(registry());
        when(connectionManager.hasConnection(connectionId)).thenReturn(true);
    }

    @Test
    void createsGatewayOnlyAfterSuccessfulEmptyQuery() {
        when(requestClient.execute(eq(connectionId), any()))
                .thenReturn(json("{\"rsp_code\":0,\"rsp_body\":[]}"),
                        json("{\"rsp_code\":[0],\"rsp_desc\":[\"网关添加成功\"]}"));

        syncService.synchronize(deviceId);

        InOrder order = Mockito.inOrder(registryClient);
        order.verify(registryClient).update(deviceId, WanDeviceSyncStatus.SYNCING, null, null);
        order.verify(registryClient).update(deviceId, WanDeviceSyncStatus.CREATING, null, null);
        order.verify(registryClient).update(deviceId, WanDeviceSyncStatus.ACTIVE, null, null);
        ArgumentCaptor<WanNsRequest> requestCaptor = ArgumentCaptor.forClass(WanNsRequest.class);
        verify(requestClient, Mockito.times(2)).execute(eq(connectionId), requestCaptor.capture());
        assertThat(requestCaptor.getAllValues()).extracting(WanNsRequest::operation)
                .containsExactly("get_gateway", "add_gateway");
        JsonNode addGateway = requestCaptor.getAllValues().get(1).body().get(0);
        assertThat(addGateway.get("gw_id").asText()).isEqualTo("8C3F74C81C703000");
        assertThat(addGateway.get("description").asText()).isEqualTo("Gateway One");
    }

    @Test
    void appliesNsConfigurationWhenGatewayExists() {
        when(requestClient.execute(eq(connectionId), any())).thenReturn(json("""
                {"rsp_code":0,"rsp_body":[{
                  "gw_id":"8C3F74C81C703000","freq_major":5,"freq_minor":6,
                  "nwk_num":7,"tdd_num":8,"rate_num":1,
                  "rate_cfgs":[{"rate_mode":4,"uplink_len":300,"downlink_len":301}]
                }]}
                """));

        syncService.synchronize(deviceId);

        ArgumentCaptor<WanGatewayConfiguration> configuration =
                ArgumentCaptor.forClass(WanGatewayConfiguration.class);
        verify(registryClient).update(eq(deviceId), eq(WanDeviceSyncStatus.ACTIVE),
                Mockito.isNull(), configuration.capture());
        assertThat(configuration.getValue().getFreqMajor()).isEqualTo(5);
        assertThat(configuration.getValue().getRateCfgs().get(0).getRateMode()).isEqualTo(4);
        verify(requestClient, Mockito.times(1)).execute(eq(connectionId), any());
    }

    @Test
    void neverCreatesOnBusinessFailure() {
        when(requestClient.execute(eq(connectionId), any()))
                .thenReturn(json("{\"rsp_code\":7,\"rsp_desc\":\"查询失败\",\"rsp_body\":[]}"));

        syncService.synchronize(deviceId);

        verify(registryClient).update(eq(deviceId), eq(WanDeviceSyncStatus.FAILED),
                Mockito.contains("查询失败"), Mockito.isNull());
        verify(registryClient, never()).update(eq(deviceId), eq(WanDeviceSyncStatus.CREATING),
                Mockito.any(), Mockito.any());
        verify(requestClient, Mockito.times(1)).execute(eq(connectionId), any());
    }

    private WanDeviceRegistrySnapshot registry() {
        return new WanDeviceRegistrySnapshot(deviceId, UUID.randomUUID(), connectionId,
                WanDeviceType.GATEWAY, "8C3F74C81C703000", "Gateway One",
                JacksonUtil.toString(deviceConfiguration()), WanDeviceSyncStatus.PENDING,
                null, null, null, 1);
    }

    private WanDeviceTransportConfiguration deviceConfiguration() {
        WanRateConfiguration rate = new WanRateConfiguration();
        rate.setRateMode(0);
        rate.setUplinkLen(100);
        rate.setDownlinkLen(120);
        WanGatewayConfiguration gateway = new WanGatewayConfiguration();
        gateway.setGwId("8C3F74C81C703000");
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

    private JsonNode json(String value) {
        return JacksonUtil.toJsonNode(value);
    }
}
