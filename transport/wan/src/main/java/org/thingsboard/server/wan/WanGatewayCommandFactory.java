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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanRateConfiguration;

@Component
public class WanGatewayCommandFactory {

    public static final String GET_GATEWAY = "get_gateway";
    public static final String ADD_GATEWAY = "add_gateway";
    public static final String DELETE_GATEWAY = "delete_gateway";

    public WanNsRequest getGateway(String gatewayId) {
        ObjectNode body = JacksonUtil.newObjectNode();
        body.putArray("gw_ids").add(gatewayId);
        return new WanNsRequest(GET_GATEWAY, body);
    }

    public WanNsRequest addGateway(String deviceName, WanGatewayConfiguration configuration) {
        ObjectNode gateway = toJson(configuration);
        gateway.put("description", deviceName);
        ArrayNode body = JacksonUtil.newArrayNode();
        body.add(gateway);
        return new WanNsRequest(ADD_GATEWAY, body);
    }

    public WanNsRequest deleteGateway(String gatewayId) {
        ObjectNode body = JacksonUtil.newObjectNode();
        body.putArray("gw_ids").add(gatewayId);
        return new WanNsRequest(DELETE_GATEWAY, body);
    }

    public WanGatewayConfiguration fromJson(JsonNode node) {
        WanGatewayConfiguration configuration = new WanGatewayConfiguration();
        configuration.setGwId(requiredText(node, "gw_id"));
        configuration.setFreqMajor(requiredInt(node, "freq_major"));
        configuration.setFreqMinor(requiredInt(node, "freq_minor"));
        configuration.setNwkNum(requiredInt(node, "nwk_num"));
        configuration.setTddNum(requiredInt(node, "tdd_num"));
        configuration.setRateNum(requiredInt(node, "rate_num"));
        JsonNode rateConfigurations = node.get("rate_cfgs");
        if (rateConfigurations == null || !rateConfigurations.isArray()) {
            throw new WanNsRequestException("NS gateway response rate_cfgs is not an array");
        }
        for (JsonNode rateNode : rateConfigurations) {
            WanRateConfiguration rate = new WanRateConfiguration();
            rate.setRateMode(requiredInt(rateNode, "rate_mode"));
            rate.setUplinkLen(requiredInt(rateNode, "uplink_len"));
            rate.setDownlinkLen(requiredInt(rateNode, "downlink_len"));
            configuration.getRateCfgs().add(rate);
        }
        if (!configuration.isValid()) {
            throw new WanNsRequestException("NS gateway response contains invalid configuration");
        }
        return configuration;
    }

    private ObjectNode toJson(WanGatewayConfiguration configuration) {
        ObjectNode gateway = JacksonUtil.newObjectNode();
        gateway.put("gw_id", configuration.getGwId());
        gateway.put("freq_major", configuration.getFreqMajor());
        gateway.put("freq_minor", configuration.getFreqMinor());
        gateway.put("nwk_num", configuration.getNwkNum());
        gateway.put("tdd_num", configuration.getTddNum());
        gateway.put("rate_num", configuration.getRateNum());
        ArrayNode rates = gateway.putArray("rate_cfgs");
        for (WanRateConfiguration configurationRate : configuration.getRateCfgs()) {
            ObjectNode rate = rates.addObject();
            rate.put("rate_mode", configurationRate.getRateMode());
            rate.put("uplink_len", configurationRate.getUplinkLen());
            rate.put("downlink_len", configurationRate.getDownlinkLen());
        }
        return gateway;
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new WanNsRequestException("NS gateway response field " + field + " is invalid");
        }
        return value.textValue();
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new WanNsRequestException("NS gateway response field " + field + " is invalid");
        }
        return value.intValue();
    }
}
