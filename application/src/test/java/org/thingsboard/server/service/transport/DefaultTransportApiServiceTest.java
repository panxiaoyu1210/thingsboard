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
package org.thingsboard.server.service.transport;


import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.cache.ota.OtaPackageDataCache;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceProfileProvisionType;
import org.thingsboard.server.common.data.device.data.DefaultDeviceConfiguration;
import org.thingsboard.server.common.data.device.data.DefaultDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.data.DeviceData;
import org.thingsboard.server.common.data.device.profile.DeviceProfileData;
import org.thingsboard.server.common.data.device.profile.X509CertificateChainProvisionConfiguration;
import org.thingsboard.server.common.data.device.credentials.WanDeviceCredentials;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.common.data.wan.WanConnection;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.msg.EncryptionUtil;
import org.thingsboard.server.dao.device.DeviceCredentialsService;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceProvisionService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.device.provision.ProvisionResponse;
import org.thingsboard.server.dao.device.provision.ProvisionResponseStatus;
import org.thingsboard.server.dao.ota.OtaPackageService;
import org.thingsboard.server.dao.queue.QueueService;
import org.thingsboard.server.dao.relation.RelationService;
import org.thingsboard.server.dao.resource.ResourceService;
import org.thingsboard.server.dao.tenant.TbTenantProfileCache;
import org.thingsboard.server.dao.wan.WanConnectionService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.service.apiusage.TbApiUsageStateService;
import org.thingsboard.server.service.wan.WanDeviceRegistryManager;
import org.thingsboard.server.service.executors.DbCallbackExecutorService;
import org.thingsboard.server.service.profile.TbDeviceProfileCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Slf4j
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = DefaultTransportApiServiceTest.TestConfig.class)
public class DefaultTransportApiServiceTest {

    @Configuration
    static class TestConfig {
        @Bean
        public DefaultTransportApiService defaultTransportApiService(TbDeviceProfileCache deviceProfileCache,
                                                                     TbTenantProfileCache tenantProfileCache,
                                                                     TbApiUsageStateService apiUsageStateService,
                                                                     DeviceService deviceService,
                                                                     DeviceProfileService deviceProfileService,
                                                                     RelationService relationService,
                                                                     DeviceCredentialsService deviceCredentialsService,
                                                                     TbClusterService tbClusterService,
                                                                     DeviceProvisionService deviceProvisionService,
                                                                     ResourceService resourceService,
                                                                     OtaPackageService otaPackageService,
                                                                     OtaPackageDataCache otaPackageDataCache,
                                                                     QueueService queueService,
                                                                     WanConnectionService wanConnectionService,
                                                                     WanDeviceRegistryManager wanDeviceRegistryManager) {
            return new DefaultTransportApiService(deviceProfileCache, tenantProfileCache, apiUsageStateService,
                    deviceService, deviceProfileService, relationService, deviceCredentialsService, tbClusterService,
                    deviceProvisionService, resourceService, otaPackageService, otaPackageDataCache, queueService,
                    wanConnectionService, wanDeviceRegistryManager);
        }
    }

    @MockitoBean
    protected TbDeviceProfileCache deviceProfileCache;
    @MockitoBean
    protected TbTenantProfileCache tenantProfileCache;
    @MockitoBean
    protected TbApiUsageStateService apiUsageStateService;
    @MockitoBean
    protected DeviceService deviceService;
    @MockitoBean
    protected DeviceProfileService deviceProfileService;
    @MockitoBean
    protected RelationService relationService;
    @MockitoBean
    protected DeviceCredentialsService deviceCredentialsService;
    @MockitoBean
    protected DbCallbackExecutorService dbCallbackExecutorService;
    @MockitoBean
    protected TbClusterService tbClusterService;
    @MockitoBean
    protected DeviceProvisionService deviceProvisionService;
    @MockitoBean
    protected ResourceService resourceService;
    @MockitoBean
    protected OtaPackageService otaPackageService;
    @MockitoBean
    protected OtaPackageDataCache otaPackageDataCache;
    @MockitoBean
    protected QueueService queueService;
    @MockitoBean
    protected WanConnectionService wanConnectionService;
    @MockitoBean
    protected WanDeviceRegistryManager wanDeviceRegistryManager;
    @MockitoSpyBean
    DefaultTransportApiService service;

    private String certificateChain;
    private String[] chain;

    @Before
    public void setUp() {

        String filePath = "src/test/resources/provision/x509ChainProvisionTest.pem";
        try {
            certificateChain = Files.readString(Paths.get(filePath));
            certificateChain = certTrimNewLinesForChainInDeviceProfile(certificateChain);
            chain = fetchLeafCertificateFromChain(certificateChain);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void validateExistingDeviceByX509CertificateStrategy() {
        var device = createDevice();

        var deviceCredentials = createDeviceCredentials(chain[0], device.getId());
        when(deviceCredentialsService.findDeviceCredentialsByCredentialsId(any())).thenReturn(deviceCredentials);

        TransportProtos.TransportApiResponseMsg response = mock(TransportProtos.TransportApiResponseMsg.class);
        willReturn(response).given(service).getDeviceInfo(deviceCredentials);

        service.validateOrCreateDeviceX509Certificate(certificateChain);
        verify(deviceCredentialsService, times(1)).findDeviceCredentialsByCredentialsId(any());
    }

    @Test
    public void provisionDeviceX509Certificate() {
        var deviceProfile = createDeviceProfile(chain[1]);
        when(deviceProfileService.findDeviceProfileByProvisionDeviceKey(any())).thenReturn(deviceProfile);

        var device = createDevice();
        when(deviceService.findDeviceByTenantIdAndName(any(), any())).thenReturn(device);

        var deviceCredentials = createDeviceCredentials(chain[0], device.getId());
        when(deviceCredentialsService.findDeviceCredentialsByCredentialsId(any())).thenReturn(null);
        when(deviceCredentialsService.updateDeviceCredentials(any(), any())).thenReturn(deviceCredentials);

        var provisionResponse = createProvisionResponse(deviceCredentials);
        when(deviceProvisionService.provisionDeviceViaX509Chain(any(), any())).thenReturn(provisionResponse);

        TransportProtos.TransportApiResponseMsg response = mock(TransportProtos.TransportApiResponseMsg.class);
        willReturn(response).given(service).getDeviceInfo(deviceCredentials);

        service.validateOrCreateDeviceX509Certificate(certificateChain);
        verify(deviceProfileService, times(1)).findDeviceProfileByProvisionDeviceKey(any());
        verify(service, times(1)).getDeviceInfo(any());
        verify(deviceCredentialsService, times(1)).findDeviceCredentialsByCredentialsId(any());
        verify(deviceProvisionService, times(1)).provisionDeviceViaX509Chain(any(), any());
    }

    @Test
    public void getWanConnectionsThroughTransportApi() {
        UUID connectionId = UUID.randomUUID();
        UUID tenantUuid = UUID.randomUUID();
        WanConnection connection = new WanConnection();
        connection.setId(connectionId);
        connection.setTenantId(TenantId.fromUUID(tenantUuid));
        connection.setName("WAN NS");
        connection.setBrokerHost("mqtt.example.org");
        connection.setBrokerPort(1883);
        connection.setClientId("wan-transport-test");
        connection.setUsername("user");
        connection.setEncryptedPassword("v1:test-ciphertext");
        connection.setNsPublishTopic("ns/publish");
        connection.setNsSubscribeTopic("ns/subscribe");
        connection.setQos(1);
        connection.setEnabled(true);
        connection.setRequestTimeoutMs(5_000);
        connection.setSyncIntervalHours(24);
        connection.setVersion(3L);
        when(wanConnectionService.findEnabledWanConnections(any(PageLink.class)))
                .thenReturn(new PageData<>(List.of(connection), 1, 1, false));

        TransportProtos.TransportApiResponseMsg response = service.handle(
                TransportProtos.GetWanConnectionsRequestMsg.newBuilder().setPage(0).setPageSize(100).build());

        TransportProtos.WanConnectionProto proto = response.getWanConnectionsResponseMsg().getConnections(0);
        Assert.assertEquals(connectionId, new UUID(proto.getConnectionIdMSB(), proto.getConnectionIdLSB()));
        Assert.assertEquals(tenantUuid, new UUID(proto.getTenantIdMSB(), proto.getTenantIdLSB()));
        Assert.assertEquals("mqtt.example.org", proto.getBrokerHost());
        Assert.assertEquals("v1:test-ciphertext", proto.getEncryptedPassword());
        Assert.assertFalse(response.getWanConnectionsResponseMsg().getHasNextPage());
    }

    @Test
    public void claimAndReleaseWanConnectionsThroughTransportApi() {
        long now = 1_000_000L;
        long leaseUntil = now + 60_000L;
        WanConnection connection = new WanConnection();
        connection.setId(UUID.randomUUID());
        connection.setTenantId(TenantId.fromUUID(UUID.randomUUID()));
        connection.setName("Owned WAN NS");
        connection.setBrokerHost("mqtt.example.org");
        connection.setBrokerPort(1883);
        connection.setClientId("wan-owner-test");
        connection.setNsPublishTopic("ns/publish");
        connection.setNsSubscribeTopic("ns/subscribe");
        connection.setQos(1);
        connection.setEnabled(true);
        connection.setRequestTimeoutMs(5_000);
        connection.setSyncEnabled(true);
        connection.setSyncIntervalHours(24);
        connection.setVersion(1L);
        when(wanConnectionService.claimEnabledWanConnections("owner-a", now, leaseUntil))
                .thenReturn(List.of(connection));

        TransportProtos.TransportApiResponseMsg claimResponse = service.handle(
                TransportProtos.GetWanConnectionsRequestMsg.newBuilder()
                        .setOwnerId("owner-a")
                        .setNow(now)
                        .setLeaseUntil(leaseUntil)
                        .build());
        service.handle(TransportProtos.GetWanConnectionsRequestMsg.newBuilder()
                .setOwnerId("owner-a")
                .setReleaseOwnership(true)
                .build());

        Assert.assertEquals(connection.getId(), new UUID(
                claimResponse.getWanConnectionsResponseMsg().getConnections(0).getConnectionIdMSB(),
                claimResponse.getWanConnectionsResponseMsg().getConnections(0).getConnectionIdLSB()));
        verify(wanConnectionService).claimEnabledWanConnections("owner-a", now, leaseUntil);
        verify(wanConnectionService).releaseWanConnections("owner-a");
    }

    @Test
    public void getWanDeviceIdsThroughTransportApi() {
        UUID deviceId = UUID.randomUUID();
        when(deviceService.findDevicesIdsByDeviceProfileTransportType(any(), any(PageLink.class)))
                .thenReturn(new PageData<>(List.of(deviceId), 1, 1, false));

        TransportProtos.TransportApiResponseMsg response = service.handle(
                TransportProtos.GetWanDevicesRequestMsg.newBuilder().setPage(0).setPageSize(100).build());

        Assert.assertEquals(List.of(deviceId.toString()), response.getWanDevicesResponseMsg().getIdsList());
    }

    @Test
    public void getPendingWanDeviceRegistriesThroughTransportApi() {
        UUID deviceId = UUID.randomUUID();
        UUID tenantUuid = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        WanDeviceRegistry registry = new WanDeviceRegistry();
        registry.setDeviceId(new DeviceId(deviceId));
        registry.setTenantId(TenantId.fromUUID(tenantUuid));
        registry.setConnectionId(connectionId);
        registry.setDeviceType(WanDeviceType.TERMINAL);
        registry.setExternalId("0000000000001001");
        registry.setDeviceName("Terminal One");
        registry.setConfiguration("{\"type\":\"WAN\"}");
        registry.setSyncStatus(WanDeviceSyncStatus.PENDING);
        registry.setVersion(2L);
        when(wanDeviceRegistryManager.findByStatus(eq(WanDeviceSyncStatus.PENDING), any(PageLink.class)))
                .thenReturn(new PageData<>(List.of(registry), 1, 1, false));
        WanDeviceCredentials secret = new WanDeviceCredentials();
        secret.setRootKey("0102030405060708090A0B0C0D0E0F10");
        DeviceCredentials credentials = new DeviceCredentials();
        credentials.setCredentialsType(DeviceCredentialsType.WAN_CREDENTIALS);
        credentials.setCredentialsValue(JacksonUtil.toString(secret));
        when(deviceCredentialsService.findDeviceCredentialsByDeviceId(registry.getTenantId(), registry.getDeviceId()))
                .thenReturn(credentials);

        TransportProtos.TransportApiResponseMsg response = service.handle(
                TransportProtos.GetPendingWanDeviceRegistriesRequestMsg.newBuilder()
                        .setPage(0).setPageSize(100).build());

        TransportProtos.WanDeviceRegistryProto proto =
                response.getPendingWanDeviceRegistriesResponseMsg().getRegistries(0);
        Assert.assertEquals(deviceId, new UUID(proto.getDeviceIdMSB(), proto.getDeviceIdLSB()));
        Assert.assertEquals(connectionId, new UUID(proto.getConnectionIdMSB(), proto.getConnectionIdLSB()));
        Assert.assertEquals("PENDING", proto.getSyncStatus());
        Assert.assertEquals("{\"type\":\"WAN\"}", proto.getConfiguration());
        Assert.assertFalse(proto.hasTerminalRootKey());
    }

    @Test
    public void claimAndReleaseWanDeviceTaskThroughTransportApi() {
        long now = 1_000_000L;
        long leaseUntil = now + 60_000L;
        UUID deviceUuid = UUID.randomUUID();
        WanDeviceRegistry registry = new WanDeviceRegistry();
        registry.setDeviceId(new DeviceId(deviceUuid));
        registry.setTenantId(TenantId.fromUUID(UUID.randomUUID()));
        registry.setConnectionId(UUID.randomUUID());
        registry.setDeviceType(WanDeviceType.GATEWAY);
        registry.setExternalId("8C3F74C81C703000");
        registry.setDeviceName("Claimed Gateway");
        registry.setConfiguration("{\"type\":\"WAN\"}");
        registry.setSyncStatus(WanDeviceSyncStatus.PENDING);
        registry.setVersion(1L);
        when(wanDeviceRegistryManager.claimTask(new DeviceId(deviceUuid), "owner-a", now, leaseUntil))
                .thenReturn(registry);
        when(wanDeviceRegistryManager.claimAvailableTasks("owner-a", now, leaseUntil, 7))
                .thenReturn(List.of(registry));
        when(wanDeviceRegistryManager.releaseTask(new DeviceId(deviceUuid), "owner-a"))
                .thenReturn(registry);

        TransportProtos.TransportApiResponseMsg singleClaim = service.handle(
                TransportProtos.GetWanDeviceRegistryRequestMsg.newBuilder()
                        .setDeviceIdMSB(deviceUuid.getMostSignificantBits())
                        .setDeviceIdLSB(deviceUuid.getLeastSignificantBits())
                        .setOwnerId("owner-a")
                        .setNow(now)
                        .setLeaseUntil(leaseUntil)
                        .build());
        TransportProtos.TransportApiResponseMsg batchClaim = service.handle(
                TransportProtos.GetPendingWanDeviceRegistriesRequestMsg.newBuilder()
                        .setPageSize(7)
                        .setOwnerId("owner-a")
                        .setNow(now)
                        .setLeaseUntil(leaseUntil)
                        .setClaimAvailable(true)
                        .build());
        service.handle(TransportProtos.UpdateWanDeviceRegistryRequestMsg.newBuilder()
                .setDeviceIdMSB(deviceUuid.getMostSignificantBits())
                .setDeviceIdLSB(deviceUuid.getLeastSignificantBits())
                .setLockOwnerId("owner-a")
                .setReleaseTask(true)
                .build());

        Assert.assertEquals(deviceUuid, new UUID(
                singleClaim.getWanDeviceRegistryResponseMsg().getRegistry().getDeviceIdMSB(),
                singleClaim.getWanDeviceRegistryResponseMsg().getRegistry().getDeviceIdLSB()));
        Assert.assertEquals(1, batchClaim.getPendingWanDeviceRegistriesResponseMsg().getRegistriesCount());
        verify(wanDeviceRegistryManager).claimTask(new DeviceId(deviceUuid), "owner-a", now, leaseUntil);
        verify(wanDeviceRegistryManager).claimAvailableTasks("owner-a", now, leaseUntil, 7);
        verify(wanDeviceRegistryManager).releaseTask(new DeviceId(deviceUuid), "owner-a");
    }

    @Test
    public void updateWanDeviceRegistryThroughTransportApi() {
        UUID deviceUuid = UUID.randomUUID();
        WanDeviceRegistry registry = new WanDeviceRegistry();
        registry.setDeviceId(new DeviceId(deviceUuid));
        registry.setTenantId(TenantId.fromUUID(UUID.randomUUID()));
        registry.setConnectionId(UUID.randomUUID());
        registry.setDeviceType(WanDeviceType.GATEWAY);
        registry.setExternalId("8C3F74C81C703000");
        registry.setDeviceName("Gateway One");
        registry.setConfiguration("{\"type\":\"WAN\"}");
        registry.setSyncStatus(WanDeviceSyncStatus.ACTIVE);
        registry.setLastSyncTime(123L);
        registry.setLastSuccessfulSyncTime(122L);
        registry.setVersion(3L);
        when(wanDeviceRegistryManager.update(
                any(DeviceId.class), any(WanDeviceSyncStatus.class), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), anyLong()))
                .thenReturn(registry);

        TransportProtos.TransportApiResponseMsg response = service.handle(
                TransportProtos.UpdateWanDeviceRegistryRequestMsg.newBuilder()
                        .setDeviceIdMSB(deviceUuid.getMostSignificantBits())
                        .setDeviceIdLSB(deviceUuid.getLeastSignificantBits())
                        .setSyncStatus("ACTIVE")
                        .setGatewayConfiguration("{\"gwId\":\"8C3F74C81C703000\"}")
                        .setOperationTime(456L)
                        .build());

        Assert.assertEquals("ACTIVE", response.getWanDeviceRegistryResponseMsg().getRegistry().getSyncStatus());
        Assert.assertEquals(123L, response.getWanDeviceRegistryResponseMsg().getRegistry().getLastSyncTime());
        Assert.assertEquals(122L,
                response.getWanDeviceRegistryResponseMsg().getRegistry().getLastSuccessfulSyncTime());
        verify(wanDeviceRegistryManager).update(
                eq(new DeviceId(deviceUuid)), eq(WanDeviceSyncStatus.ACTIVE), isNull(),
                eq("{\"gwId\":\"8C3F74C81C703000\"}"), isNull(), isNull(), isNull(),
                eq(false), eq(false), isNull(), eq(456L));
    }

    @Test
    public void getDeviceForTransportIncludesCompleteRoutingInfo() {
        UUID deviceUuid = UUID.randomUUID();
        UUID tenantUuid = UUID.randomUUID();
        UUID profileUuid = UUID.randomUUID();
        Device device = new Device(new DeviceId(deviceUuid));
        device.setTenantId(TenantId.fromUUID(tenantUuid));
        device.setCustomerId(new CustomerId(EntityId.NULL_UUID));
        device.setDeviceProfileId(new DeviceProfileId(profileUuid));
        device.setName("WAN Terminal");
        device.setType("default");
        device.setAdditionalInfo(JacksonUtil.newObjectNode());
        DeviceData deviceData = new DeviceData();
        deviceData.setConfiguration(new DefaultDeviceConfiguration());
        deviceData.setTransportConfiguration(new DefaultDeviceTransportConfiguration());
        device.setDeviceData(deviceData);
        when(deviceService.findDeviceById(TenantId.SYS_TENANT_ID, device.getId())).thenReturn(device);

        TransportProtos.TransportApiResponseMsg response = service.handle(
                TransportProtos.GetDeviceRequestMsg.newBuilder()
                        .setDeviceIdMSB(deviceUuid.getMostSignificantBits())
                        .setDeviceIdLSB(deviceUuid.getLeastSignificantBits())
                        .build());

        Assert.assertTrue(response.getDeviceResponseMsg().hasDeviceInfo());
        TransportProtos.DeviceInfoProto info = response.getDeviceResponseMsg().getDeviceInfo();
        Assert.assertEquals(deviceUuid, new UUID(info.getDeviceIdMSB(), info.getDeviceIdLSB()));
        Assert.assertEquals(tenantUuid, new UUID(info.getTenantIdMSB(), info.getTenantIdLSB()));
        Assert.assertEquals(profileUuid,
                new UUID(info.getDeviceProfileIdMSB(), info.getDeviceProfileIdLSB()));
        Assert.assertEquals("WAN Terminal", info.getDeviceName());
        Assert.assertEquals("default", info.getDeviceType());
    }

    private DeviceProfile createDeviceProfile(String certificateValue) {
        X509CertificateChainProvisionConfiguration provision = new X509CertificateChainProvisionConfiguration();
        provision.setProvisionDeviceSecret(certificateValue);
        provision.setCertificateRegExPattern("([^@]+)");
        provision.setAllowCreateNewDevicesByX509Certificate(true);

        DeviceProfileData deviceProfileData = new DeviceProfileData();
        deviceProfileData.setProvisionConfiguration(provision);

        DeviceProfile deviceProfile = new DeviceProfile();
        deviceProfile.setProfileData(deviceProfileData);
        deviceProfile.setProvisionDeviceKey(EncryptionUtil.getSha3Hash(certificateValue));
        deviceProfile.setProvisionType(DeviceProfileProvisionType.X509_CERTIFICATE_CHAIN);
        return deviceProfile;
    }

    private DeviceCredentials createDeviceCredentials(String certificateValue, DeviceId deviceId) {
        DeviceCredentials deviceCredentials = new DeviceCredentials();
        deviceCredentials.setDeviceId(deviceId);
        deviceCredentials.setCredentialsValue(certificateValue);
        deviceCredentials.setCredentialsId(EncryptionUtil.getSha3Hash(certificateValue));
        deviceCredentials.setCredentialsType(DeviceCredentialsType.X509_CERTIFICATE);
        return deviceCredentials;
    }

    private Device createDevice() {
        Device device = new Device();
        device.setId(new DeviceId(UUID.randomUUID()));
        return device;
    }

    private ProvisionResponse createProvisionResponse(DeviceCredentials deviceCredentials) {
        return new ProvisionResponse(deviceCredentials, ProvisionResponseStatus.SUCCESS);
    }

    public static String certTrimNewLinesForChainInDeviceProfile(String input) {
        return input.replaceAll("\n", "")
                .replaceAll("\r", "")
                .replaceAll("-----BEGIN CERTIFICATE-----", "-----BEGIN CERTIFICATE-----\n")
                .replaceAll("-----END CERTIFICATE-----", "\n-----END CERTIFICATE-----\n")
                .trim();
    }

    private String[] fetchLeafCertificateFromChain(String value) {
        List<String> chain = new ArrayList<>();
        String regex = "-----BEGIN CERTIFICATE-----\\s*.*?\\s*-----END CERTIFICATE-----";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            chain.add(matcher.group(0));
        }
        return chain.toArray(new String[0]);
    }
}
