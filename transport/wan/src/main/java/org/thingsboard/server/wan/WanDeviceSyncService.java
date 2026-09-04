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
import org.thingsboard.server.common.data.transport.wan.WanTerminalConfiguration;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "transport.wan", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WanDeviceSyncService {

    private final WanDeviceRegistryClient registryClient;
    private final WanConnectionManager connectionManager;
    private final WanNsRequestClient requestClient;
    private final WanGatewayCommandFactory gatewayCommandFactory;
    private final WanTerminalCommandFactory terminalCommandFactory;
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
            if (registry == null || registry.syncStatus() != WanDeviceSyncStatus.PENDING) {
                return;
            }
            if (!connectionManager.hasConnection(registry.connectionId())) {
                connectionManager.refresh();
            }
            registryClient.update(deviceId, WanDeviceSyncStatus.SYNCING, null, null);
            syncing = true;
            switch (registry.deviceType()) {
                case GATEWAY -> synchronizeGateway(registry);
                case TERMINAL -> synchronizeTerminal(registry);
            }
        } catch (RuntimeException e) {
            if (syncing) {
                fail(deviceId, e);
            } else {
                log.warn("Unable to start WAN device synchronization for device [{}]", deviceId, e);
            }
        } finally {
            inFlight.remove(deviceId);
        }
    }

    private void synchronizeGateway(WanDeviceRegistrySnapshot registry) {
        JsonNode response = requestClient.execute(registry.connectionId(),
                gatewayCommandFactory.getGateway(registry.externalId()));
        requireSuccessfulResponse(response, WanGatewayCommandFactory.GET_GATEWAY);
        JsonNode body = requireArrayBody(response, WanGatewayCommandFactory.GET_GATEWAY);
        if (body.isEmpty()) {
            createGateway(registry);
            return;
        }
        if (body.size() != 1) {
            throw new WanNsRequestException("NS get_gateway response contains unexpected gateways");
        }
        WanGatewayConfiguration nsConfiguration = gatewayCommandFactory.fromJson(body.get(0));
        if (!registry.externalId().equalsIgnoreCase(nsConfiguration.getGwId())) {
            throw new WanNsRequestException("NS get_gateway response gateway id does not match request");
        }
        registryClient.update(registry.deviceId(), WanDeviceSyncStatus.ACTIVE, null, nsConfiguration);
    }

    private void createGateway(WanDeviceRegistrySnapshot registry) {
        WanDeviceTransportConfiguration deviceConfiguration = platformConfiguration(registry);
        if (deviceConfiguration.getDeviceType() != WanDeviceType.GATEWAY
                || deviceConfiguration.getGateway() == null || !deviceConfiguration.getGateway().isValid()) {
            throw new WanNsRequestException("Platform WAN gateway configuration is invalid");
        }
        registryClient.update(registry.deviceId(), WanDeviceSyncStatus.CREATING, null, null);
        JsonNode response = requestClient.execute(registry.connectionId(),
                gatewayCommandFactory.addGateway(registry.deviceName(), deviceConfiguration.getGateway()));
        requireSuccessfulAddResponse(response, WanGatewayCommandFactory.ADD_GATEWAY);
        registryClient.update(registry.deviceId(), WanDeviceSyncStatus.ACTIVE, null, null);
    }

    private void synchronizeTerminal(WanDeviceRegistrySnapshot registry) {
        JsonNode response = requestClient.execute(registry.connectionId(),
                terminalCommandFactory.getTerminal(registry.externalId()));
        requireSuccessfulResponse(response, WanTerminalCommandFactory.GET_TERMINAL);
        JsonNode body = requireArrayBody(response, WanTerminalCommandFactory.GET_TERMINAL);
        if (body.isEmpty()) {
            createTerminal(registry);
            return;
        }
        if (body.size() != 1) {
            throw new WanNsRequestException("NS get_terminal response contains unexpected terminals");
        }
        WanNsTerminalConfiguration nsConfiguration = terminalCommandFactory.fromJson(body.get(0));
        if (!registry.externalId().equalsIgnoreCase(nsConfiguration.deviceConfiguration().getDevEui())) {
            throw new WanNsRequestException("NS get_terminal response device EUI does not match request");
        }
        registryClient.updateTerminal(registry.deviceId(), WanDeviceSyncStatus.ACTIVE,
                nsConfiguration.deviceConfiguration(), nsConfiguration.rootKey(),
                nsConfiguration.relatedExternalId());
    }

    private void createTerminal(WanDeviceRegistrySnapshot registry) {
        WanDeviceTransportConfiguration deviceConfiguration = platformConfiguration(registry);
        WanTerminalConfiguration terminal = deviceConfiguration.getTerminal();
        if (deviceConfiguration.getDeviceType() != WanDeviceType.TERMINAL
                || terminal == null || !terminal.isValid()) {
            throw new WanNsRequestException("Platform WAN terminal configuration is invalid");
        }
        registryClient.update(registry.deviceId(), WanDeviceSyncStatus.CREATING, null, null);
        JsonNode response = requestClient.execute(registry.connectionId(), terminalCommandFactory.addTerminal(
                registry.deviceName(), terminal, registry.terminalRootKey(), registry.relatedExternalId()));
        requireSuccessfulAddResponse(response, WanTerminalCommandFactory.ADD_TERMINAL);
        registryClient.update(registry.deviceId(), WanDeviceSyncStatus.ACTIVE, null, null);
    }

    private WanDeviceTransportConfiguration platformConfiguration(WanDeviceRegistrySnapshot registry) {
        WanDeviceTransportConfiguration configuration = JacksonUtil.fromString(
                registry.configuration(), WanDeviceTransportConfiguration.class);
        if (configuration == null) {
            throw new WanNsRequestException("Platform WAN device configuration is invalid");
        }
        return configuration;
    }

    private JsonNode requireArrayBody(JsonNode response, String operation) {
        JsonNode body = response.get("rsp_body");
        if (body == null || !body.isArray()) {
            throw new WanNsRequestException("NS " + operation + " response body is not an array");
        }
        return body;
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

    private void requireSuccessfulAddResponse(JsonNode response, String operation) {
        JsonNode code = response == null ? null : response.get("rsp_code");
        if (code == null || !code.isArray() || code.size() != 1
                || !code.get(0).isIntegralNumber() || !code.get(0).canConvertToInt()) {
            throw new WanNsRequestException("NS " + operation + " response code is invalid");
        }
        if (code.get(0).intValue() != 0) {
            throw new WanNsRequestException(nsError(response, operation));
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
            log.error("Unable to persist WAN device synchronization failure for device [{}]", deviceId, updateError);
        }
    }

    int inFlightCount() {
        return inFlight.size();
    }
}
