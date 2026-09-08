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
package org.thingsboard.server.common.data.device.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.device.credentials.WanDeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanRateConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanTerminalConfiguration;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WanDeviceTransportConfigurationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRoundTripGatewayConfiguration() throws Exception {
        WanDeviceTransportConfiguration configuration = gatewayConfiguration();

        String json = objectMapper.writeValueAsString(configuration);
        DeviceTransportConfiguration restored = objectMapper.readValue(json, DeviceTransportConfiguration.class);

        assertThat(json)
                .contains("\"type\":\"WAN\"")
                .doesNotContain("\"valid\"")
                .doesNotContain("\"externalId\"");
        assertThat(restored).isInstanceOf(WanDeviceTransportConfiguration.class).isEqualTo(configuration);
        assertThatCode(restored::validate).doesNotThrowAnyException();
    }

    @Test
    void shouldRoundTripTerminalConfiguration() throws Exception {
        WanTerminalConfiguration terminal = new WanTerminalConfiguration();
        terminal.setDevEui("0000000000001001");
        terminal.setDevType(1);
        terminal.setSecurityMode(5);
        WanDeviceTransportConfiguration configuration = new WanDeviceTransportConfiguration();
        configuration.setDeviceType(WanDeviceType.TERMINAL);
        configuration.setTerminal(terminal);

        DeviceTransportConfiguration restored = objectMapper.readValue(
                objectMapper.writeValueAsString(configuration), DeviceTransportConfiguration.class);

        assertThat(restored).isInstanceOf(WanDeviceTransportConfiguration.class).isEqualTo(configuration);
        assertThatCode(restored::validate).doesNotThrowAnyException();
        assertThatCode(() -> configuration.validateGatewayFlag(false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> configuration.validateGatewayFlag(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gateway flag");
    }

    @Test
    void shouldRejectInvalidGatewayFieldRelationships() {
        WanDeviceTransportConfiguration configuration = gatewayConfiguration();
        configuration.getGateway().setRateNum(2);

        assertThatThrownBy(configuration::validate).isInstanceOf(IllegalArgumentException.class);

        configuration = gatewayConfiguration();
        WanRateConfiguration duplicatedRate = configuration.getGateway().getRateCfgs().get(0);
        configuration.getGateway().setRateCfgs(List.of(duplicatedRate, duplicatedRate));
        configuration.getGateway().setRateNum(2);

        assertThatThrownBy(configuration::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldApplyPacketLengthBoundaryForRateMode() {
        assertPacketLengthBoundary(0, 245);
        assertPacketLengthBoundary(3, 245);
        assertPacketLengthBoundary(4, 401);
        assertPacketLengthBoundary(6, 401);
        assertPacketLengthBoundary(7, 585);
    }

    @Test
    void shouldRejectMalformedWanIdentifiers() {
        WanDeviceTransportConfiguration gateway = gatewayConfiguration();
        gateway.getGateway().setGwId("8C3F74C81C70300Z");

        WanTerminalConfiguration terminal = new WanTerminalConfiguration();
        terminal.setDevEui("000000000000100");
        terminal.setDevType(0);
        terminal.setSecurityMode(0);
        WanDeviceTransportConfiguration terminalConfiguration = new WanDeviceTransportConfiguration();
        terminalConfiguration.setDeviceType(WanDeviceType.TERMINAL);
        terminalConfiguration.setTerminal(terminal);

        assertThatThrownBy(gateway::validate).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(terminalConfiguration::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectGatewayValuesOutsideProtocolRanges() {
        assertInvalidGateway(configuration -> configuration.setFreqMajor(0));
        assertInvalidGateway(configuration -> configuration.setFreqMinor(9));
        assertInvalidGateway(configuration -> configuration.setNwkNum(33));
        assertInvalidGateway(configuration -> configuration.setTddNum(0));
        assertInvalidGateway(configuration -> configuration.setRateNum(0));
    }

    @Test
    void shouldRejectRateModeOutsideProtocolRange() {
        assertInvalidGateway(configuration -> configuration.getRateCfgs().get(0).setRateMode(-1));
        assertInvalidGateway(configuration -> configuration.getRateCfgs().get(0).setRateMode(8));
    }

    @Test
    void shouldRedactWanRootKeyFromStringRepresentations() {
        String rootKey = "0102030405060708090A0B0C0D0E0F10";
        WanDeviceCredentials wanCredentials = new WanDeviceCredentials();
        wanCredentials.setRootKey(rootKey);
        DeviceCredentials credentials = new DeviceCredentials();
        credentials.setCredentialsType(DeviceCredentialsType.WAN_CREDENTIALS);
        credentials.setCredentialsValue("{\"rootKey\":\"" + rootKey + "\"}");

        assertThat(wanCredentials.toString()).contains("REDACTED").doesNotContain(rootKey);
        assertThat(credentials.toString()).contains("REDACTED").doesNotContain(rootKey);
    }

    private WanDeviceTransportConfiguration gatewayConfiguration() {
        WanRateConfiguration rate = new WanRateConfiguration();
        rate.setRateMode(0);
        rate.setUplinkLen(245);
        rate.setDownlinkLen(245);
        WanGatewayConfiguration gateway = new WanGatewayConfiguration();
        gateway.setGwId("8C3F74C81C703000");
        gateway.setFreqMajor(10);
        gateway.setFreqMinor(8);
        gateway.setNwkNum(32);
        gateway.setTddNum(255);
        gateway.setRateNum(1);
        gateway.setRateCfgs(List.of(rate));
        WanDeviceTransportConfiguration configuration = new WanDeviceTransportConfiguration();
        configuration.setDeviceType(WanDeviceType.GATEWAY);
        configuration.setGateway(gateway);
        return configuration;
    }

    private void assertPacketLengthBoundary(int rateMode, int maxLength) {
        WanDeviceTransportConfiguration configuration = gatewayConfiguration();
        WanRateConfiguration rate = configuration.getGateway().getRateCfgs().get(0);
        rate.setRateMode(rateMode);
        rate.setUplinkLen(maxLength);
        rate.setDownlinkLen(maxLength);

        assertThatCode(configuration::validate).doesNotThrowAnyException();

        rate.setUplinkLen(maxLength + 1);
        assertThatThrownBy(configuration::validate).isInstanceOf(IllegalArgumentException.class);

        rate.setUplinkLen(maxLength);
        rate.setDownlinkLen(maxLength + 1);
        assertThatThrownBy(configuration::validate).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertInvalidGateway(Consumer<WanGatewayConfiguration> mutation) {
        WanDeviceTransportConfiguration configuration = gatewayConfiguration();
        mutation.accept(configuration.getGateway());

        assertThatThrownBy(configuration::validate).isInstanceOf(IllegalArgumentException.class);
    }

}
