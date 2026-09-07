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
import org.springframework.beans.factory.annotation.Autowired;
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
import org.thingsboard.server.dao.wan.WanConnectionService;
import org.thingsboard.server.dao.wan.WanDeviceRegistryService;
import org.thingsboard.server.service.wan.WanDeviceRegistryManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DaoSqlTest
public class WanDeviceSyncControllerTest extends AbstractControllerTest {

    @Autowired
    private WanDeviceRegistryService registryService;

    @Autowired
    private WanDeviceRegistryManager registryManager;

    @Autowired
    private WanConnectionService wanConnectionService;

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

    @Test
    public void queuesIdempotentOnDemandSyncWhileDeviceGetRemainsReadOnly() throws Exception {
        WanConnection connection = saveConnection("On Demand NS");
        DeviceProfile profile = saveWanProfile("On Demand Profile", connection);
        Device device = doPost("/api/device",
                gateway("On Demand Gateway", profile, "8C3F74C81C703020"), Device.class);
        WanDeviceRegistry active = setSyncState(device, WanDeviceSyncStatus.ACTIVE, "previous error", 123L);

        doGet("/api/device/" + device.getId().getId(), Device.class);
        WanDeviceRegistry afterDeviceGet = registryService.findByDeviceId(tenantId, device.getId());
        assertThat(afterDeviceGet.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.ACTIVE);
        assertThat(afterDeviceGet.getVersion()).isEqualTo(active.getVersion());

        WanDeviceRegistry first = doPost(
                "/api/wan/device/" + device.getId().getId() + "/sync", WanDeviceRegistry.class);
        WanDeviceRegistry second = doPost(
                "/api/wan/device/" + device.getId().getId() + "/sync", WanDeviceRegistry.class);

        assertThat(first.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
        assertThat(first.getError()).isNull();
        assertThat(first.getLastSuccessfulSyncTime()).isEqualTo(123L);
        assertThat(second.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
        assertThat(second.getVersion()).isEqualTo(first.getVersion());
    }

    @Test
    public void retriesOnlyFailedSyncAndRejectsCustomerUser() throws Exception {
        WanConnection connection = saveConnection("Retry NS");
        DeviceProfile profile = saveWanProfile("Retry Profile", connection);
        Device device = doPost("/api/device",
                gateway("Retry Gateway", profile, "8C3F74C81C703021"), Device.class);
        setSyncState(device, WanDeviceSyncStatus.ACTIVE, null, 123L);

        doPost("/api/wan/device/" + device.getId().getId() + "/sync/retry")
                .andExpect(status().isBadRequest());

        setSyncState(device, WanDeviceSyncStatus.FAILED, "NS rejected device", 123L);
        WanDeviceRegistry retried = doPost(
                "/api/wan/device/" + device.getId().getId() + "/sync/retry", WanDeviceRegistry.class);
        assertThat(retried.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
        assertThat(retried.getError()).isNull();

        setSyncState(device, WanDeviceSyncStatus.FAILED, "NS rejected device", 123L);
        loginCustomerUser();
        doPost("/api/wan/device/" + device.getId().getId() + "/sync/retry")
                .andExpect(status().isForbidden());
    }

    @Test
    public void snapshotsOldNsIdentityAndCurrentPlatformConfigurationForRecreate() throws Exception {
        WanConnection connection = saveConnection("Recreate NS");
        DeviceProfile profile = saveWanProfile("Recreate Profile", connection);
        Device device = doPost("/api/device",
                gateway("Recreate Gateway", profile, "8C3F74C81C703030"), Device.class);
        setSyncState(device, WanDeviceSyncStatus.ACTIVE, null, 123L);
        WanDeviceTransportConfiguration transport =
                (WanDeviceTransportConfiguration) device.getDeviceData().getTransportConfiguration();
        transport.getGateway().setGwId("8C3F74C81C703031");
        transport.getGateway().setFreqMajor(5);
        device = doPost("/api/device", device, Device.class);

        WanDeviceRegistry recreating = doPost(
                "/api/wan/device/" + device.getId().getId() + "/sync/recreate", WanDeviceRegistry.class);

        assertThat(recreating.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.RECREATING);
        assertThat(recreating.getDeletionConnectionId()).isEqualTo(connection.getId());
        assertThat(recreating.getDeletionExternalId()).isEqualTo("8C3F74C81C703030");
        assertThat(recreating.getExternalId()).isEqualTo("8C3F74C81C703031");
        assertThat(JacksonUtil.toJsonNode(jdbcTemplate.queryForObject(
                "SELECT configuration FROM wan_device_registry WHERE device_id = ?",
                String.class, device.getId().getId())).path("gateway").path("freqMajor").asInt()).isEqualTo(5);
    }

    @Test
    public void preservesDeletionTombstoneAfterLocalDeviceIsDeleted() throws Exception {
        WanConnection connection = saveConnection("Deletion NS");
        DeviceProfile profile = saveWanProfile("Deletion Profile", connection);
        Device device = doPost("/api/device",
                gateway("Deleted Gateway", profile, "8C3F74C81C703032"), Device.class);
        setSyncState(device, WanDeviceSyncStatus.ACTIVE, null, 123L);

        doDelete("/api/device/" + device.getId().getId()).andExpect(status().isOk());

        WanDeviceRegistry tombstone = registryService.findByDeviceId(device.getId());
        assertThat(tombstone).isNotNull();
        assertThat(tombstone.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.DELETING);
        assertThat(tombstone.getDeletionConnectionId()).isEqualTo(connection.getId());
        assertThat(tombstone.getDeletionExternalId()).isEqualTo("8C3F74C81C703032");
        assertThat(tombstone.getConfiguration()).contains("8C3F74C81C703032");
        doGet("/api/device/" + device.getId().getId()).andExpect(status().isNotFound());
    }

    @Test
    public void atomicallyClaimsAndRecoversExpiredSynchronizationTask() throws Exception {
        long now = 1_000_000L;
        WanConnection connection = saveConnection("Task Claim NS");
        DeviceProfile profile = saveWanProfile("Task Claim Profile", connection);
        Device device = doPost("/api/device",
                gateway("Task Claim Gateway", profile, "8C3F74C81C703033"), Device.class);

        wanConnectionService.claimEnabledWanConnections("owner-a", now, now + 60_000L);
        assertThat(registryManager.claimTask(device.getId(), "owner-b", now, now + 60_000L)).isNull();
        List<WanDeviceRegistry> firstClaim = registryManager.claimAvailableTasks(
                "owner-a", now, now + 60_000L, 10);
        assertThat(firstClaim).extracting(WanDeviceRegistry::getDeviceId).contains(device.getId());
        assertThat(registryManager.claimTask(device.getId(), "owner-b", now, now + 60_000L)).isNull();

        WanDeviceRegistry syncing = registryManager.update(device.getId(), WanDeviceSyncStatus.SYNCING,
                null, null, null, null, null, false, false, "owner-a", now + 1);
        assertThat(syncing.getLockOwnerId()).isEqualTo("owner-a");
        WanDeviceRegistry active = registryManager.update(device.getId(), WanDeviceSyncStatus.ACTIVE,
                null, null, null, null, null, false, false, "owner-a", now + 2);
        assertThat(active.getNextSyncTime()).isBetween(
                now + 2 + 24L * 3_600_000L,
                now + 2 + 25L * 3_600_000L);

        jdbcTemplate.update("""
                        UPDATE wan_device_registry
                        SET sync_status = 'SYNCING', next_sync_time = NULL,
                            lock_owner_id = 'dead-owner', lock_until = ?
                        WHERE device_id = ?
                        """, now, device.getId().getId());
        wanConnectionService.claimEnabledWanConnections("owner-b", now + 60_000L, now + 120_000L);
        List<WanDeviceRegistry> recovered = registryManager.claimAvailableTasks(
                "owner-b", now + 60_000L, now + 120_000L, 10);
        WanDeviceRegistry recoveredDevice = recovered.stream()
                .filter(registry -> registry.getDeviceId().equals(device.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(recoveredDevice.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
        assertThat(recoveredDevice.getLockOwnerId()).isEqualTo("owner-b");
    }

    private WanDeviceRegistry setSyncState(Device device, WanDeviceSyncStatus status,
                                           String error, Long lastSuccessfulSyncTime) {
        WanDeviceRegistry registry = registryService.findByDeviceId(tenantId, device.getId());
        registry.setSyncStatus(status);
        registry.setError(error);
        registry.setLastSuccessfulSyncTime(lastSuccessfulSyncTime);
        return registryService.save(registry);
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
