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
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.gen.transport.TransportProtos;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanTaskClaimClientTest {

    private static final long NOW = 1_000_000L;

    private TransportService transportService;
    private WanDeviceRegistryClient client;
    private UUID deviceId;

    @BeforeEach
    void setUp() {
        transportService = Mockito.mock(TransportService.class);
        client = new WanDeviceRegistryClient(transportService, 20, 7, 60_000L, "owner-a",
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        deviceId = UUID.randomUUID();
    }

    @Test
    void claimsOneBatchUsingTheControlledClockAndConfiguredBatchSize() {
        when(transportService.getPendingWanDeviceRegistries(Mockito.any())).thenReturn(
                TransportProtos.GetPendingWanDeviceRegistriesResponseMsg.newBuilder()
                        .addRegistries(registry(deviceId))
                        .build());

        assertThat(client.claimAvailable()).extracting(WanDeviceRegistrySnapshot::deviceId)
                .containsExactly(deviceId);

        ArgumentCaptor<TransportProtos.GetPendingWanDeviceRegistriesRequestMsg> request =
                ArgumentCaptor.forClass(TransportProtos.GetPendingWanDeviceRegistriesRequestMsg.class);
        verify(transportService).getPendingWanDeviceRegistries(request.capture());
        assertThat(request.getValue().getClaimAvailable()).isTrue();
        assertThat(request.getValue().getPageSize()).isEqualTo(7);
        assertThat(request.getValue().getOwnerId()).isEqualTo("owner-a");
        assertThat(request.getValue().getNow()).isEqualTo(NOW);
        assertThat(request.getValue().getLeaseUntil()).isEqualTo(NOW + 60_000L);
    }

    @Test
    void attachesOwnerAndTimeToUpdatesAndExplicitRelease() {
        when(transportService.getWanDeviceRegistry(Mockito.any())).thenReturn(
                TransportProtos.GetWanDeviceRegistryResponseMsg.newBuilder()
                        .setRegistry(registry(deviceId))
                        .build());
        when(transportService.updateWanDeviceRegistry(Mockito.any())).thenReturn(
                TransportProtos.GetWanDeviceRegistryResponseMsg.getDefaultInstance());

        assertThat(client.claim(deviceId).deviceId()).isEqualTo(deviceId);
        client.update(deviceId, WanDeviceSyncStatus.SYNCING, null, null);
        client.release(deviceId);

        ArgumentCaptor<TransportProtos.GetWanDeviceRegistryRequestMsg> claim =
                ArgumentCaptor.forClass(TransportProtos.GetWanDeviceRegistryRequestMsg.class);
        verify(transportService).getWanDeviceRegistry(claim.capture());
        assertThat(claim.getValue().getOwnerId()).isEqualTo("owner-a");
        assertThat(claim.getValue().getNow()).isEqualTo(NOW);
        assertThat(claim.getValue().getLeaseUntil()).isEqualTo(NOW + 60_000L);

        ArgumentCaptor<TransportProtos.UpdateWanDeviceRegistryRequestMsg> updates =
                ArgumentCaptor.forClass(TransportProtos.UpdateWanDeviceRegistryRequestMsg.class);
        verify(transportService, Mockito.times(2)).updateWanDeviceRegistry(updates.capture());
        assertThat(updates.getAllValues().get(0).getLockOwnerId()).isEqualTo("owner-a");
        assertThat(updates.getAllValues().get(0).getOperationTime()).isEqualTo(NOW);
        assertThat(updates.getAllValues().get(1).getReleaseTask()).isTrue();
        assertThat(updates.getAllValues().get(1).getLockOwnerId()).isEqualTo("owner-a");
    }

    private TransportProtos.WanDeviceRegistryProto registry(UUID id) {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        return TransportProtos.WanDeviceRegistryProto.newBuilder()
                .setDeviceIdMSB(id.getMostSignificantBits())
                .setDeviceIdLSB(id.getLeastSignificantBits())
                .setTenantIdMSB(tenantId.getMostSignificantBits())
                .setTenantIdLSB(tenantId.getLeastSignificantBits())
                .setConnectionIdMSB(connectionId.getMostSignificantBits())
                .setConnectionIdLSB(connectionId.getLeastSignificantBits())
                .setDeviceType("GATEWAY")
                .setExternalId("8C3F74C81C703000")
                .setDeviceName("Gateway")
                .setConfiguration("{}")
                .setSyncStatus(WanDeviceSyncStatus.PENDING.name())
                .setVersion(1)
                .build();
    }

}
