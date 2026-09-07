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
import org.thingsboard.server.common.data.device.credentials.WanDeviceCredentials;
import org.thingsboard.server.common.data.device.data.DeviceData;
import org.thingsboard.server.common.data.device.data.WanDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.profile.DeviceProfileData;
import org.thingsboard.server.common.data.device.profile.WanDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanTerminalConfiguration;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.dao.device.DeviceCredentialsService;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.wan.WanConnectionService;
import org.thingsboard.server.dao.wan.WanDeviceRegistryService;
import org.thingsboard.server.exception.DataValidationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanTerminalRegistryManagerTest {

    private static final String DEVICE_EUI = "0000000000001001";
    private static final String GATEWAY_ID = "8C3F74C81C703000";
    private static final String NS_ROOT_KEY = "11111111111111111111111111111111";

    private DeviceProfileService profileService;
    private DeviceService deviceService;
    private DeviceCredentialsService credentialsService;
    private WanDeviceRegistryService registryService;
    private WanDeviceRegistryManager manager;
    private TenantId tenantId;
    private UUID connectionId;
    private DeviceId terminalId;
    private DeviceId gatewayId;
    private DeviceProfileId terminalProfileId;
    private DeviceProfileId gatewayProfileId;

    @BeforeEach
    void setUp() {
        profileService = Mockito.mock(DeviceProfileService.class);
        deviceService = Mockito.mock(DeviceService.class);
        credentialsService = Mockito.mock(DeviceCredentialsService.class);
        registryService = Mockito.mock(WanDeviceRegistryService.class);
        manager = new WanDeviceRegistryManager(
                profileService, deviceService, credentialsService, registryService,
                Mockito.mock(WanConnectionService.class));
        tenantId = TenantId.fromUUID(UUID.randomUUID());
        connectionId = UUID.randomUUID();
        terminalId = new DeviceId(UUID.randomUUID());
        gatewayId = new DeviceId(UUID.randomUUID());
        terminalProfileId = new DeviceProfileId(UUID.randomUUID());
        gatewayProfileId = new DeviceProfileId(UUID.randomUUID());
        when(registryService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void registersTerminalWithSameTenantAndConnectionGateway() {
        Device terminal = terminal(terminalId, terminalProfileId, gatewayId, 5);
        Device gateway = gateway();
        when(profileService.findDeviceProfileById(tenantId, terminalProfileId))
                .thenReturn(profile(terminalProfileId, connectionId));
        when(deviceService.findDeviceById(tenantId, gatewayId)).thenReturn(gateway);
        when(profileService.findDeviceProfileById(tenantId, gatewayProfileId))
                .thenReturn(profile(gatewayProfileId, connectionId));

        WanDeviceRegistry result = manager.registerCreatedDevice(terminal);

        assertThat(result.getDeviceType()).isEqualTo(WanDeviceType.TERMINAL);
        assertThat(result.getExternalId()).isEqualTo(DEVICE_EUI);
        assertThat(result.getRelatedExternalId()).isEqualTo(GATEWAY_ID);
        assertThat(result.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
    }

    @Test
    void rejectsMissingCrossTenantOrDifferentConnectionGateway() {
        Device terminal = terminal(terminalId, terminalProfileId, gatewayId, 5);
        when(profileService.findDeviceProfileById(tenantId, terminalProfileId))
                .thenReturn(profile(terminalProfileId, connectionId));

        assertThatThrownBy(() -> manager.registerCreatedDevice(terminal))
                .isInstanceOf(DataValidationException.class)
                .hasMessageContaining("current tenant");

        when(deviceService.findDeviceById(tenantId, gatewayId))
                .thenReturn(terminal(gatewayId, gatewayProfileId, null, 0));
        assertThatThrownBy(() -> manager.registerCreatedDevice(terminal))
                .isInstanceOf(DataValidationException.class)
                .hasMessageContaining("current tenant");

        when(deviceService.findDeviceById(tenantId, gatewayId)).thenReturn(gateway());
        when(profileService.findDeviceProfileById(tenantId, gatewayProfileId))
                .thenReturn(profile(gatewayProfileId, UUID.randomUUID()));
        assertThatThrownBy(() -> manager.registerCreatedDevice(terminal))
                .isInstanceOf(DataValidationException.class)
                .hasMessageContaining("same NS connection");
    }

    @Test
    void appliesAuthoritativeNsTerminalConfigurationAndRootKey() {
        WanDeviceRegistry terminalRegistry = terminalRegistry();
        WanDeviceRegistry gatewayRegistry = new WanDeviceRegistry();
        gatewayRegistry.setDeviceId(gatewayId);
        Device terminal = terminal(terminalId, terminalProfileId, null, 5);
        DeviceCredentials credentials = credentials("0102030405060708090A0B0C0D0E0F10");
        when(registryService.findByDeviceIdForUpdate(terminalId)).thenReturn(terminalRegistry);
        when(registryService.findGatewayByExternalId(tenantId, connectionId, GATEWAY_ID))
                .thenReturn(gatewayRegistry);
        when(deviceService.findDeviceById(tenantId, terminalId)).thenReturn(terminal);
        when(credentialsService.findDeviceCredentialsByDeviceId(tenantId, terminalId)).thenReturn(credentials);
        WanTerminalConfiguration nsConfiguration = terminalConfiguration(null, 0, 4);

        WanDeviceRegistry result = manager.update(terminalId, WanDeviceSyncStatus.ACTIVE, null, null,
                JacksonUtil.toString(nsConfiguration), NS_ROOT_KEY, GATEWAY_ID);

        WanTerminalConfiguration savedConfiguration = ((WanDeviceTransportConfiguration)
                terminal.getDeviceData().getTransportConfiguration()).getTerminal();
        assertThat(savedConfiguration.getDevType()).isZero();
        assertThat(savedConfiguration.getSecurityMode()).isEqualTo(4);
        assertThat(savedConfiguration.getRelatedGatewayId()).isEqualTo(gatewayId);
        assertThat(result.getRelatedExternalId()).isEqualTo(GATEWAY_ID);
        assertThat(result.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.ACTIVE);
        ArgumentCaptor<DeviceCredentials> credentialsCaptor = ArgumentCaptor.forClass(DeviceCredentials.class);
        verify(credentialsService).updateDeviceCredentials(Mockito.eq(tenantId), credentialsCaptor.capture());
        WanDeviceCredentials savedCredentials = JacksonUtil.fromString(
                credentialsCaptor.getValue().getCredentialsValue(), WanDeviceCredentials.class);
        assertThat(savedCredentials.getRootKey()).isEqualTo(NS_ROOT_KEY);
        verify(deviceService).saveDevice(terminal);
    }

    private Device terminal(DeviceId id, DeviceProfileId profileId, DeviceId relatedGatewayId, int securityMode) {
        WanDeviceTransportConfiguration transport = new WanDeviceTransportConfiguration();
        transport.setDeviceType(WanDeviceType.TERMINAL);
        transport.setTerminal(terminalConfiguration(relatedGatewayId, 1, securityMode));
        DeviceData data = new DeviceData();
        data.setTransportConfiguration(transport);
        Device device = new Device(id);
        device.setTenantId(tenantId);
        device.setDeviceProfileId(profileId);
        device.setName("Terminal One");
        device.setDeviceData(data);
        device.setAdditionalInfo(JacksonUtil.newObjectNode().put(DataConstants.GATEWAY_PARAMETER, false));
        return device;
    }

    private WanTerminalConfiguration terminalConfiguration(DeviceId relatedGatewayId, int deviceType, int securityMode) {
        WanTerminalConfiguration configuration = new WanTerminalConfiguration();
        configuration.setDevEui(DEVICE_EUI);
        configuration.setDevType(deviceType);
        configuration.setSecurityMode(securityMode);
        configuration.setRelatedGatewayId(relatedGatewayId);
        return configuration;
    }

    private Device gateway() {
        WanGatewayConfiguration gateway = new WanGatewayConfiguration();
        gateway.setGwId(GATEWAY_ID);
        WanDeviceTransportConfiguration transport = new WanDeviceTransportConfiguration();
        transport.setDeviceType(WanDeviceType.GATEWAY);
        transport.setGateway(gateway);
        DeviceData data = new DeviceData();
        data.setTransportConfiguration(transport);
        Device device = new Device(gatewayId);
        device.setTenantId(tenantId);
        device.setDeviceProfileId(gatewayProfileId);
        device.setName("Gateway One");
        device.setDeviceData(data);
        device.setAdditionalInfo(JacksonUtil.newObjectNode().put(DataConstants.GATEWAY_PARAMETER, true));
        return device;
    }

    private DeviceProfile profile(DeviceProfileId profileId, UUID profileConnectionId) {
        WanDeviceProfileTransportConfiguration transport = new WanDeviceProfileTransportConfiguration();
        transport.setConnectionId(profileConnectionId);
        DeviceProfileData data = new DeviceProfileData();
        data.setTransportConfiguration(transport);
        DeviceProfile profile = new DeviceProfile(profileId);
        profile.setTenantId(tenantId);
        profile.setProfileData(data);
        return profile;
    }

    private WanDeviceRegistry terminalRegistry() {
        WanDeviceRegistry registry = new WanDeviceRegistry();
        registry.setId(UUID.randomUUID());
        registry.setTenantId(tenantId);
        registry.setDeviceId(terminalId);
        registry.setConnectionId(connectionId);
        registry.setDeviceType(WanDeviceType.TERMINAL);
        registry.setExternalId(DEVICE_EUI);
        registry.setDeviceName("Terminal One");
        registry.setSyncStatus(WanDeviceSyncStatus.SYNCING);
        registry.setVersion(1L);
        return registry;
    }

    private DeviceCredentials credentials(String rootKey) {
        WanDeviceCredentials value = new WanDeviceCredentials();
        value.setRootKey(rootKey);
        DeviceCredentials credentials = new DeviceCredentials();
        credentials.setDeviceId(terminalId);
        credentials.setCredentialsType(DeviceCredentialsType.WAN_CREDENTIALS);
        credentials.setCredentialsId(DEVICE_EUI);
        credentials.setCredentialsValue(JacksonUtil.toString(value));
        return credentials;
    }
}
