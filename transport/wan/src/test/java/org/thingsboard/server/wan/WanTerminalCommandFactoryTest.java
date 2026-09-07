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

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.transport.wan.WanTerminalConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WanTerminalCommandFactoryTest {

    private static final String DEVICE_EUI = "0000000000001001";
    private static final String ROOT_KEY = "0102030405060708090A0B0C0D0E0F10";
    private static final String GATEWAY_ID = "8C3F74C81C703000";
    private final WanTerminalCommandFactory factory = new WanTerminalCommandFactory();

    @Test
    void buildsProtocolExactGetAndAddRequests() {
        WanNsRequest get = factory.getTerminal(DEVICE_EUI);
        WanNsRequest add = factory.addTerminal("Terminal One", terminal(5), ROOT_KEY, GATEWAY_ID);
        WanNsRequest delete = factory.deleteTerminal(DEVICE_EUI);

        assertThat(get.operation()).isEqualTo("get_terminal");
        assertThat(get.body().path("dev_euis")).hasSize(1);
        assertThat(get.body().path("dev_euis").path(0).asText()).isEqualTo(DEVICE_EUI);
        assertThat(add.operation()).isEqualTo("add_terminal");
        JsonNode body = add.body().path(0);
        assertThat(body.path("dev_eui").asText()).isEqualTo(DEVICE_EUI);
        assertThat(body.path("dev_type").asInt()).isEqualTo(1);
        assertThat(body.path("security_mode").asInt()).isEqualTo(5);
        assertThat(body.path("root_key").asText()).isEqualTo(ROOT_KEY);
        assertThat(body.path("related_id").asText()).isEqualTo(GATEWAY_ID);
        assertThat(body.path("description").asText()).isEqualTo("Terminal One");

        JsonNode unsecured = factory.addTerminal("No Security", terminal(0), null, null).body().path(0);
        assertThat(unsecured.path("root_key").asText()).isEmpty();
        assertThat(unsecured.path("related_id").asText()).isEmpty();
        assertThat(delete.operation()).isEqualTo("delete_terminal");
        assertThat(delete.body().path("dev_euis")).hasSize(1);
        assertThat(delete.body().path("dev_euis").path(0).asText()).isEqualTo(DEVICE_EUI);
    }

    @Test
    void parsesPlatformManagedNsResponseWithoutDerivedAddressFields() {
        WanNsTerminalConfiguration result = factory.fromJson(JacksonUtil.toJsonNode("""
                {"dev_eui":"0000000000001001","dev_type":1,"security_mode":5,
                 "root_key":"0102030405060708090A0B0C0D0E0F10",
                 "related_id":"8C3F74C81C703000","description":"Terminal One"}
                """));

        assertThat(result.deviceConfiguration().getDevEui()).isEqualTo(DEVICE_EUI);
        assertThat(result.deviceConfiguration().getDevType()).isEqualTo(1);
        assertThat(result.deviceConfiguration().getSecurityMode()).isEqualTo(5);
        assertThat(result.rootKey()).isEqualTo(ROOT_KEY);
        assertThat(result.relatedExternalId()).isEqualTo(GATEWAY_ID);
        assertThat(result.toString()).contains("rootKey=REDACTED").doesNotContain(ROOT_KEY);
    }

    @Test
    void ignoresNsDerivedAddressFields() {
        WanNsTerminalConfiguration result = factory.fromJson(JacksonUtil.toJsonNode("""
                {"dev_eui":"0000000000001001","dev_type":1,"security_mode":5,
                 "root_key":"0102030405060708090A0B0C0D0E0F10",
                 "related_id":"8C3F74C81C703000","description":"Terminal One",
                 "addr_mode":"assigned","nwk_id":"","nwk_addr":null}
                """));

        assertThat(result.deviceConfiguration().getDevEui()).isEqualTo(DEVICE_EUI);
        assertThat(result.deviceConfiguration().getDevType()).isEqualTo(1);
        assertThat(result.deviceConfiguration().getSecurityMode()).isEqualTo(5);
        assertThat(result.rootKey()).isEqualTo(ROOT_KEY);
        assertThat(result.relatedExternalId()).isEqualTo(GATEWAY_ID);
    }

    @Test
    void rejectsMissingPlatformManagedFieldsAndInvalidRelatedGateway() {
        assertThatThrownBy(() -> factory.fromJson(JacksonUtil.toJsonNode("""
                {"dev_eui":"0000000000001001","security_mode":0,
                 "root_key":"","related_id":"","description":"Terminal One"}
                """))).isInstanceOf(WanNsRequestException.class).hasMessageContaining("dev_type");

        assertThatThrownBy(() -> factory.fromJson(JacksonUtil.toJsonNode("""
                {"dev_eui":"0000000000001001","dev_type":1,"security_mode":5,
                 "root_key":"","related_id":"","description":"Terminal One"}
                """))).isInstanceOf(WanNsRequestException.class).hasMessageContaining("root_key");

        assertThatThrownBy(() -> factory.addTerminal("Terminal One", terminal(5), ROOT_KEY, "INVALID"))
                .isInstanceOf(WanNsRequestException.class).hasMessageContaining("related gateway");
    }

    private WanTerminalConfiguration terminal(int securityMode) {
        WanTerminalConfiguration configuration = new WanTerminalConfiguration();
        configuration.setDevEui(DEVICE_EUI);
        configuration.setDevType(1);
        configuration.setSecurityMode(securityMode);
        return configuration;
    }
}
