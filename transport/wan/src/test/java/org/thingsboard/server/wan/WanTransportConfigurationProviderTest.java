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

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceProfileProvisionType;
import org.thingsboard.server.common.data.DeviceProfileType;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileConfiguration;
import org.thingsboard.server.common.data.device.profile.DeviceProfileData;
import org.thingsboard.server.common.data.device.profile.WanDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.util.ProtoUtils;
import org.thingsboard.server.gen.transport.TransportProtos;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanTransportConfigurationProviderTest {

    @Test
    void loadsPagedConnectionsAndCompleteWanDeviceDescriptors() {
        TransportService transportService = Mockito.mock(TransportService.class);
        WanTransportPasswordService passwordService = Mockito.mock(WanTransportPasswordService.class);
        when(passwordService.decrypt("v1:test-ciphertext")).thenReturn("password");
        WanTransportConfigurationProvider provider = new WanTransportConfigurationProvider(transportService, passwordService);
        ReflectionTestUtils.setField(provider, "pageSize", 1);

        UUID connectionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        TransportProtos.WanConnectionProto connection = connection(connectionId, tenantId);
        when(transportService.getWanConnections(Mockito.any()))
                .thenReturn(TransportProtos.GetWanConnectionsResponseMsg.newBuilder()
                                .addConnections(connection).setHasNextPage(true).build(),
                        TransportProtos.GetWanConnectionsResponseMsg.newBuilder()
                                .setHasNextPage(false).build());

        UUID deviceId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(transportService.getWanDevicesIds(Mockito.any()))
                .thenReturn(TransportProtos.GetWanDevicesResponseMsg.newBuilder()
                        .addIds(deviceId.toString()).setHasNextPage(false).build());
        when(transportService.getDevice(Mockito.any())).thenReturn(
                TransportProtos.GetDeviceResponseMsg.newBuilder()
                        .setDeviceProfileIdMSB(profileId.getMostSignificantBits())
                        .setDeviceProfileIdLSB(profileId.getLeastSignificantBits())
                        .setDeviceTransportConfiguration(ByteString.copyFrom(new byte[]{1, 2, 3}))
                        .build());
        when(transportService.getEntityProfile(Mockito.any())).thenReturn(
                TransportProtos.GetEntityProfileResponseMsg.newBuilder()
                        .setEntityType("DEVICE_PROFILE")
                        .setDeviceProfile(ProtoUtils.toProto(profile(profileId, tenantId, connectionId)))
                        .build());

        WanConfigurationSnapshot snapshot = provider.load();

        assertThat(snapshot.connections()).hasSize(1);
        assertThat(snapshot.connections().get(0).id()).isEqualTo(connectionId);
        assertThat(snapshot.connections().get(0).password()).isEqualTo("password");
        assertThat(snapshot.devices()).hasSize(1);
        assertThat(snapshot.devices().get(0).deviceId()).isEqualTo(deviceId);
        assertThat(snapshot.devices().get(0).connectionId()).isEqualTo(connectionId);
        assertThat(snapshot.devices().get(0).transportConfiguration()).containsExactly(1, 2, 3);
        verify(transportService, times(2)).getWanConnections(Mockito.any());
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
                .setUsername("user")
                .setEncryptedPassword("v1:test-ciphertext")
                .setNsPublishTopic("ns/publish")
                .setNsSubscribeTopic("ns/subscribe")
                .setQos(1)
                .setEnabled(true)
                .setRequestTimeoutMs(5_000)
                .setSyncIntervalHours(24)
                .setVersion(1)
                .build();
    }

    private DeviceProfile profile(UUID profileId, UUID tenantId, UUID connectionId) {
        WanDeviceProfileTransportConfiguration transportConfiguration = new WanDeviceProfileTransportConfiguration();
        transportConfiguration.setConnectionId(connectionId);
        DeviceProfileData profileData = new DeviceProfileData();
        profileData.setConfiguration(new DefaultDeviceProfileConfiguration());
        profileData.setTransportConfiguration(transportConfiguration);
        DeviceProfile profile = new DeviceProfile(new DeviceProfileId(profileId));
        profile.setTenantId(TenantId.fromUUID(tenantId));
        profile.setName("WAN profile");
        profile.setType(DeviceProfileType.DEFAULT);
        profile.setTransportType(DeviceTransportType.WAN);
        profile.setProvisionType(DeviceProfileProvisionType.DISABLED);
        profile.setProfileData(profileData);
        return profile;
    }

}
