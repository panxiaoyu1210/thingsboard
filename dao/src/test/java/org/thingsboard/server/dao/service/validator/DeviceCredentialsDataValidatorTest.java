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
package org.thingsboard.server.dao.service.validator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.device.credentials.BasicMqttCredentials;
import org.thingsboard.server.common.data.device.credentials.WanDeviceCredentials;
import org.thingsboard.server.common.data.device.data.DeviceData;
import org.thingsboard.server.common.data.device.data.WanDeviceTransportConfiguration;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanTerminalConfiguration;
import org.thingsboard.server.dao.device.DeviceCredentialsDao;
import org.thingsboard.server.dao.device.DeviceDao;
import org.thingsboard.server.dao.exception.DeviceCredentialsValidationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willReturn;

@ExtendWith(MockitoExtension.class)
class DeviceCredentialsDataValidatorTest {

    @Mock
    DeviceCredentialsDao deviceCredentialsDao;
    @Mock
    DeviceDao deviceDao;
    @InjectMocks
    DeviceCredentialsDataValidator validator;

    final TenantId tenantId = TenantId.fromUUID(UUID.fromString("9ef79cdf-37a8-4119-b682-2e7ed4e018da"));
    final DeviceId deviceId = new DeviceId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

    @Test
    void rejectsNewlineInAccessToken() {
        DeviceCredentials creds = accessToken("safe_token\nentrypoint: [\"/bin/sh\"]");

        assertThatThrownBy(() -> validator.validateDataImpl(tenantId, creds))
                .isInstanceOf(DeviceCredentialsValidationException.class)
                .hasMessageContaining("credentialsId")
                .hasMessageContaining("control characters");
    }

    @Test
    void rejectsCarriageReturnInAccessToken() {
        DeviceCredentials creds = accessToken("token\rprivileged: true");

        assertThatThrownBy(() -> validator.validateDataImpl(tenantId, creds))
                .isInstanceOf(DeviceCredentialsValidationException.class)
                .hasMessageContaining("control characters");
    }

    @Test
    void rejectsNewlineInMqttClientId() {
        DeviceCredentials creds = mqttBasic("cid\nentrypoint: x", "user", "pwd");

        assertThatThrownBy(() -> validator.validateDataImpl(tenantId, creds))
                .isInstanceOf(DeviceCredentialsValidationException.class)
                .hasMessageContaining("clientId");
    }

    @Test
    void rejectsNewlineInMqttUserName() {
        DeviceCredentials creds = mqttBasic("cid", "user\nprivileged: true", "pwd");

        assertThatThrownBy(() -> validator.validateDataImpl(tenantId, creds))
                .isInstanceOf(DeviceCredentialsValidationException.class)
                .hasMessageContaining("userName");
    }

    @Test
    void rejectsNewlineInMqttPassword() {
        DeviceCredentials creds = mqttBasic("cid", "user", "pwd\nentrypoint: x");

        assertThatThrownBy(() -> validator.validateDataImpl(tenantId, creds))
                .isInstanceOf(DeviceCredentialsValidationException.class)
                .hasMessageContaining("password");
    }

    @Test
    void acceptsValidCredentials() {
        willReturn(new Device()).given(deviceDao).findById(tenantId, deviceId.getId());
        DeviceCredentials creds = accessToken("safe_token_123");

        assertThatCode(() -> validator.validateDataImpl(tenantId, creds))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsValidMqttBasicCredentials() {
        willReturn(new Device()).given(deviceDao).findById(tenantId, deviceId.getId());
        DeviceCredentials creds = mqttBasic("client-1", "user-1", "pwd-1");

        assertThatCode(() -> validator.validateDataImpl(tenantId, creds))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsValidWanTerminalCredentials() {
        willReturn(wanTerminal("0000000000001001", 5)).given(deviceDao).findById(tenantId, deviceId.getId());
        DeviceCredentials credentials = wanCredentials(
                "0000000000001001", "0102030405060708090A0B0C0D0E0F10");

        assertThatCode(() -> validator.validateDataImpl(tenantId, credentials))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsEmptyRootKeyForWanTerminalWithoutSecurity() {
        willReturn(wanTerminal("0000000000001001", 0)).given(deviceDao).findById(tenantId, deviceId.getId());
        DeviceCredentials credentials = wanCredentials("0000000000001001", null);

        assertThatCode(() -> validator.validateDataImpl(tenantId, credentials))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingRootKeyForSecuredWanTerminal() {
        willReturn(wanTerminal("0000000000001001", 4)).given(deviceDao).findById(tenantId, deviceId.getId());
        DeviceCredentials credentials = wanCredentials("0000000000001001", null);

        assertThatThrownBy(() -> validator.validateDataImpl(tenantId, credentials))
                .isInstanceOf(DeviceCredentialsValidationException.class)
                .hasMessageContaining("32 hexadecimal characters");
    }

    @Test
    void rejectsWanCredentialsWithMismatchedExternalId() {
        willReturn(wanTerminal("0000000000001001", 0)).given(deviceDao).findById(tenantId, deviceId.getId());
        DeviceCredentials credentials = wanCredentials("0000000000001002", null);

        assertThatThrownBy(() -> validator.validateDataImpl(tenantId, credentials))
                .isInstanceOf(DeviceCredentialsValidationException.class)
                .hasMessageContaining("must match the device external id");
    }

    @Test
    void rejectsRootKeyForWanGateway() {
        willReturn(wanGateway("8C3F74C81C703000")).given(deviceDao).findById(tenantId, deviceId.getId());
        DeviceCredentials credentials = wanCredentials(
                "8C3F74C81C703000", "0102030405060708090A0B0C0D0E0F10");

        assertThatThrownBy(() -> validator.validateDataImpl(tenantId, credentials))
                .isInstanceOf(DeviceCredentialsValidationException.class)
                .hasMessageContaining("must not contain a root key");
    }

    private DeviceCredentials accessToken(String token) {
        DeviceCredentials c = new DeviceCredentials();
        c.setDeviceId(deviceId);
        c.setCredentialsType(DeviceCredentialsType.ACCESS_TOKEN);
        c.setCredentialsId(token);
        return c;
    }

    private DeviceCredentials mqttBasic(String clientId, String userName, String password) {
        BasicMqttCredentials inner = new BasicMqttCredentials();
        inner.setClientId(clientId);
        inner.setUserName(userName);
        inner.setPassword(password);
        DeviceCredentials c = new DeviceCredentials();
        c.setDeviceId(deviceId);
        c.setCredentialsType(DeviceCredentialsType.MQTT_BASIC);
        c.setCredentialsId("mqtt-credentials-id");
        c.setCredentialsValue(JacksonUtil.toString(inner));
        return c;
    }

    private DeviceCredentials wanCredentials(String externalId, String rootKey) {
        WanDeviceCredentials inner = new WanDeviceCredentials();
        inner.setRootKey(rootKey);
        DeviceCredentials credentials = new DeviceCredentials();
        credentials.setDeviceId(deviceId);
        credentials.setCredentialsType(DeviceCredentialsType.WAN_CREDENTIALS);
        credentials.setCredentialsId(externalId);
        credentials.setCredentialsValue(JacksonUtil.toString(inner));
        return credentials;
    }

    private Device wanTerminal(String deviceEui, int securityMode) {
        WanTerminalConfiguration terminal = new WanTerminalConfiguration();
        terminal.setDevEui(deviceEui);
        terminal.setDevType(0);
        terminal.setSecurityMode(securityMode);
        WanDeviceTransportConfiguration configuration = new WanDeviceTransportConfiguration();
        configuration.setDeviceType(WanDeviceType.TERMINAL);
        configuration.setTerminal(terminal);
        return wanDevice(configuration);
    }

    private Device wanGateway(String gatewayId) {
        WanGatewayConfiguration gateway = new WanGatewayConfiguration();
        gateway.setGwId(gatewayId);
        WanDeviceTransportConfiguration configuration = new WanDeviceTransportConfiguration();
        configuration.setDeviceType(WanDeviceType.GATEWAY);
        configuration.setGateway(gateway);
        return wanDevice(configuration);
    }

    private Device wanDevice(WanDeviceTransportConfiguration configuration) {
        DeviceData deviceData = new DeviceData();
        deviceData.setTransportConfiguration(configuration);
        Device device = new Device();
        device.setDeviceData(deviceData);
        return device;
    }

}
