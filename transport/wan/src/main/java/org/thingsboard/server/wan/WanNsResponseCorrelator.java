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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
public class WanNsResponseCorrelator {

    private final ConcurrentMap<RequestKey, PendingRequest> pending = new ConcurrentHashMap<>();

    public CompletableFuture<JsonNode> register(UUID connectionId, int requestId, String operation) {
        RequestKey key = new RequestKey(connectionId, requestId);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        if (pending.putIfAbsent(key, new PendingRequest(operation, future)) != null) {
            throw new WanNsRequestException("Duplicate WAN NS request id");
        }
        return future;
    }

    public void cancel(UUID connectionId, int requestId) {
        pending.remove(new RequestKey(connectionId, requestId));
    }

    public boolean isPending(UUID connectionId, int requestId) {
        return pending.containsKey(new RequestKey(connectionId, requestId));
    }

    public boolean onMessage(UUID connectionId, byte[] payload) {
        JsonNode response;
        try {
            response = JacksonUtil.fromBytes(payload);
        } catch (RuntimeException e) {
            log.warn("Unable to parse WAN NS response for connection [{}]", connectionId);
            return false;
        }
        JsonNode requestIdNode = response == null ? null : response.get("req_id");
        if (requestIdNode == null || !requestIdNode.isIntegralNumber() || !requestIdNode.canConvertToInt()) {
            return false;
        }
        RequestKey key = new RequestKey(connectionId, requestIdNode.intValue());
        PendingRequest request = pending.remove(key);
        if (request == null) {
            return false;
        }
        String responseOperation = response.path("req_opt").asText(null);
        if (!request.operation().equals(responseOperation)) {
            request.future().completeExceptionally(
                    new WanNsRequestException("WAN NS response operation does not match request"));
        } else {
            request.future().complete(response);
        }
        return true;
    }

    int pendingCount() {
        return pending.size();
    }

    private record RequestKey(UUID connectionId, int requestId) {
    }

    private record PendingRequest(String operation, CompletableFuture<JsonNode> future) {
    }
}
