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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WanUplinkMessageParserTest {

    private final WanUplinkMessageParser parser = new WanUplinkMessageParser();

    @Test
    void parsesAndNormalizesValidProtocolMessage() {
        WanUplinkMessage message = parser.parse(json("""
                {"req_id":1,"req_opt":"push_uplink","req_body":{
                  "dev_eui":"000000000000ab12","rssi":-54,"snr":18,"port":0,"data":"01ab03cd"
                }}
                """));

        assertThat(message).isEqualTo(new WanUplinkMessage(
                1, "000000000000AB12", -54, 18, 0, "01ab03cd"));
    }

    @Test
    void rejectsInvalidProtocolFields() {
        List<String> invalidMessages = List.of(
                "{\"req_id\":\"1\",\"req_opt\":\"push_uplink\",\"req_body\":{\"dev_eui\":\"0000000000001002\",\"rssi\":-54,\"snr\":18,\"port\":0,\"data\":\"0102\"}}",
                "{\"req_id\":1,\"req_opt\":\"push_uplink\",\"req_body\":[]}",
                "{\"req_id\":1,\"req_opt\":\"push_uplink\",\"req_body\":{\"dev_eui\":\"1002\",\"rssi\":-54,\"snr\":18,\"port\":0,\"data\":\"0102\"}}",
                "{\"req_id\":1,\"req_opt\":\"push_uplink\",\"req_body\":{\"dev_eui\":\"0000000000001002\",\"rssi\":-54.5,\"snr\":18,\"port\":0,\"data\":\"0102\"}}",
                "{\"req_id\":1,\"req_opt\":\"push_uplink\",\"req_body\":{\"dev_eui\":\"0000000000001002\",\"rssi\":-54,\"snr\":\"18\",\"port\":0,\"data\":\"0102\"}}",
                "{\"req_id\":1,\"req_opt\":\"push_uplink\",\"req_body\":{\"dev_eui\":\"0000000000001002\",\"rssi\":-54,\"snr\":18,\"port\":256,\"data\":\"0102\"}}",
                "{\"req_id\":1,\"req_opt\":\"push_uplink\",\"req_body\":{\"dev_eui\":\"0000000000001002\",\"rssi\":-54,\"snr\":18,\"port\":0,\"data\":\"010\"}}",
                "{\"req_id\":1,\"req_opt\":\"push_uplink\",\"req_body\":{\"dev_eui\":\"0000000000001002\",\"rssi\":-54,\"snr\":18,\"port\":0,\"data\":\"01XZ\"}}"
        );

        invalidMessages.forEach(payload -> assertThatThrownBy(() -> parser.parse(json(payload)))
                .isInstanceOf(WanUplinkException.class));
    }

    @Test
    void ignoresOtherWanOperations() {
        JsonNode response = json("{\"req_id\":1,\"req_opt\":\"get_terminal\",\"rsp_code\":0}");
        assertThat(parser.supports(response)).isFalse();
    }

    private JsonNode json(String value) {
        return JacksonUtil.toJsonNode(value);
    }

}
