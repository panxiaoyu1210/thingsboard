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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.TbTransportService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "transport.wan", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WanConnectionManager implements TbTransportService {

    private final WanTransportConfigurationProvider configurationProvider;
    private final WanMqttClientFactory clientFactory;

    private final Map<UUID, WanMqttClient> clients = new ConcurrentHashMap<>();
    private volatile List<WanDeviceDescriptor> devices = List.of();

    @PostConstruct
    public void init() {
        refresh();
    }

    @Scheduled(initialDelayString = "${transport.wan.config_refresh_interval_ms:30000}",
            fixedDelayString = "${transport.wan.config_refresh_interval_ms:30000}")
    public synchronized void refresh() {
        WanConfigurationSnapshot snapshot;
        try {
            snapshot = configurationProvider.load();
        } catch (RuntimeException e) {
            log.warn("Unable to refresh WAN configuration; existing MQTT clients remain active", e);
            return;
        }
        Map<UUID, WanConnectionConfig> desired = snapshot.connections().stream()
                .filter(WanConnectionConfig::enabled)
                .collect(Collectors.toMap(WanConnectionConfig::id, Function.identity(), (first, second) -> second));

        clients.keySet().removeIf(connectionId -> {
            if (desired.containsKey(connectionId)) {
                return false;
            }
            closeClient(clients.get(connectionId));
            return true;
        });

        desired.forEach((connectionId, configuration) -> {
            WanMqttClient current = clients.get(connectionId);
            if (current != null && current.configuration().equals(configuration)) {
                return;
            }
            if (current != null) {
                closeClient(current);
                clients.remove(connectionId);
            }
            WanMqttClient replacement = null;
            try {
                replacement = clientFactory.create(configuration);
                replacement.start();
                clients.put(connectionId, replacement);
            } catch (Exception e) {
                closeClient(replacement);
                log.warn("Unable to establish WAN MQTT connection [{}]; it will be retried on refresh",
                        connectionId, e);
            }
        });
        devices = List.copyOf(snapshot.devices());
        log.info("WAN configuration refreshed: [{}] active connections, [{}] devices", clients.size(), devices.size());
    }

    @PreDestroy
    public synchronized void stop() {
        clients.values().forEach(this::closeClient);
        clients.clear();
        devices = List.of();
    }

    @Override
    public String getName() {
        return DataConstants.WAN_TRANSPORT_NAME;
    }

    int activeConnectionCount() {
        return clients.size();
    }

    List<WanDeviceDescriptor> devices() {
        return devices;
    }

    WanConnectionConfig connection(UUID connectionId) {
        WanMqttClient client = clients.get(connectionId);
        if (client == null) {
            throw new WanNsRequestException("WAN NS connection is not active");
        }
        return client.configuration();
    }

    boolean hasConnection(UUID connectionId) {
        return clients.containsKey(connectionId);
    }

    void publish(UUID connectionId, byte[] payload) throws Exception {
        WanMqttClient client = clients.get(connectionId);
        if (client == null) {
            throw new WanNsRequestException("WAN NS connection is not active");
        }
        WanConnectionConfig configuration = client.configuration();
        client.publish(configuration.nsSubscribeTopic(), payload, configuration.qos(),
                configuration.requestTimeoutMs());
    }

    private void closeClient(WanMqttClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException e) {
                log.debug("WAN MQTT client close failed", e);
            }
        }
    }

}
