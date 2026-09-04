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
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "transport.wan", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WanNsRequestClient {

    private final WanConnectionManager connectionManager;
    private final WanNsResponseCorrelator responseCorrelator;
    private final ConcurrentMap<UUID, AtomicInteger> requestIds = new ConcurrentHashMap<>();

    public JsonNode execute(UUID connectionId, WanNsRequest request) {
        WanConnectionConfig connection = connectionManager.connection(connectionId);
        int requestId = nextRequestId(connectionId);
        ObjectNode payload = JacksonUtil.newObjectNode();
        payload.put("req_id", requestId);
        payload.put("req_opt", request.operation());
        payload.set("req_body", request.body());
        var responseFuture = responseCorrelator.register(connectionId, requestId, request.operation());
        try {
            connectionManager.publish(connectionId, JacksonUtil.toString(payload).getBytes(StandardCharsets.UTF_8));
            return responseFuture.get(connection.requestTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WanNsRequestException("WAN NS request was interrupted", e);
        } catch (TimeoutException e) {
            throw new WanNsRequestException("WAN NS request timed out", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new WanNsRequestException(cause.getMessage(), cause);
        } catch (Exception e) {
            throw new WanNsRequestException("Unable to publish WAN NS request", e);
        } finally {
            responseCorrelator.cancel(connectionId, requestId);
        }
    }

    private int nextRequestId(UUID connectionId) {
        AtomicInteger sequence = requestIds.computeIfAbsent(connectionId, ignored -> new AtomicInteger());
        int requestId;
        do {
            requestId = sequence.updateAndGet(value -> value == Integer.MAX_VALUE ? 1 : value + 1);
        } while (responseCorrelator.isPending(connectionId, requestId));
        return requestId;
    }
}
