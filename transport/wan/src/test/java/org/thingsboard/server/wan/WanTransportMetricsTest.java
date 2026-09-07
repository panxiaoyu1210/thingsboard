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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WanTransportMetricsTest {

    @Test
    void exposesBoundedWanRuntimeMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WanTransportMetrics metrics = new WanTransportMetrics(registry);

        metrics.setActiveConnections(2);
        metrics.recordNsRequest();
        metrics.recordNsRequestTimeout();
        metrics.recordSynchronization(true);
        metrics.recordSynchronization(false);
        metrics.recordUplink(true);
        metrics.recordUplink(false);
        metrics.recordDownlink(WanDownlinkService.DOWNLINK_METHOD, true);
        metrics.recordDownlink(WanDownlinkService.BROADCAST_METHOD, false);

        assertThat(registry.get(WanTransportMetrics.ACTIVE_CONNECTIONS).gauge().value()).isEqualTo(2);
        assertThat(registry.get(WanTransportMetrics.NS_REQUESTS).counter().count()).isEqualTo(1);
        assertThat(registry.get(WanTransportMetrics.NS_REQUEST_TIMEOUTS).counter().count()).isEqualTo(1);
        assertThat(registry.get(WanTransportMetrics.SYNCHRONIZATIONS).tag("result", "success")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get(WanTransportMetrics.SYNCHRONIZATIONS).tag("result", "failure")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get(WanTransportMetrics.UPLINKS).tag("result", "success")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get(WanTransportMetrics.UPLINKS).tag("result", "failure")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get(WanTransportMetrics.DOWNLINKS).tags("operation", "downlink", "result", "success")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get(WanTransportMetrics.DOWNLINKS).tags("operation", "broadcast", "result", "failure")
                .counter().count()).isEqualTo(1);
    }

}
