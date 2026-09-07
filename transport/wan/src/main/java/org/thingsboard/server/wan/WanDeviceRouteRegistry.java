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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class WanDeviceRouteRegistry {

    private volatile Map<WanDeviceKey, List<WanDeviceDescriptor>> routes = Map.of();

    public void replace(List<WanDeviceDescriptor> descriptors,
                        Map<UUID, WanConnectionConfig> activeConnections) {
        Map<WanDeviceKey, List<WanDeviceDescriptor>> updated = new HashMap<>();
        for (WanDeviceDescriptor descriptor : descriptors) {
            WanConnectionConfig connection = activeConnections.get(descriptor.connectionId());
            if (connection == null || descriptor.tenantId() == null || descriptor.externalId() == null) {
                continue;
            }
            if (!descriptor.tenantId().equals(connection.tenantId())) {
                log.warn("WAN device [{}] tenant does not match connection [{}]; route ignored",
                        descriptor.deviceId(), descriptor.connectionId());
                continue;
            }
            WanDeviceKey key = new WanDeviceKey(descriptor.connectionId(),
                    descriptor.externalId().toUpperCase(Locale.ROOT));
            updated.computeIfAbsent(key, ignored -> new ArrayList<>()).add(descriptor);
        }
        updated.replaceAll((key, value) -> List.copyOf(value));
        routes = Map.copyOf(updated);
    }

    public void clear() {
        routes = Map.of();
    }

    public WanDeviceDescriptor resolve(UUID connectionId, String externalId) {
        List<WanDeviceDescriptor> matches = routes.get(
                new WanDeviceKey(connectionId, externalId.toUpperCase(Locale.ROOT)));
        if (matches == null || matches.isEmpty()) {
            throw new WanUplinkException("WAN uplink terminal [" + externalId
                    + "] is unknown for NS connection [" + connectionId + "]");
        }
        if (matches.size() != 1) {
            throw new WanUplinkException("WAN uplink terminal [" + externalId
                    + "] mapping is ambiguous for NS connection [" + connectionId + "]");
        }
        return matches.get(0);
    }

    private record WanDeviceKey(UUID connectionId, String externalId) {
    }

}
