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
package org.thingsboard.server.service.wan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceProfileProvisionType;
import org.thingsboard.server.common.data.DeviceProfileType;
import org.thingsboard.server.common.data.device.data.DeviceData;
import org.thingsboard.server.common.data.device.data.WanDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileConfiguration;
import org.thingsboard.server.common.data.device.profile.DeviceProfileData;
import org.thingsboard.server.common.data.device.profile.WanDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanRateConfiguration;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceCredentialsService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.wan.WanDeviceRegistryService;
import org.thingsboard.server.exception.DataValidationException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanDeviceSyncManagerTest {

    private DeviceProfileService profileService;
    private DeviceService deviceService;
    private DeviceCredentialsService deviceCredentialsService;
    private WanDeviceRegistryService registryService;
    private WanDeviceRegistryManager manager;
    private TenantId tenantId;
    private DeviceId deviceId;
    private DeviceProfileId profileId;
    private UUID connectionId;

    @BeforeEach
    void setUp() {
        profileService = Mockito.mock(DeviceProfileService.class);
        deviceService = Mockito.mock(DeviceService.class);
        deviceCredentialsService = Mockito.mock(DeviceCredentialsService.class);
        registryService = Mockito.mock(WanDeviceRegistryService.class);
        manager = new WanDeviceRegistryManager(
                profileService, deviceService, deviceCredentialsService, registryService);
        tenantId = TenantId.fromUUID(UUID.randomUUID());
        deviceId = new DeviceId(UUID.randomUUID());
        profileId = new DeviceProfileId(UUID.randomUUID());
        connectionId = UUID.randomUUID();
        when(registryService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsPendingRegistryForNewWanGateway() {
        Device gateway = gateway(gatewayConfiguration(1));
        when(profileService.findDeviceProfileById(tenantId, profileId)).thenReturn(profile());

        WanDeviceRegistry result = manager.registerCreatedDevice(gateway);

        assertThat(result.getTenantId()).isEqualTo(tenantId);
        assertThat(result.getDeviceId()).isEqualTo(deviceId);
        assertThat(result.getConnectionId()).isEqualTo(connectionId);
        assertThat(result.getDeviceType()).isEqualTo(WanDeviceType.GATEWAY);
        assertThat(result.getExternalId()).isEqualTo("8C3F74C81C703000");
        assertThat(result.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
        assertThat(JacksonUtil.toJsonNode(result.getConfiguration()).path("gateway").path("freqMajor").asInt())
                .isEqualTo(1);
    }

    @Test
    void rejectsMismatchedGatewayFlag() {
        Device device = gateway(gatewayConfiguration(1));
        device.setAdditionalInfo(JacksonUtil.newObjectNode().put(DataConstants.GATEWAY_PARAMETER, false));

        assertThatThrownBy(() -> manager.registerCreatedDevice(device))
                .isInstanceOf(DataValidationException.class)
                .hasMessageContaining("gateway flag");
        verify(registryService, never()).save(any());
    }

    @Test
    void appliesAuthoritativeNsConfigurationAndActivatesRegistry() {
        WanDeviceRegistry registry = registry(WanDeviceSyncStatus.SYNCING);
        Device gateway = gateway(gatewayConfiguration(1));
        when(registryService.findByDeviceId(deviceId)).thenReturn(registry);
        when(deviceService.findDeviceById(tenantId, deviceId)).thenReturn(gateway);
        WanGatewayConfiguration nsConfiguration = gatewayConfiguration(5);

        WanDeviceRegistry result = manager.update(deviceId, WanDeviceSyncStatus.ACTIVE,
                null, JacksonUtil.toString(nsConfiguration), null, null, null);

        assertThat(result.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.ACTIVE);
        assertThat(result.getLastSyncTime()).isNotNull();
        assertThat(result.getError()).isNull();
        assertThat(((WanDeviceTransportConfiguration) gateway.getDeviceData().getTransportConfiguration())
                .getGateway().getFreqMajor()).isEqualTo(5);
        verify(deviceService).saveDevice(gateway);
    }

    @Test
    void rejectsInvalidStateTransition() {
        when(registryService.findByDeviceId(deviceId)).thenReturn(registry(WanDeviceSyncStatus.ACTIVE));

        assertThatThrownBy(() -> manager.update(deviceId, WanDeviceSyncStatus.CREATING,
                null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("CREATING");
    }

    private WanDeviceRegistry registry(WanDeviceSyncStatus status) {
        WanDeviceRegistry registry = new WanDeviceRegistry();
        registry.setId(UUID.randomUUID());
        registry.setTenantId(tenantId);
        registry.setDeviceId(deviceId);
        registry.setConnectionId(connectionId);
        registry.setDeviceType(WanDeviceType.GATEWAY);
        registry.setExternalId("8C3F74C81C703000");
        registry.setDeviceName("Gateway One");
        registry.setConfiguration(JacksonUtil.toString(gateway(gatewayConfiguration(1))
                .getDeviceData().getTransportConfiguration()));
        registry.setSyncStatus(status);
        registry.setVersion(1L);
        return registry;
    }

    private Device gateway(WanGatewayConfiguration gatewayConfiguration) {
        WanDeviceTransportConfiguration transport = new WanDeviceTransportConfiguration();
        transport.setDeviceType(WanDeviceType.GATEWAY);
        transport.setGateway(gatewayConfiguration);
        DeviceData data = new DeviceData();
        data.setTransportConfiguration(transport);
        Device device = new Device(deviceId);
        device.setTenantId(tenantId);
        device.setDeviceProfileId(profileId);
        device.setName("Gateway One");
        device.setDeviceData(data);
        device.setAdditionalInfo(JacksonUtil.newObjectNode().put(DataConstants.GATEWAY_PARAMETER, true));
        return device;
    }

    private DeviceProfile profile() {
        WanDeviceProfileTransportConfiguration transport = new WanDeviceProfileTransportConfiguration();
        transport.setConnectionId(connectionId);
        DeviceProfileData data = new DeviceProfileData();
        data.setConfiguration(new DefaultDeviceProfileConfiguration());
        data.setTransportConfiguration(transport);
        DeviceProfile profile = new DeviceProfile(profileId);
        profile.setTenantId(tenantId);
        profile.setName("WAN");
        profile.setType(DeviceProfileType.DEFAULT);
        profile.setProvisionType(DeviceProfileProvisionType.DISABLED);
        profile.setProfileData(data);
        return profile;
    }

    private WanGatewayConfiguration gatewayConfiguration(int freqMajor) {
        WanRateConfiguration rate = new WanRateConfiguration();
        rate.setRateMode(0);
        rate.setUplinkLen(100);
        rate.setDownlinkLen(120);
        WanGatewayConfiguration gateway = new WanGatewayConfiguration();
        gateway.setGwId("8C3F74C81C703000");
        gateway.setFreqMajor(freqMajor);
        gateway.setFreqMinor(2);
        gateway.setNwkNum(3);
        gateway.setTddNum(4);
        gateway.setRateNum(1);
        gateway.setRateCfgs(List.of(rate));
        return gateway;
    }
}
