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
import org.thingsboard.server.common.data.device.profile.WanDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanRateConfiguration;
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
        WanConnection connection = saveConnection();
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

    private WanConnection saveConnection() {
        WanConnection connection = new WanConnection();
        connection.setName("Gateway Sync NS");
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
}
