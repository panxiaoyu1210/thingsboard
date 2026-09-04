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
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanDeletionServiceTest {

    private static final String DEVICE_EUI = "0000000000001001";

    private WanDeviceRegistryClient registryClient;
    private WanNsRequestClient requestClient;
    private WanDeviceSyncService service;
    private UUID deviceId;
    private UUID connectionId;

    @BeforeEach
    void setUp() {
        registryClient = Mockito.mock(WanDeviceRegistryClient.class);
        WanConnectionManager connectionManager = Mockito.mock(WanConnectionManager.class);
        requestClient = Mockito.mock(WanNsRequestClient.class);
        service = new WanDeviceSyncService(registryClient, connectionManager, requestClient,
                new WanGatewayCommandFactory(), new WanTerminalCommandFactory());
        deviceId = UUID.randomUUID();
        connectionId = UUID.randomUUID();
        when(connectionManager.hasConnection(connectionId)).thenReturn(true);
    }

    @Test
    void deletesExistingTerminalAndCompletesTombstone() {
        when(registryClient.get(deviceId)).thenReturn(registry(0));
        when(requestClient.execute(eq(connectionId), any())).thenReturn(
                JacksonUtil.toJsonNode("{\"rsp_code\":0,\"rsp_body\":[{\"dev_eui\":\"" + DEVICE_EUI + "\"}]}"),
                JacksonUtil.toJsonNode("{\"rsp_code\":0,\"rsp_desc\":\"deleted\"}"));

        service.synchronize(deviceId);

        ArgumentCaptor<WanNsRequest> requests = ArgumentCaptor.forClass(WanNsRequest.class);
        verify(requestClient, Mockito.times(2)).execute(eq(connectionId), requests.capture());
        assertThat(requests.getAllValues()).extracting(WanNsRequest::operation)
                .containsExactly("get_terminal", "delete_terminal");
        assertThat(requests.getAllValues().get(1).body().path("dev_euis").path(0).asText())
                .isEqualTo(DEVICE_EUI);
        verify(registryClient).completeDeletion(deviceId);
    }

    @Test
    void treatsAlreadyMissingTerminalAsIdempotentSuccess() {
        when(registryClient.get(deviceId)).thenReturn(registry(0));
        when(requestClient.execute(eq(connectionId), any()))
                .thenReturn(JacksonUtil.toJsonNode("{\"rsp_code\":0,\"rsp_body\":[]}"));

        service.synchronize(deviceId);

        verify(requestClient, Mockito.times(1)).execute(eq(connectionId), any());
        verify(registryClient).completeDeletion(deviceId);
    }

    @Test
    void retriesDeletionAndRetainsPermanentFailure() {
        when(requestClient.execute(eq(connectionId), any()))
                .thenThrow(new WanNsRequestException("WAN NS request timed out"));
        when(registryClient.get(deviceId)).thenReturn(registry(0));

        service.synchronize(deviceId);

        verify(registryClient).updateDeletionFailure(deviceId, WanDeviceSyncStatus.DELETING,
                "WAN NS request timed out");
        verify(registryClient, never()).completeDeletion(deviceId);

        Mockito.reset(registryClient);
        when(registryClient.get(deviceId)).thenReturn(registry(9));
        service.synchronize(deviceId);
        verify(registryClient).updateDeletionFailure(deviceId, WanDeviceSyncStatus.FAILED,
                "WAN NS request timed out");
    }

    @Test
    void schedulerSubmitsPendingRecreatingAndDeletingWork() {
        UUID pending = UUID.randomUUID();
        UUID recreating = UUID.randomUUID();
        UUID deleting = UUID.randomUUID();
        when(registryClient.getDeviceIds(WanDeviceSyncStatus.PENDING)).thenReturn(List.of(pending));
        when(registryClient.getDeviceIds(WanDeviceSyncStatus.RECREATING)).thenReturn(List.of(recreating));
        when(registryClient.getDeviceIds(WanDeviceSyncStatus.DELETING)).thenReturn(List.of(deleting));
        WanDeviceSyncService scheduledService = Mockito.mock(WanDeviceSyncService.class);

        new WanPendingSyncScheduler(registryClient, scheduledService).submitPending();

        verify(scheduledService).synchronizeAsync(pending);
        verify(scheduledService).synchronizeAsync(recreating);
        verify(scheduledService).synchronizeAsync(deleting);
    }

    private WanDeviceRegistrySnapshot registry(int retryCount) {
        return new WanDeviceRegistrySnapshot(deviceId, UUID.randomUUID(), connectionId,
                WanDeviceType.TERMINAL, DEVICE_EUI, "Deleted Terminal", "{}",
                WanDeviceSyncStatus.DELETING, null, null, null, 1L,
                null, null, null, connectionId, DEVICE_EUI, retryCount);
    }
}
