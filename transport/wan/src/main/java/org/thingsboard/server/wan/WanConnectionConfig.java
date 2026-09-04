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

import java.util.UUID;

public record WanConnectionConfig(UUID id, UUID tenantId, String name, String brokerHost, int brokerPort,
                                  boolean tls, String clientId, String username, String password,
                                  String nsPublishTopic, String nsSubscribeTopic, int qos, boolean enabled,
                                  int requestTimeoutMs, int syncIntervalHours, long version) {

    @Override
    public String toString() {
        return "WanConnectionConfig[id=" + id + ", tenantId=" + tenantId + ", name=" + name
                + ", brokerHost=" + brokerHost + ", brokerPort=" + brokerPort + ", tls=" + tls
                + ", clientId=" + clientId + ", username=" + username + ", password=REDACTED"
                + ", nsPublishTopic=" + nsPublishTopic + ", nsSubscribeTopic=" + nsSubscribeTopic
                + ", qos=" + qos + ", enabled=" + enabled + ", requestTimeoutMs=" + requestTimeoutMs
                + ", syncIntervalHours=" + syncIntervalHours + ", version=" + version + "]";
    }

}
