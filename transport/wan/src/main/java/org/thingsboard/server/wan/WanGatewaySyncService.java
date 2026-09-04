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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.device.data.WanDeviceTransportConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "transport.wan", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WanGatewaySyncService {

    private final WanDeviceRegistryClient registryClient;
    private final WanConnectionManager connectionManager;
    private final WanNsRequestClient requestClient;
    private final WanGatewayCommandFactory commandFactory;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    @Async
    public void synchronizeAsync(UUID deviceId) {
        synchronize(deviceId);
    }

    void synchronize(UUID deviceId) {
        if (!inFlight.add(deviceId)) {
            return;
        }
        boolean syncing = false;
        try {
            WanDeviceRegistrySnapshot registry = registryClient.get(deviceId);
            if (registry == null || registry.deviceType() != WanDeviceType.GATEWAY
                    || registry.syncStatus() != WanDeviceSyncStatus.PENDING) {
                return;
            }
            if (!connectionManager.hasConnection(registry.connectionId())) {
                connectionManager.refresh();
            }
            registryClient.update(deviceId, WanDeviceSyncStatus.SYNCING, null, null);
            syncing = true;
            synchronizeGateway(registry);
        } catch (RuntimeException e) {
            if (syncing) {
                fail(deviceId, e);
            } else {
                log.warn("Unable to start WAN gateway synchronization for device [{}]", deviceId, e);
            }
        } finally {
            inFlight.remove(deviceId);
        }
    }

    private void synchronizeGateway(WanDeviceRegistrySnapshot registry) {
        JsonNode response = requestClient.execute(registry.connectionId(),
                commandFactory.getGateway(registry.externalId()));
        requireSuccessfulResponse(response, WanGatewayCommandFactory.GET_GATEWAY);
        JsonNode body = response.get("rsp_body");
        if (body == null || !body.isArray()) {
            throw new WanNsRequestException("NS get_gateway response body is not an array");
        }
        if (body.isEmpty()) {
            createGateway(registry);
            return;
        }
        if (body.size() != 1) {
            throw new WanNsRequestException("NS get_gateway response contains unexpected gateways");
        }
        WanGatewayConfiguration nsConfiguration = commandFactory.fromJson(body.get(0));
        if (!registry.externalId().equalsIgnoreCase(nsConfiguration.getGwId())) {
            throw new WanNsRequestException("NS get_gateway response gateway id does not match request");
        }
        registryClient.update(registry.deviceId(), WanDeviceSyncStatus.ACTIVE, null, nsConfiguration);
    }

    private void createGateway(WanDeviceRegistrySnapshot registry) {
        WanDeviceTransportConfiguration deviceConfiguration = JacksonUtil.fromString(
                registry.configuration(), WanDeviceTransportConfiguration.class);
        if (deviceConfiguration == null || deviceConfiguration.getDeviceType() != WanDeviceType.GATEWAY
                || deviceConfiguration.getGateway() == null || !deviceConfiguration.getGateway().isValid()) {
            throw new WanNsRequestException("Platform WAN gateway configuration is invalid");
        }
        registryClient.update(registry.deviceId(), WanDeviceSyncStatus.CREATING, null, null);
        JsonNode response = requestClient.execute(registry.connectionId(),
                commandFactory.addGateway(registry.deviceName(), deviceConfiguration.getGateway()));
        requireSuccessfulAddResponse(response);
        registryClient.update(registry.deviceId(), WanDeviceSyncStatus.ACTIVE, null, null);
    }

    private void requireSuccessfulResponse(JsonNode response, String operation) {
        JsonNode code = response == null ? null : response.get("rsp_code");
        if (code == null || !code.isIntegralNumber() || !code.canConvertToInt()) {
            throw new WanNsRequestException("NS " + operation + " response code is invalid");
        }
        if (code.intValue() != 0) {
            throw new WanNsRequestException(nsError(response, operation));
        }
    }

    private void requireSuccessfulAddResponse(JsonNode response) {
        JsonNode code = response == null ? null : response.get("rsp_code");
        if (code == null || !code.isArray() || code.size() != 1
                || !code.get(0).isIntegralNumber() || !code.get(0).canConvertToInt()) {
            throw new WanNsRequestException("NS add_gateway response code is invalid");
        }
        if (code.get(0).intValue() != 0) {
            throw new WanNsRequestException(nsError(response, WanGatewayCommandFactory.ADD_GATEWAY));
        }
    }

    private String nsError(JsonNode response, String operation) {
        JsonNode description = response.get("rsp_desc");
        String detail;
        if (description == null) {
            detail = "unknown error";
        } else if (description.isArray() && !description.isEmpty()) {
            detail = description.get(0).asText("unknown error");
        } else {
            detail = description.asText("unknown error");
        }
        return "NS " + operation + " failed: " + detail;
    }

    private void fail(UUID deviceId, RuntimeException error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        try {
            registryClient.update(deviceId, WanDeviceSyncStatus.FAILED, message, null);
        } catch (RuntimeException updateError) {
            log.error("Unable to persist WAN gateway synchronization failure for device [{}]", deviceId, updateError);
        }
    }

    int inFlightCount() {
        return inFlight.size();
    }
}
