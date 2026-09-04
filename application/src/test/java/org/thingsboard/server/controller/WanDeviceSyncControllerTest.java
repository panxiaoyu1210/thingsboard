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
package org.thingsboard.server.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.SaveDeviceWithCredentialsRequest;
import org.thingsboard.server.common.data.device.data.DeviceData;
import org.thingsboard.server.common.data.device.data.DefaultDeviceConfiguration;
import org.thingsboard.server.common.data.device.data.WanDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.credentials.WanDeviceCredentials;
import org.thingsboard.server.common.data.device.profile.WanDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanRateConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanTerminalConfiguration;
import org.thingsboard.server.common.data.wan.WanConnection;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.dao.service.DaoSqlTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DaoSqlTest
public class WanDeviceSyncControllerTest extends AbstractControllerTest {

    @Before
    public void login() throws Exception {
        loginTenantAdmin();
    }

    @Test
    public void createsPendingRegistryForBothDeviceRestCreationPathsAndExposesReadOnlyState() throws Exception {
        WanConnection connection = saveConnection("Gateway Sync NS");
        WanDeviceProfileTransportConfiguration profileConfiguration =
                new WanDeviceProfileTransportConfiguration();
        profileConfiguration.setConnectionId(connection.getId());
        DeviceProfile profile = doPost("/api/deviceProfile",
                createDeviceProfile("WAN Gateway Sync Profile", profileConfiguration), DeviceProfile.class);

        Device first = doPost("/api/device",
                gateway("WAN Gateway API", profile, "8C3F74C81C703000"), Device.class);

        Device secondRequest = gateway("WAN Gateway Credentials API", profile, "8C3F74C81C703001");
        DeviceCredentials credentials = new DeviceCredentials();
        credentials.setCredentialsType(DeviceCredentialsType.WAN_CREDENTIALS);
        credentials.setCredentialsId("8C3F74C81C703001");
        Device second = doPost("/api/device-with-credentials",
                new SaveDeviceWithCredentialsRequest(secondRequest, credentials), Device.class);

        WanDeviceRegistry firstState = doGet(
                "/api/wan/device/" + first.getId().getId() + "/sync", WanDeviceRegistry.class);
        WanDeviceRegistry secondState = doGet(
                "/api/wan/device/" + second.getId().getId() + "/sync", WanDeviceRegistry.class);

        assertThat(firstState.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
        assertThat(firstState.getConnectionId()).isEqualTo(connection.getId());
        assertThat(firstState.getExternalId()).isEqualTo("8C3F74C81C703000");
        assertThat(firstState.getDeviceName()).isEqualTo("WAN Gateway API");
        assertThat(firstState.getConfiguration()).isNull();
        assertThat(firstState.getLastSyncTime()).isNull();
        assertThat(firstState.getError()).isNull();
        assertThat(secondState.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
        assertThat(secondState.getExternalId()).isEqualTo("8C3F74C81C703001");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wan_device_registry WHERE device_id IN (?, ?)",
                Long.class, first.getId().getId(), second.getId().getId())).isEqualTo(2L);

        loginDifferentTenant();
        doGet("/api/wan/device/" + first.getId().getId() + "/sync")
                .andExpect(status().isNotFound());
    }

    @Test
    public void createsPendingRegistryForBothTerminalRestCreationPaths() throws Exception {
        WanConnection connection = saveConnection("Terminal Sync NS");
        WanDeviceProfileTransportConfiguration profileConfiguration =
                new WanDeviceProfileTransportConfiguration();
        profileConfiguration.setConnectionId(connection.getId());
        DeviceProfile profile = doPost("/api/deviceProfile",
                createDeviceProfile("WAN Terminal Sync Profile", profileConfiguration), DeviceProfile.class);

        Device first = doPost("/api/device",
                terminal("WAN Terminal API", profile, "0000000000001001"), Device.class);
        DeviceCredentials credentials = new DeviceCredentials();
        credentials.setCredentialsType(DeviceCredentialsType.WAN_CREDENTIALS);
        credentials.setCredentialsId("0000000000001002");
        credentials.setCredentialsValue(JacksonUtil.toString(new WanDeviceCredentials()));
        Device second = doPost("/api/device-with-credentials",
                new SaveDeviceWithCredentialsRequest(
                        terminal("WAN Terminal Credentials API", profile, "0000000000001002"), credentials),
                Device.class);

        WanDeviceRegistry firstState = doGet(
                "/api/wan/device/" + first.getId().getId() + "/sync", WanDeviceRegistry.class);
        WanDeviceRegistry secondState = doGet(
                "/api/wan/device/" + second.getId().getId() + "/sync", WanDeviceRegistry.class);
        assertThat(firstState.getDeviceType()).isEqualTo(WanDeviceType.TERMINAL);
        assertThat(firstState.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
        assertThat(firstState.getExternalId()).isEqualTo("0000000000001001");
        assertThat(firstState.getRelatedExternalId()).isNull();
        assertThat(secondState.getDeviceType()).isEqualTo(WanDeviceType.TERMINAL);
        assertThat(secondState.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
        assertThat(secondState.getExternalId()).isEqualTo("0000000000001002");
    }

    @Test
    public void rejectsCrossTenantNonGatewayAndDifferentConnectionRelations() throws Exception {
        WanConnection firstConnection = saveConnection("First Relation NS");
        DeviceProfile firstProfile = saveWanProfile("First Relation Profile", firstConnection);
        Device firstTenantGateway = doPost("/api/device",
                gateway("First Tenant Gateway", firstProfile, "8C3F74C81C703010"), Device.class);

        loginDifferentTenant();
        WanConnection secondConnection = saveConnection("Second Relation NS");
        DeviceProfile secondProfile = saveWanProfile("Second Relation Profile", secondConnection);
        doPost("/api/device", terminal("Cross Tenant Terminal", secondProfile,
                "0000000000001010", firstTenantGateway.getId())).andExpect(status().isBadRequest());

        Device nonGateway = doPost("/api/device",
                terminal("Not A Gateway", secondProfile, "0000000000001011"), Device.class);
        doPost("/api/device", terminal("Related To Terminal", secondProfile,
                "0000000000001012", nonGateway.getId())).andExpect(status().isBadRequest());

        Device secondTenantGateway = doPost("/api/device",
                gateway("Second Tenant Gateway", secondProfile, "8C3F74C81C703011"), Device.class);
        WanConnection thirdConnection = saveConnection("Third Relation NS");
        DeviceProfile thirdProfile = saveWanProfile("Third Relation Profile", thirdConnection);
        doPost("/api/device", terminal("Different Connection Terminal", thirdProfile,
                "0000000000001013", secondTenantGateway.getId())).andExpect(status().isBadRequest());
    }

    private DeviceProfile saveWanProfile(String name, WanConnection connection) {
        WanDeviceProfileTransportConfiguration configuration = new WanDeviceProfileTransportConfiguration();
        configuration.setConnectionId(connection.getId());
        return doPost("/api/deviceProfile", createDeviceProfile(name, configuration), DeviceProfile.class);
    }

    private WanConnection saveConnection(String name) {
        WanConnection connection = new WanConnection();
        connection.setName(name);
        connection.setBrokerHost("mqtt.example.org");
        connection.setBrokerPort(1883);
        connection.setClientId("gateway-sync-test");
        connection.setNsPublishTopic("turmass/ns/publish");
        connection.setNsSubscribeTopic("turmass/ns/subscribe");
        connection.setQos(1);
        connection.setEnabled(true);
        connection.setRequestTimeoutMs(5_000);
        connection.setSyncIntervalHours(24);
        return doPost("/api/wan/connection", connection, WanConnection.class);
    }

    private Device gateway(String name, DeviceProfile profile, String gatewayId) {
        WanRateConfiguration rate = new WanRateConfiguration();
        rate.setRateMode(0);
        rate.setUplinkLen(100);
        rate.setDownlinkLen(120);
        WanGatewayConfiguration gateway = new WanGatewayConfiguration();
        gateway.setGwId(gatewayId);
        gateway.setFreqMajor(1);
        gateway.setFreqMinor(2);
        gateway.setNwkNum(3);
        gateway.setTddNum(4);
        gateway.setRateNum(1);
        gateway.setRateCfgs(List.of(rate));
        WanDeviceTransportConfiguration transport = new WanDeviceTransportConfiguration();
        transport.setDeviceType(WanDeviceType.GATEWAY);
        transport.setGateway(gateway);
        DeviceData data = new DeviceData();
        data.setConfiguration(new DefaultDeviceConfiguration());
        data.setTransportConfiguration(transport);
        ObjectNode additionalInfo = JacksonUtil.newObjectNode().put("gateway", true);
        Device device = new Device();
        device.setName(name);
        device.setDeviceProfileId(profile.getId());
        device.setDeviceData(data);
        device.setAdditionalInfo(additionalInfo);
        return device;
    }

    private Device terminal(String name, DeviceProfile profile, String deviceEui) {
        return terminal(name, profile, deviceEui, null);
    }

    private Device terminal(String name, DeviceProfile profile, String deviceEui, DeviceId relatedGatewayId) {
        WanTerminalConfiguration terminal = new WanTerminalConfiguration();
        terminal.setDevEui(deviceEui);
        terminal.setDevType(0);
        terminal.setSecurityMode(0);
        terminal.setRelatedGatewayId(relatedGatewayId);
        WanDeviceTransportConfiguration transport = new WanDeviceTransportConfiguration();
        transport.setDeviceType(WanDeviceType.TERMINAL);
        transport.setTerminal(terminal);
        DeviceData data = new DeviceData();
        data.setConfiguration(new DefaultDeviceConfiguration());
        data.setTransportConfiguration(transport);
        Device device = new Device();
        device.setName(name);
        device.setDeviceProfileId(profile.getId());
        device.setDeviceData(data);
        device.setAdditionalInfo(JacksonUtil.newObjectNode().put("gateway", false));
        return device;
    }
}
