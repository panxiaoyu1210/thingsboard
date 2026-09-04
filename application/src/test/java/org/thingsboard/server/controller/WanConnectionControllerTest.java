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

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.device.profile.WanDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.wan.WanConnection;
import org.thingsboard.server.common.data.wan.WanConnectionTestResult;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.server.dao.wan.WanConnectionService;
import org.thingsboard.server.service.wan.WanConnectionTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DaoSqlTest
public class WanConnectionControllerTest extends AbstractControllerTest {

    private static final String PASSWORD = "wan-test-secret";

    @Autowired
    private WanConnectionService wanConnectionService;

    @MockitoBean
    private WanConnectionTester wanConnectionTester;

    @Before
    public void login() throws Exception {
        loginTenantAdmin();
        Mockito.reset(wanConnectionTester);
    }

    @Test
    public void testWanConnectionCrudPaginationAndPasswordProtection() throws Exception {
        WanConnection first = saveConnection("Primary NS", PASSWORD);
        WanConnection second = saveConnection("Backup NS", null);

        assertThat(first.getId()).isNotNull();
        assertThat(first.getTenantId()).isEqualTo(tenantId);
        assertThat(first.getPassword()).isEqualTo(WanConnection.PASSWORD_MASK);
        assertThat(first.isPasswordSet()).isTrue();

        String persistedPassword = jdbcTemplate.queryForObject(
                "SELECT encrypted_password FROM wan_connection WHERE id = ?", String.class, first.getId());
        assertThat(persistedPassword).startsWith("v1:").doesNotContain(PASSWORD);

        WanConnection found = doGet("/api/wan/connection/" + first.getId(), WanConnection.class);
        assertThat(found.getPassword()).isEqualTo(WanConnection.PASSWORD_MASK);
        assertThat(found.getEncryptedPassword()).isNull();

        PageData<WanConnection> page = doGetTyped(
                "/api/wan/connections?pageSize=10&page=0&textSearch=NS&sortProperty=name&sortOrder=ASC",
                new TypeReference<>() {
                });
        assertThat(page.getData()).extracting(WanConnection::getName)
                .containsExactly("Backup NS", "Primary NS");

        found.setName("Primary NS updated");
        found.setPassword(null);
        WanConnection updated = doPost("/api/wan/connection", found, WanConnection.class);
        assertThat(updated.getName()).isEqualTo("Primary NS updated");
        assertThat(updated.getPassword()).isEqualTo(WanConnection.PASSWORD_MASK);
        assertThat(wanConnectionService.findWanConnectionWithCredentials(tenantId, first.getId()).getPassword())
                .isEqualTo(PASSWORD);

        updated.setPassword("replacement-secret");
        updated = doPost("/api/wan/connection", updated, WanConnection.class);
        assertThat(wanConnectionService.findWanConnectionWithCredentials(tenantId, first.getId()).getPassword())
                .isEqualTo("replacement-secret");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT encrypted_password FROM wan_connection WHERE id = ?", String.class, first.getId()))
                .doesNotContain("replacement-secret");

        updated.setPassword("");
        updated = doPost("/api/wan/connection", updated, WanConnection.class);
        assertThat(updated.isPasswordSet()).isFalse();
        assertThat(updated.getPassword()).isNull();
        assertThat(wanConnectionService.findWanConnectionWithCredentials(tenantId, first.getId()).getPassword())
                .isNull();

        doDelete("/api/wan/connection/" + first.getId()).andExpect(status().isOk());
        doDelete("/api/wan/connection/" + second.getId()).andExpect(status().isOk());
        doGet("/api/wan/connection/" + first.getId()).andExpect(status().isNotFound());
    }

    @Test
    public void testWanConnectionPermissionsAndTenantIsolation() throws Exception {
        WanConnection saved = saveConnection("Tenant NS", PASSWORD);

        loginDifferentTenant();
        doGet("/api/wan/connection/" + saved.getId()).andExpect(status().isNotFound());
        doPost("/api/wan/connection", saved)
                .andExpect(status().isBadRequest())
                .andExpect(statusReason(containsString("current tenant")));
        doDelete("/api/wan/connection/" + saved.getId())
                .andExpect(status().isBadRequest())
                .andExpect(statusReason(containsString("current tenant")));

        loginCustomerUser();
        doPost("/api/wan/connection", newConnection("Forbidden NS", null))
                .andExpect(status().isForbidden());

        loginTenantAdmin();
        doDelete("/api/wan/connection/" + saved.getId()).andExpect(status().isOk());
    }

    @Test
    public void testWanConnectionValidationAndUniqueName() throws Exception {
        WanConnection saved = saveConnection("Unique NS", null);

        WanConnection duplicate = newConnection("unique ns", null);
        doPost("/api/wan/connection", duplicate)
                .andExpect(status().isBadRequest())
                .andExpect(statusReason(containsString("already exists")));

        WanConnection invalidHost = newConnection("Invalid host", null);
        invalidHost.setBrokerHost("http://127.0.0.1/path");
        doPost("/api/wan/connection", invalidHost)
                .andExpect(status().isBadRequest())
                .andExpect(statusReason(containsString("broker host")));

        doDelete("/api/wan/connection/" + saved.getId()).andExpect(status().isOk());
    }

    @Test
    public void testWanConnectionTestUsesSavedPasswordAndReturnsExplicitResult() throws Exception {
        WanConnection saved = saveConnection("Testable NS", PASSWORD);
        saved.setPassword(null);

        Mockito.when(wanConnectionTester.test(Mockito.any())).thenReturn(WanConnectionTestResult.connected());
        WanConnectionTestResult success = doPost(
                "/api/wan/connection/test", saved, WanConnectionTestResult.class);
        assertThat(success.success()).isTrue();
        assertThat(success.code()).isEqualTo("CONNECTED");

        ArgumentCaptor<WanConnection> connectionCaptor = ArgumentCaptor.forClass(WanConnection.class);
        Mockito.verify(wanConnectionTester).test(connectionCaptor.capture());
        assertThat(connectionCaptor.getValue().getPassword()).isEqualTo(PASSWORD);

        Mockito.when(wanConnectionTester.test(Mockito.any())).thenReturn(
                WanConnectionTestResult.failure("CONNECTION_FAILED", "WAN NS broker connection failed"));
        WanConnectionTestResult failure = doPost(
                "/api/wan/connection/test", saved, WanConnectionTestResult.class);
        assertThat(failure.success()).isFalse();
        assertThat(failure.code()).isEqualTo("CONNECTION_FAILED");

        doDelete("/api/wan/connection/" + saved.getId()).andExpect(status().isOk());
    }

    @Test
    public void testWanProfileTenantReferenceAndDeleteProtection() throws Exception {
        WanConnection saved = saveConnection("Profile NS", PASSWORD);
        WanDeviceProfileTransportConfiguration configuration = new WanDeviceProfileTransportConfiguration();
        configuration.setConnectionId(saved.getId());
        DeviceProfile profile = doPost("/api/deviceProfile",
                createDeviceProfile("WAN Profile", configuration), DeviceProfile.class);

        doDelete("/api/wan/connection/" + saved.getId())
                .andExpect(status().isBadRequest())
                .andExpect(statusReason(containsString("device profile")));

        loginDifferentTenant();
        doPost("/api/deviceProfile", createDeviceProfile("Cross tenant WAN Profile", configuration))
                .andExpect(status().isBadRequest())
                .andExpect(statusReason(containsString("current tenant")));

        loginTenantAdmin();
        doDelete("/api/deviceProfile/" + profile.getId().getId()).andExpect(status().isOk());
        doDelete("/api/wan/connection/" + saved.getId()).andExpect(status().isOk());
    }

    private WanConnection saveConnection(String name, String password) {
        return doPost("/api/wan/connection", newConnection(name, password), WanConnection.class);
    }

    private WanConnection newConnection(String name, String password) {
        WanConnection connection = new WanConnection();
        connection.setName(name);
        connection.setBrokerHost("mqtt.example.org");
        connection.setBrokerPort(1883);
        connection.setClientId("tb-" + name.replace(" ", "-").toLowerCase());
        connection.setUsername("tenant-user");
        connection.setPassword(password);
        connection.setNsPublishTopic("turmass/ns/publish");
        connection.setNsSubscribeTopic("turmass/ns/subscribe");
        connection.setQos(1);
        connection.setEnabled(true);
        connection.setRequestTimeoutMs(5_000);
        connection.setSyncIntervalHours(24);
        return connection;
    }

}
