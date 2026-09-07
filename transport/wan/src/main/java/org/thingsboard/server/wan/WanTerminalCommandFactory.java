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
import org.thingsboard.server.common.data.transport.wan.WanTerminalConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanValidation;

@Component
public class WanTerminalCommandFactory {

    public static final String GET_TERMINAL = "get_terminal";
    public static final String ADD_TERMINAL = "add_terminal";
    public static final String DELETE_TERMINAL = "delete_terminal";

    public WanNsRequest getTerminal(String deviceEui) {
        ObjectNode body = JacksonUtil.newObjectNode();
        body.putArray("dev_euis").add(deviceEui);
        return new WanNsRequest(GET_TERMINAL, body);
    }

    public WanNsRequest addTerminal(String deviceName, WanTerminalConfiguration configuration,
                                    String rootKey, String relatedExternalId) {
        validateAdd(configuration, rootKey, relatedExternalId);
        ObjectNode terminal = JacksonUtil.newObjectNode();
        terminal.put("dev_eui", configuration.getDevEui());
        terminal.put("dev_type", configuration.getDevType());
        terminal.put("security_mode", configuration.getSecurityMode());
        terminal.put("root_key", rootKey == null ? "" : rootKey);
        terminal.put("related_id", relatedExternalId == null ? "" : relatedExternalId);
        terminal.put("description", deviceName);
        ArrayNode body = JacksonUtil.newArrayNode();
        body.add(terminal);
        return new WanNsRequest(ADD_TERMINAL, body);
    }

    public WanNsRequest deleteTerminal(String deviceEui) {
        ObjectNode body = JacksonUtil.newObjectNode();
        body.putArray("dev_euis").add(deviceEui);
        return new WanNsRequest(DELETE_TERMINAL, body);
    }

    public WanNsTerminalConfiguration fromJson(JsonNode node) {
        WanTerminalConfiguration configuration = new WanTerminalConfiguration();
        configuration.setDevEui(requiredHex(node, "dev_eui", 16));
        configuration.setDevType(requiredInt(node, "dev_type", 0, 1));
        configuration.setSecurityMode(requiredInt(node, "security_mode", 0, 5));
        String rootKey = optionalText(node, "root_key");
        if ((!rootKey.isEmpty() && !WanValidation.isHex(rootKey, 32))
                || (configuration.getSecurityMode() != 0 && rootKey.isEmpty())) {
            throw new WanNsRequestException("NS terminal response root_key is invalid");
        }
        String relatedExternalId = optionalText(node, "related_id");
        if (!relatedExternalId.isEmpty() && !WanValidation.isHex(relatedExternalId, 16)) {
            throw new WanNsRequestException("NS terminal response related_id is invalid");
        }
        if (!configuration.isValid()) {
            throw new WanNsRequestException("NS terminal response contains invalid configuration");
        }
        return new WanNsTerminalConfiguration(configuration,
                rootKey.isEmpty() ? null : rootKey.toUpperCase(),
                relatedExternalId.isEmpty() ? null : relatedExternalId.toUpperCase());
    }

    private void validateAdd(WanTerminalConfiguration configuration, String rootKey, String relatedExternalId) {
        if (configuration == null || !configuration.isValid()) {
            throw new WanNsRequestException("Platform WAN terminal configuration is invalid");
        }
        if ((rootKey != null && !rootKey.isBlank() && !WanValidation.isHex(rootKey, 32))
                || (configuration.getSecurityMode() != 0 && !WanValidation.isHex(rootKey, 32))) {
            throw new WanNsRequestException("Platform WAN terminal root key is invalid");
        }
        if (relatedExternalId != null && !relatedExternalId.isBlank()
                && !WanValidation.isHex(relatedExternalId, 16)) {
            throw new WanNsRequestException("Platform WAN terminal related gateway id is invalid");
        }
    }

    private String requiredHex(JsonNode node, String field, int length) {
        String value = optionalText(node, field);
        if (!WanValidation.isHex(value, length)) {
            throw new WanNsRequestException("NS terminal response field " + field + " is invalid");
        }
        return value.toUpperCase();
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new WanNsRequestException("NS terminal response field " + field + " is invalid");
        }
        return value.textValue().trim();
    }

    private int requiredInt(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < min || value.intValue() > max) {
            throw new WanNsRequestException("NS terminal response field " + field + " is invalid");
        }
        return value.intValue();
    }
}
