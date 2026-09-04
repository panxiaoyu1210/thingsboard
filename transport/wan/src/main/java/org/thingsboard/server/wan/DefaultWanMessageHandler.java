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

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class DefaultWanMessageHandler implements WanMessageHandler {

    private final WanNsResponseCorrelator responseCorrelator;

    public DefaultWanMessageHandler(WanNsResponseCorrelator responseCorrelator) {
        this.responseCorrelator = responseCorrelator;
    }

    @Override
    public void onMessage(UUID connectionId, String topic, byte[] payload) {
        if (!responseCorrelator.onMessage(connectionId, payload)) {
            log.debug("Ignoring unmatched WAN message for connection [{}], topic [{}]", connectionId, topic);
        }
    }

}
