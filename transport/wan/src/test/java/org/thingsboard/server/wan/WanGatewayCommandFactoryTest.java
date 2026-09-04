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
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanRateConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WanGatewayCommandFactoryTest {

    private final WanGatewayCommandFactory factory = new WanGatewayCommandFactory();

    @Test
    void buildsSingleGatewayQueryAndCompleteAddRequest() {
        WanGatewayConfiguration configuration = gateway();

        WanNsRequest query = factory.getGateway(configuration.getGwId());
        WanNsRequest add = factory.addGateway("Gateway One", configuration);

        assertThat(query.operation()).isEqualTo("get_gateway");
        assertThat(query.body().get("gw_ids")).hasSize(1);
        assertThat(query.body().get("gw_ids").get(0).asText()).isEqualTo("8C3F74C81C703000");

        JsonNode gateway = add.body().get(0);
        assertThat(add.operation()).isEqualTo("add_gateway");
        assertThat(gateway.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "gw_id", "freq_major", "freq_minor", "nwk_num", "tdd_num",
                "rate_num", "rate_cfgs", "description");
        assertThat(gateway.get("description").asText()).isEqualTo("Gateway One");
        assertThat(gateway.get("rate_cfgs")).hasSize(2);
        assertThat(gateway.get("rate_cfgs").get(1).get("downlink_len").asInt()).isEqualTo(220);
    }

    @Test
    void parsesAuthoritativeGatewayConfigurationAndIgnoresNsOnlySlotField() {
        JsonNode responseGateway = JacksonUtil.toJsonNode("""
                {
                  "gw_id": "8C3F74C81C703000",
                  "freq_major": 2,
                  "freq_minor": 3,
                  "nwk_num": 4,
                  "tdd_num": 5,
                  "slot_cfg": 0,
                  "rate_num": 2,
                  "rate_cfgs": [
                    {"rate_mode": 0, "uplink_len": 100, "downlink_len": 120},
                    {"rate_mode": 4, "uplink_len": 200, "downlink_len": 220}
                  ],
                  "description": "NS Gateway"
                }
                """);

        WanGatewayConfiguration result = factory.fromJson(responseGateway);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getFreqMajor()).isEqualTo(2);
        assertThat(result.getRateCfgs()).extracting(WanRateConfiguration::getRateMode)
                .containsExactly(0, 4);
    }

    private WanGatewayConfiguration gateway() {
        WanRateConfiguration rate0 = new WanRateConfiguration();
        rate0.setRateMode(0);
        rate0.setUplinkLen(100);
        rate0.setDownlinkLen(120);
        WanRateConfiguration rate4 = new WanRateConfiguration();
        rate4.setRateMode(4);
        rate4.setUplinkLen(200);
        rate4.setDownlinkLen(220);
        WanGatewayConfiguration configuration = new WanGatewayConfiguration();
        configuration.setGwId("8C3F74C81C703000");
        configuration.setFreqMajor(2);
        configuration.setFreqMinor(3);
        configuration.setNwkNum(4);
        configuration.setTddNum(5);
        configuration.setRateNum(2);
        configuration.setRateCfgs(List.of(rate0, rate4));
        return configuration;
    }
}
