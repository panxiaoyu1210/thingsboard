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
import org.mockito.Mockito;
import org.thingsboard.common.util.JacksonUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

class WanNsRequestClientTest {

    @Test
    void assignsUniqueRequestIdsAndCorrelatesByConnectionAndOperation() throws Exception {
        UUID connectionId = UUID.randomUUID();
        WanConnectionManager manager = Mockito.mock(WanConnectionManager.class);
        WanNsResponseCorrelator correlator = new WanNsResponseCorrelator();
        WanNsRequestClient client = new WanNsRequestClient(manager, correlator);
        when(manager.connection(connectionId)).thenReturn(connection(connectionId));
        List<JsonNode> requests = new ArrayList<>();
        doAnswer(invocation -> {
            JsonNode request = JacksonUtil.fromBytes(invocation.getArgument(1));
            requests.add(request);
            String response = "{\"req_id\":" + request.get("req_id").asInt()
                    + ",\"req_opt\":\"" + request.get("req_opt").asText()
                    + "\",\"rsp_code\":0,\"rsp_body\":[]}";
            assertThat(correlator.onMessage(UUID.randomUUID(), response.getBytes(StandardCharsets.UTF_8))).isFalse();
            assertThat(correlator.onMessage(connectionId, response.getBytes(StandardCharsets.UTF_8))).isTrue();
            return null;
        }).when(manager).publish(Mockito.eq(connectionId), Mockito.any(byte[].class));

        JsonNode first = client.execute(connectionId,
                new WanNsRequest("get_gateway", JacksonUtil.toJsonNode("{\"gw_ids\":[\"A\"]}")));
        JsonNode second = client.execute(connectionId,
                new WanNsRequest("get_gateway", JacksonUtil.toJsonNode("{\"gw_ids\":[\"B\"]}")));

        assertThat(first.get("rsp_code").asInt()).isZero();
        assertThat(second.get("rsp_code").asInt()).isZero();
        assertThat(requests).extracting(node -> node.get("req_id").asInt()).doesNotHaveDuplicates();
        assertThat(requests).allSatisfy(node -> {
            assertThat(node.get("req_opt").asText()).isEqualTo("get_gateway");
            assertThat(node.get("req_body").get("gw_ids")).hasSize(1);
        });
        assertThat(correlator.pendingCount()).isZero();
    }

    @Test
    void rejectsMismatchedOperationAndCleansPendingRequest() throws Exception {
        UUID connectionId = UUID.randomUUID();
        WanConnectionManager manager = Mockito.mock(WanConnectionManager.class);
        WanNsResponseCorrelator correlator = new WanNsResponseCorrelator();
        WanNsRequestClient client = new WanNsRequestClient(manager, correlator);
        when(manager.connection(connectionId)).thenReturn(connection(connectionId));
        doAnswer(invocation -> {
            JsonNode request = JacksonUtil.fromBytes(invocation.getArgument(1));
            String response = "{\"req_id\":" + request.get("req_id").asInt()
                    + ",\"req_opt\":\"add_gateway\",\"rsp_code\":0}";
            correlator.onMessage(connectionId, response.getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(manager).publish(Mockito.eq(connectionId), Mockito.any(byte[].class));

        assertThatThrownBy(() -> client.execute(connectionId,
                new WanNsRequest("get_gateway", JacksonUtil.toJsonNode("{\"gw_ids\":[\"A\"]}"))))
                .isInstanceOf(WanNsRequestException.class)
                .hasMessageContaining("does not match");
        assertThat(correlator.pendingCount()).isZero();
    }

    private WanConnectionConfig connection(UUID id) {
        return new WanConnectionConfig(id, UUID.randomUUID(), "NS", "localhost", 1883, false,
                "client", null, null, "ns/publish", "ns/subscribe",
                1, true, 1_000, 24, 1);
    }
}
