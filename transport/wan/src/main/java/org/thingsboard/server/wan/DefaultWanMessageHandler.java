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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;

import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "transport.wan", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DefaultWanMessageHandler implements WanMessageHandler {

    private final WanNsResponseCorrelator responseCorrelator;
    private final WanUplinkService uplinkService;

    public DefaultWanMessageHandler(WanNsResponseCorrelator responseCorrelator,
                                    WanUplinkService uplinkService) {
        this.responseCorrelator = responseCorrelator;
        this.uplinkService = uplinkService;
    }

    @Override
    public void onMessage(UUID connectionId, String topic, byte[] payload) {
        JsonNode message;
        try {
            message = JacksonUtil.fromBytes(payload);
        } catch (RuntimeException e) {
            log.warn("Rejecting malformed WAN message for connection [{}], topic [{}]", connectionId, topic);
            return;
        }
        if (responseCorrelator.onMessage(connectionId, message)) {
            return;
        }
        try {
            if (uplinkService.onMessage(connectionId, message)) {
                return;
            }
        } catch (WanUplinkException e) {
            log.warn("Rejecting WAN uplink for connection [{}], topic [{}]: {}",
                    connectionId, topic, e.getMessage());
            return;
        } catch (RuntimeException e) {
            log.warn("Unable to process WAN uplink for connection [{}], topic [{}]",
                    connectionId, topic, e);
            return;
        }
        log.debug("Ignoring unmatched WAN message for connection [{}], topic [{}]", connectionId, topic);
    }

}
