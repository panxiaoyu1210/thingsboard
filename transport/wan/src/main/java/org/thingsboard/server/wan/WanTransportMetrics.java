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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(prefix = "transport.wan", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WanTransportMetrics {

    static final String ACTIVE_CONNECTIONS = "tb.wan.connections.active";
    static final String NS_REQUESTS = "tb.wan.ns.requests";
    static final String NS_REQUEST_TIMEOUTS = "tb.wan.ns.request.timeouts";
    static final String SYNCHRONIZATIONS = "tb.wan.synchronizations";
    static final String UPLINKS = "tb.wan.uplinks";
    static final String DOWNLINKS = "tb.wan.downlinks";

    private static final WanTransportMetrics NOOP = new WanTransportMetrics();

    private final AtomicInteger activeConnections;
    private final Counter nsRequests;
    private final Counter nsRequestTimeouts;
    private final Counter syncSuccess;
    private final Counter syncFailure;
    private final Counter uplinkSuccess;
    private final Counter uplinkFailure;
    private final Counter downlinkSuccess;
    private final Counter downlinkFailure;
    private final Counter broadcastSuccess;
    private final Counter broadcastFailure;

    @Autowired
    public WanTransportMetrics(MeterRegistry meterRegistry) {
        activeConnections = new AtomicInteger();
        meterRegistry.gauge(ACTIVE_CONNECTIONS, activeConnections);
        nsRequests = meterRegistry.counter(NS_REQUESTS);
        nsRequestTimeouts = meterRegistry.counter(NS_REQUEST_TIMEOUTS);
        syncSuccess = meterRegistry.counter(SYNCHRONIZATIONS, "result", "success");
        syncFailure = meterRegistry.counter(SYNCHRONIZATIONS, "result", "failure");
        uplinkSuccess = meterRegistry.counter(UPLINKS, "result", "success");
        uplinkFailure = meterRegistry.counter(UPLINKS, "result", "failure");
        downlinkSuccess = meterRegistry.counter(DOWNLINKS, "operation", "downlink", "result", "success");
        downlinkFailure = meterRegistry.counter(DOWNLINKS, "operation", "downlink", "result", "failure");
        broadcastSuccess = meterRegistry.counter(DOWNLINKS, "operation", "broadcast", "result", "success");
        broadcastFailure = meterRegistry.counter(DOWNLINKS, "operation", "broadcast", "result", "failure");
    }

    private WanTransportMetrics() {
        activeConnections = null;
        nsRequests = null;
        nsRequestTimeouts = null;
        syncSuccess = null;
        syncFailure = null;
        uplinkSuccess = null;
        uplinkFailure = null;
        downlinkSuccess = null;
        downlinkFailure = null;
        broadcastSuccess = null;
        broadcastFailure = null;
    }

    static WanTransportMetrics noop() {
        return NOOP;
    }

    void setActiveConnections(int count) {
        if (activeConnections != null) {
            activeConnections.set(count);
        }
    }

    void recordNsRequest() {
        increment(nsRequests);
    }

    void recordNsRequestTimeout() {
        increment(nsRequestTimeouts);
    }

    void recordSynchronization(boolean success) {
        increment(success ? syncSuccess : syncFailure);
    }

    void recordUplink(boolean success) {
        increment(success ? uplinkSuccess : uplinkFailure);
    }

    void recordDownlink(String method, boolean success) {
        boolean broadcast = WanDownlinkService.BROADCAST_METHOD.equals(method);
        increment(broadcast
                ? (success ? broadcastSuccess : broadcastFailure)
                : (success ? downlinkSuccess : downlinkFailure));
    }

    private void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

}
