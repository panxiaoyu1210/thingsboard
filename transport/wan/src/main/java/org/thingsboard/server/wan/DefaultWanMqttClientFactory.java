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

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultWanMqttClientFactory implements WanMqttClientFactory {

    private final WanMessageHandler messageHandler;

    @Value("${transport.wan.connect_timeout_seconds:10}")
    private int connectTimeoutSeconds;

    @Value("${transport.wan.keep_alive_seconds:30}")
    private int keepAliveSeconds;

    @Override
    public WanMqttClient create(WanConnectionConfig configuration) throws Exception {
        return new PahoWanMqttClient(configuration, messageHandler, connectTimeoutSeconds, keepAliveSeconds);
    }

}
