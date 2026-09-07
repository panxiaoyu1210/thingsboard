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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.gen.transport.TransportProtos;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanConnectionOwnershipTest {

    @Test
    void claimsConnectionsWithAControlledLeaseAndReleasesThemOnShutdown() {
        long now = 1_000_000L;
        UUID connectionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        TransportService transportService = Mockito.mock(TransportService.class);
        WanTransportPasswordService passwordService = Mockito.mock(WanTransportPasswordService.class);
        when(transportService.getWanConnections(Mockito.any())).thenReturn(
                TransportProtos.GetWanConnectionsResponseMsg.newBuilder()
                        .addConnections(connection(connectionId, tenantId))
                        .build(),
                TransportProtos.GetWanConnectionsResponseMsg.getDefaultInstance());
        when(transportService.getWanDevicesIds(Mockito.any()))
                .thenReturn(TransportProtos.GetWanDevicesResponseMsg.getDefaultInstance());
        Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        WanTransportConfigurationProvider provider = new WanTransportConfigurationProvider(
                transportService, passwordService, "owner-a", clock, 90_000L);

        WanConfigurationSnapshot snapshot = provider.load();
        provider.releaseOwnership();

        assertThat(snapshot.connections()).extracting(WanConnectionConfig::id).containsExactly(connectionId);
        ArgumentCaptor<TransportProtos.GetWanConnectionsRequestMsg> requests =
                ArgumentCaptor.forClass(TransportProtos.GetWanConnectionsRequestMsg.class);
        verify(transportService, Mockito.times(2)).getWanConnections(requests.capture());
        TransportProtos.GetWanConnectionsRequestMsg claim = requests.getAllValues().get(0);
        assertThat(claim.getOwnerId()).isEqualTo("owner-a");
        assertThat(claim.getNow()).isEqualTo(now);
        assertThat(claim.getLeaseUntil()).isEqualTo(now + 90_000L);
        assertThat(claim.getReleaseOwnership()).isFalse();
        assertThat(requests.getAllValues().get(1).getReleaseOwnership()).isTrue();
    }

    @Test
    void skipsTheGlobalDeviceScanWhenThisInstanceOwnsNoConnections() {
        TransportService transportService = Mockito.mock(TransportService.class);
        when(transportService.getWanConnections(Mockito.any()))
                .thenReturn(TransportProtos.GetWanConnectionsResponseMsg.getDefaultInstance());
        WanTransportConfigurationProvider provider = new WanTransportConfigurationProvider(
                transportService, Mockito.mock(WanTransportPasswordService.class),
                "standby-owner", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 90_000L);

        WanConfigurationSnapshot snapshot = provider.load();

        assertThat(snapshot.connections()).isEmpty();
        assertThat(snapshot.devices()).isEmpty();
        verify(transportService, never()).getWanDevicesIds(Mockito.any());
    }

    private TransportProtos.WanConnectionProto connection(UUID connectionId, UUID tenantId) {
        return TransportProtos.WanConnectionProto.newBuilder()
                .setConnectionIdMSB(connectionId.getMostSignificantBits())
                .setConnectionIdLSB(connectionId.getLeastSignificantBits())
                .setTenantIdMSB(tenantId.getMostSignificantBits())
                .setTenantIdLSB(tenantId.getLeastSignificantBits())
                .setName("NS")
                .setBrokerHost("mqtt.example.org")
                .setBrokerPort(1883)
                .setClientId("client")
                .setNsPublishTopic("ns/publish")
                .setNsSubscribeTopic("ns/subscribe")
                .setQos(1)
                .setEnabled(true)
                .setRequestTimeoutMs(5_000)
                .setSyncIntervalHours(24)
                .setSyncEnabled(true)
                .setVersion(1)
                .build();
    }

}
