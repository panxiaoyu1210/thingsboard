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

import org.junit.jupiter.api.Test;
import org.thingsboard.common.util.JacksonUtil;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WanNsResponseCorrelatorTest {

    @Test
    void doesNotConsumeUplinkThatReusesPendingRequestId() {
        UUID connectionId = UUID.randomUUID();
        WanNsResponseCorrelator correlator = new WanNsResponseCorrelator();
        correlator.register(connectionId, 7, "get_terminal");

        boolean consumed = correlator.onMessage(connectionId, JacksonUtil.toJsonNode("""
                {"req_id":7,"req_opt":"push_uplink","req_body":{
                  "dev_eui":"0000000000001002","rssi":-54,"snr":18,"port":0,"data":"0102"
                }}
                """));

        assertThat(consumed).isFalse();
        assertThat(correlator.isPending(connectionId, 7)).isTrue();
        correlator.cancel(connectionId, 7);
    }

}
