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
package org.thingsboard.server.wan.mock;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.thingsboard.common.util.JacksonUtil;

import static org.assertj.core.api.Assertions.assertThat;

class WanMockNsApplicationTest {

    @Test
    void supportsDeviceLifecycleAndRedactsRootKeyFromState() {
        WanMockNsApplication.MockNsState state = new WanMockNsApplication.MockNsState();
        ObjectNode seed = JacksonUtil.newObjectNode();
        seed.putArray("gateways").addObject().put("gw_id", "8c3f74c81c703000");
        seed.putArray("terminals").addObject()
                .put("dev_eui", "0000000000001002")
                .put("root_key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        state.seed(seed);

        ObjectNode gatewayRequest = JacksonUtil.newObjectNode().put("req_id", 1).put("req_opt", "get_gateway");
        gatewayRequest.putObject("req_body").putArray("gw_ids").add("8C3F74C81C703000");
        ObjectNode terminalRequest = JacksonUtil.newObjectNode().put("req_id", 2).put("req_opt", "get_terminal");
        terminalRequest.putObject("req_body").putArray("dev_euis").add("0000000000001002");

        ObjectNode gatewayResponse = state.process(gatewayRequest);
        ObjectNode terminalResponse = state.process(terminalRequest);

        assertThat(gatewayResponse.path("rsp_code").asInt()).isZero();
        assertThat(gatewayResponse.path("rsp_body").path(0).path("gw_id").asText())
                .isEqualTo("8C3F74C81C703000");
        assertThat(terminalResponse.path("rsp_body").path(0).path("root_key").asText())
                .isEqualTo("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertThat(state.snapshot(1, 1).path("terminals").path(0).has("root_key")).isFalse();
        assertThat(state.snapshot(1, 1).path("operationCounts").path("get_gateway").asInt()).isEqualTo(1);
        assertThat(state.snapshot(1, 1).path("operationCounts").path("get_terminal").asInt()).isEqualTo(1);
    }

    @Test
    void recordsDownlinkWithoutCreatingResponse() {
        WanMockNsApplication.MockNsState state = new WanMockNsApplication.MockNsState();
        ObjectNode request = JacksonUtil.newObjectNode().put("req_id", 3).put("req_opt", "push_downlink");
        request.putObject("req_body").put("dev_eui", "0000000000001002")
                .put("port", 3).put("data", "0102");

        assertThat(state.process(request)).isNull();
        assertThat(state.snapshot(0, 0).path("downlinks").path(0).path("operation").asText())
                .isEqualTo("push_downlink");
    }
}
