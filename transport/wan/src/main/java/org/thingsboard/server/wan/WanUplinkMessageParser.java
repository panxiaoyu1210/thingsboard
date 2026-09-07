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
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.transport.wan.WanValidation;

import java.util.Locale;

@Component
public class WanUplinkMessageParser {

    public static final String OPERATION = "push_uplink";

    public boolean supports(JsonNode message) {
        return message != null && message.isObject()
                && OPERATION.equals(message.path("req_opt").asText(null));
    }

    public WanUplinkMessage parse(JsonNode message) {
        if (!supports(message)) {
            throw new WanUplinkException("WAN message is not push_uplink");
        }
        int requestId = requiredInt(message, "req_id");
        JsonNode body = message.get("req_body");
        if (body == null || !body.isObject()) {
            throw new WanUplinkException("WAN uplink req_body must be an object");
        }
        String deviceEui = requiredText(body, "dev_eui").toUpperCase(Locale.ROOT);
        if (!WanValidation.isHex(deviceEui, 16)) {
            throw new WanUplinkException("WAN uplink dev_eui must contain 16 hexadecimal characters");
        }
        int rssi = requiredInt(body, "rssi");
        int snr = requiredInt(body, "snr");
        int port = requiredInt(body, "port");
        if (!WanValidation.isInRange(port, 0, 255)) {
            throw new WanUplinkException("WAN uplink port must be between 0 and 255");
        }
        String data = requiredText(body, "data");
        if ((data.length() & 1) != 0 || !WanValidation.isHex(data, data.length())) {
            throw new WanUplinkException("WAN uplink data must contain an even number of hexadecimal characters");
        }
        return new WanUplinkMessage(requestId, deviceEui, rssi, snr, port, data);
    }

    private int requiredInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new WanUplinkException("WAN uplink " + field + " must be an integer");
        }
        return value.intValue();
    }

    private String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new WanUplinkException("WAN uplink " + field + " must be a non-empty string");
        }
        return value.textValue().trim();
    }

}
