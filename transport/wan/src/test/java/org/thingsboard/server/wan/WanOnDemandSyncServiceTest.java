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
import org.mockito.Mockito;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.device.data.WanDeviceTransportConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanRateConfiguration;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanOnDemandSyncServiceTest {

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
        when(connectionManager.hasConnection(connectionId)).thenReturn(true);
    }

    @Test
    void marksPreviouslyActiveDeviceUnknownOnTechnicalFailureWithoutCreating() {
        when(registryClient.claim(deviceId)).thenReturn(registry(123L));
        when(requestClient.execute(eq(connectionId), any()))
                .thenThrow(new WanNsRequestException("WAN NS request timed out"));

        syncService.synchronize(deviceId);

        verify(registryClient).update(deviceId, WanDeviceSyncStatus.UNKNOWN,
                "WAN NS request timed out", null);
        verify(registryClient, never()).update(eq(deviceId), eq(WanDeviceSyncStatus.CREATING), any(), any());
    }

    @Test
    void marksFirstTechnicalFailureFailed() {
        when(registryClient.claim(deviceId)).thenReturn(registry(null));
        when(requestClient.execute(eq(connectionId), any()))
                .thenThrow(new WanNsRequestException("WAN NS response is malformed"));

        syncService.synchronize(deviceId);

        verify(registryClient).update(deviceId, WanDeviceSyncStatus.FAILED,
                "WAN NS response is malformed", null);
    }

    @Test
    void marksExplicitNsBusinessFailureFailedEvenAfterSuccessfulSync() {
        when(registryClient.claim(deviceId)).thenReturn(registry(123L));
        when(requestClient.execute(eq(connectionId), any())).thenReturn(JacksonUtil.toJsonNode(
                "{\"rsp_code\":7,\"rsp_desc\":\"gateway rejected\",\"rsp_body\":[]}"));

        syncService.synchronize(deviceId);

        verify(registryClient).update(deviceId, WanDeviceSyncStatus.FAILED,
                "NS get_gateway failed: gateway rejected", null);
        verify(registryClient, never()).update(eq(deviceId), eq(WanDeviceSyncStatus.CREATING), any(), any());
    }

    @Test
    void marksMismatchedResponseUnknownWithoutCreating() {
        when(registryClient.claim(deviceId)).thenReturn(registry(123L));
        when(requestClient.execute(eq(connectionId), any())).thenReturn(JacksonUtil.toJsonNode("""
                {"rsp_code":0,"rsp_body":[{
                  "gw_id":"8C3F74C81C703099","freq_major":5,"freq_minor":6,
                  "nwk_num":7,"tdd_num":8,"rate_num":1,
                  "rate_cfgs":[{"rate_mode":4,"uplink_len":300,"downlink_len":301}]
                }]}
                """));

        syncService.synchronize(deviceId);

        verify(registryClient).update(eq(deviceId), eq(WanDeviceSyncStatus.UNKNOWN),
                Mockito.contains("does not match"), Mockito.isNull());
        verify(registryClient, never()).update(eq(deviceId), eq(WanDeviceSyncStatus.CREATING), any(), any());
    }

    @Test
    void releasesTheLeaseWhenAConcurrentDeletionRejectsTheFailureUpdate() {
        when(registryClient.claim(deviceId)).thenReturn(registry(123L));
        when(requestClient.execute(eq(connectionId), any()))
                .thenThrow(new WanNsRequestException("WAN NS request timed out"));
        Mockito.doThrow(new IllegalArgumentException("deletion tombstone"))
                .when(registryClient).update(deviceId, WanDeviceSyncStatus.UNKNOWN,
                        "WAN NS request timed out", null);

        syncService.synchronize(deviceId);

        verify(registryClient).release(deviceId);
    }

    @Test
    void mergesConcurrentRequestsIntoOneNsOperation() throws Exception {
        when(registryClient.claim(deviceId)).thenReturn(registry(123L));
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        when(requestClient.execute(eq(connectionId), any())).thenAnswer(invocation -> {
            requestStarted.countDown();
            assertThat(releaseResponse.await(5, TimeUnit.SECONDS)).isTrue();
            return JacksonUtil.toJsonNode(existingGatewayResponse());
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> syncService.synchronize(deviceId));
            assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> syncService.synchronize(deviceId));
            second.get(5, TimeUnit.SECONDS);
            releaseResponse.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            releaseResponse.countDown();
            executor.shutdownNow();
        }

        verify(registryClient, Mockito.times(1)).claim(deviceId);
        verify(requestClient, Mockito.times(1)).execute(eq(connectionId), any());
        assertThat(syncService.inFlightCount()).isZero();
    }

    private WanDeviceRegistrySnapshot registry(Long lastSuccessfulSyncTime) {
        return new WanDeviceRegistrySnapshot(deviceId, UUID.randomUUID(), connectionId,
                WanDeviceType.GATEWAY, "8C3F74C81C703000", "Gateway One",
                JacksonUtil.toString(deviceConfiguration()), WanDeviceSyncStatus.PENDING,
                100L, null, null, 1L, null, null, lastSuccessfulSyncTime);
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

    private String existingGatewayResponse() {
        return """
                {"rsp_code":0,"rsp_body":[{
                  "gw_id":"8C3F74C81C703000","freq_major":5,"freq_minor":6,
                  "nwk_num":7,"tdd_num":8,"rate_num":1,
                  "rate_cfgs":[{"rate_mode":4,"uplink_len":300,"downlink_len":301}]
                }]}
                """;
    }
}
