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
package org.thingsboard.server.service.wan;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.device.credentials.WanDeviceCredentials;
import org.thingsboard.server.common.data.device.data.WanDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.profile.WanDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanTerminalConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanValidation;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.dao.device.DeviceCredentialsService;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.wan.WanDeviceRegistryService;
import org.thingsboard.server.exception.DataValidationException;
import org.thingsboard.server.queue.util.TbCoreComponent;

import java.util.UUID;

@Service
@TbCoreComponent
@RequiredArgsConstructor
public class WanDeviceRegistryManager {

    private final DeviceProfileService deviceProfileService;
    private final DeviceService deviceService;
    private final DeviceCredentialsService deviceCredentialsService;
    private final WanDeviceRegistryService registryService;

    @Transactional
    public WanDeviceRegistry registerCreatedDevice(Device device) {
        if (device == null || device.getId() == null || device.getDeviceData() == null
                || !(device.getDeviceData().getTransportConfiguration()
                instanceof WanDeviceTransportConfiguration configuration)) {
            return null;
        }
        if (isGateway(device) != (configuration.getDeviceType() == WanDeviceType.GATEWAY)) {
            throw new DataValidationException("WAN device type must match the gateway flag");
        }
        WanDeviceRegistry existing = registryService.findByDeviceId(device.getTenantId(), device.getId());
        if (existing != null) {
            return existing;
        }
        DeviceProfile profile = deviceProfileService.findDeviceProfileById(device.getTenantId(), device.getDeviceProfileId());
        if (profile == null || !(profile.getProfileData().getTransportConfiguration()
                instanceof WanDeviceProfileTransportConfiguration wanProfile)
                || wanProfile.getConnectionId() == null) {
            return null;
        }
        WanDeviceRegistry registry = new WanDeviceRegistry();
        registry.setTenantId(device.getTenantId());
        registry.setDeviceId(device.getId());
        registry.setConnectionId(wanProfile.getConnectionId());
        registry.setDeviceType(configuration.getDeviceType());
        registry.setExternalId(configuration.getExternalId());
        if (configuration.getDeviceType() == WanDeviceType.TERMINAL) {
            registry.setRelatedExternalId(resolveRelatedGatewayForCreation(
                    device.getTenantId(), wanProfile.getConnectionId(), configuration.getTerminal()));
        }
        registry.setDeviceName(device.getName());
        registry.setConfiguration(JacksonUtil.toString(configuration));
        registry.setSyncStatus(WanDeviceSyncStatus.PENDING);
        return registryService.save(registry);
    }

    public WanDeviceRegistry find(DeviceId deviceId) {
        return registryService.findByDeviceId(deviceId);
    }

    public PageData<WanDeviceRegistry> findPending(PageLink pageLink) {
        return registryService.findBySyncStatus(WanDeviceSyncStatus.PENDING, pageLink);
    }

    public PageData<WanDeviceRegistry> findByStatus(WanDeviceSyncStatus status, PageLink pageLink) {
        return registryService.findBySyncStatus(status, pageLink);
    }

    @Transactional
    public WanDeviceRegistry requestSync(TenantId tenantId, DeviceId deviceId, boolean retryOnly) {
        WanDeviceRegistry registry = registryService.findByDeviceIdForUpdate(tenantId, deviceId);
        if (registry == null) {
            return null;
        }
        WanDeviceSyncStatus status = registry.getSyncStatus();
        if (status == WanDeviceSyncStatus.FAILED
                && (registry.getDeletionConnectionId() != null || registry.getDeletionExternalId() != null)) {
            throw new DataValidationException(
                    "Failed WAN recreation must be retried with the platform recreation operation");
        }
        if (status == WanDeviceSyncStatus.PENDING
                || status == WanDeviceSyncStatus.SYNCING
                || status == WanDeviceSyncStatus.CREATING) {
            return registry;
        }
        if (retryOnly && status != WanDeviceSyncStatus.FAILED) {
            throw new DataValidationException("Only a failed WAN synchronization can be retried");
        }
        if (status != WanDeviceSyncStatus.ACTIVE
                && status != WanDeviceSyncStatus.UNKNOWN
                && status != WanDeviceSyncStatus.FAILED) {
            throw new DataValidationException("WAN device synchronization cannot be requested in state " + status);
        }
        registry.setSyncStatus(WanDeviceSyncStatus.PENDING);
        registry.setError(null);
        return registryService.save(registry);
    }

    @Transactional
    public WanDeviceRegistry requestRecreate(TenantId tenantId, DeviceId deviceId) {
        WanDeviceRegistry registry = registryService.findByDeviceIdForUpdate(tenantId, deviceId);
        if (registry == null) {
            return null;
        }
        if (registry.getSyncStatus() == WanDeviceSyncStatus.RECREATING) {
            return registry;
        }
        if (registry.getSyncStatus() == WanDeviceSyncStatus.PENDING
                || registry.getSyncStatus() == WanDeviceSyncStatus.SYNCING
                || registry.getSyncStatus() == WanDeviceSyncStatus.CREATING
                || registry.getSyncStatus() == WanDeviceSyncStatus.DELETING) {
            throw new DataValidationException(
                    "WAN device cannot be recreated in state " + registry.getSyncStatus());
        }
        Device device = deviceService.findDeviceById(tenantId, deviceId);
        if (device == null || device.getDeviceData() == null
                || !(device.getDeviceData().getTransportConfiguration()
                instanceof WanDeviceTransportConfiguration configuration)
                || isGateway(device) != (configuration.getDeviceType() == WanDeviceType.GATEWAY)) {
            throw new DataValidationException("WAN device configuration is unavailable");
        }
        DeviceProfile profile = deviceProfileService.findDeviceProfileById(tenantId, device.getDeviceProfileId());
        if (profile == null || !(profile.getProfileData().getTransportConfiguration()
                instanceof WanDeviceProfileTransportConfiguration wanProfile)
                || wanProfile.getConnectionId() == null) {
            throw new DataValidationException("WAN device profile connection is unavailable");
        }
        if (registry.getDeletionConnectionId() == null) {
            registry.setDeletionConnectionId(registry.getConnectionId());
        }
        if (registry.getDeletionExternalId() == null) {
            registry.setDeletionExternalId(registry.getExternalId());
        }
        registry.setConnectionId(wanProfile.getConnectionId());
        registry.setExternalId(configuration.getExternalId());
        registry.setDeviceType(configuration.getDeviceType());
        registry.setDeviceName(device.getName());
        registry.setConfiguration(JacksonUtil.toString(configuration));
        registry.setRelatedExternalId(configuration.getDeviceType() == WanDeviceType.TERMINAL
                ? resolveRelatedGatewayForCreation(tenantId, wanProfile.getConnectionId(), configuration.getTerminal())
                : null);
        registry.setSyncStatus(WanDeviceSyncStatus.RECREATING);
        registry.setError(null);
        registry.setRetryCount(0);
        return registryService.save(registry);
    }

    @Transactional
    public WanDeviceRegistry prepareDeletion(TenantId tenantId, DeviceId deviceId) {
        WanDeviceRegistry registry = registryService.findByDeviceIdForUpdate(tenantId, deviceId);
        if (registry == null) {
            return null;
        }
        registry.setDeletionConnectionId(registry.getConnectionId());
        registry.setDeletionExternalId(registry.getExternalId());
        registry.setSyncStatus(WanDeviceSyncStatus.DELETING);
        registry.setError(null);
        registry.setRetryCount(0);
        return registryService.save(registry);
    }

    @Transactional
    public WanDeviceRegistry update(DeviceId deviceId, WanDeviceSyncStatus targetStatus,
                                    String error, String gatewayConfiguration,
                                    String terminalConfiguration, String terminalRootKey,
                                    String relatedExternalId) {
        return update(deviceId, targetStatus, error, gatewayConfiguration, terminalConfiguration,
                terminalRootKey, relatedExternalId, false, false);
    }

    @Transactional
    public WanDeviceRegistry update(DeviceId deviceId, WanDeviceSyncStatus targetStatus,
                                    String error, String gatewayConfiguration,
                                    String terminalConfiguration, String terminalRootKey,
                                    String relatedExternalId, boolean deleteRegistry,
                                    boolean deletionOperation) {
        WanDeviceRegistry registry = registryService.findByDeviceId(deviceId);
        if (registry == null) {
            return null;
        }
        if (registry.getSyncStatus() == WanDeviceSyncStatus.DELETING && !deletionOperation) {
            throw new IllegalArgumentException("Only a WAN deletion operation can update a deletion tombstone");
        }
        if (deletionOperation && registry.getSyncStatus() != WanDeviceSyncStatus.DELETING) {
            throw new IllegalArgumentException("WAN deletion operation requires a deletion tombstone");
        }
        if (deleteRegistry && !deletionOperation) {
            throw new IllegalArgumentException("WAN registry cleanup requires a deletion operation");
        }
        if (deleteRegistry) {
            registryService.deleteByDeviceId(deviceId);
            return null;
        }
        WanDeviceSyncStatus previousStatus = registry.getSyncStatus();
        validateTransition(registry.getSyncStatus(), targetStatus);
        if (gatewayConfiguration != null) {
            applyGatewayConfiguration(registry, gatewayConfiguration);
        }
        if (terminalConfiguration != null) {
            applyTerminalConfiguration(registry, terminalConfiguration, terminalRootKey, relatedExternalId);
        }
        registry.setSyncStatus(targetStatus);
        registry.setError(normalizeError(error));
        if (previousStatus == WanDeviceSyncStatus.DELETING && error != null) {
            registry.setRetryCount(registry.getRetryCount() + 1);
        }
        if (previousStatus == WanDeviceSyncStatus.RECREATING && targetStatus == WanDeviceSyncStatus.ACTIVE) {
            registry.setDeletionConnectionId(null);
            registry.setDeletionExternalId(null);
            registry.setRetryCount(0);
        }
        if (targetStatus == WanDeviceSyncStatus.ACTIVE
                || targetStatus == WanDeviceSyncStatus.UNKNOWN
                || targetStatus == WanDeviceSyncStatus.FAILED) {
            long completionTime = System.currentTimeMillis();
            registry.setLastSyncTime(completionTime);
            if (targetStatus == WanDeviceSyncStatus.ACTIVE) {
                registry.setLastSuccessfulSyncTime(completionTime);
            }
        }
        return registryService.save(registry);
    }

    private void applyGatewayConfiguration(WanDeviceRegistry registry, String gatewayConfiguration) {
        if (registry.getDeviceType() != WanDeviceType.GATEWAY) {
            throw new IllegalArgumentException("WAN registry does not represent a gateway");
        }
        var nsConfiguration = JacksonUtil.fromString(gatewayConfiguration,
                org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration.class);
        if (nsConfiguration == null || !nsConfiguration.isValid()
                || !registry.getExternalId().equalsIgnoreCase(nsConfiguration.getGwId())) {
            throw new IllegalArgumentException("NS gateway configuration is invalid or does not match the registry");
        }
        Device device = deviceService.findDeviceById(registry.getTenantId(), registry.getDeviceId());
        if (device == null || !(device.getDeviceData().getTransportConfiguration()
                instanceof WanDeviceTransportConfiguration deviceConfiguration)
                || deviceConfiguration.getDeviceType() != WanDeviceType.GATEWAY) {
            throw new IllegalArgumentException("WAN gateway device is unavailable");
        }
        deviceConfiguration.setGateway(nsConfiguration);
        device.getDeviceData().setTransportConfiguration(deviceConfiguration);
        deviceService.saveDevice(device);
        registry.setConfiguration(JacksonUtil.toString(deviceConfiguration));
    }

    private void applyTerminalConfiguration(WanDeviceRegistry registry, String terminalConfiguration,
                                            String rootKey, String relatedExternalId) {
        if (registry.getDeviceType() != WanDeviceType.TERMINAL) {
            throw new IllegalArgumentException("WAN registry does not represent a terminal");
        }
        WanTerminalConfiguration nsConfiguration = JacksonUtil.fromString(
                terminalConfiguration, WanTerminalConfiguration.class);
        if (nsConfiguration == null || !nsConfiguration.isValid()
                || !registry.getExternalId().equalsIgnoreCase(nsConfiguration.getDevEui())) {
            throw new IllegalArgumentException("NS terminal configuration is invalid or does not match the registry");
        }
        nsConfiguration.setRelatedGatewayId(resolveRelatedGatewayFromNs(registry, relatedExternalId));
        Device device = deviceService.findDeviceById(registry.getTenantId(), registry.getDeviceId());
        if (device == null || device.getDeviceData() == null
                || !(device.getDeviceData().getTransportConfiguration()
                instanceof WanDeviceTransportConfiguration deviceConfiguration)
                || deviceConfiguration.getDeviceType() != WanDeviceType.TERMINAL) {
            throw new IllegalArgumentException("WAN terminal device is unavailable");
        }
        deviceConfiguration.setTerminal(nsConfiguration);
        device.getDeviceData().setTransportConfiguration(deviceConfiguration);
        deviceService.saveDevice(device);
        updateRootKey(registry, nsConfiguration, rootKey);
        registry.setRelatedExternalId(normalizeRelatedExternalId(relatedExternalId));
        registry.setConfiguration(JacksonUtil.toString(deviceConfiguration));
    }

    private void updateRootKey(WanDeviceRegistry registry, WanTerminalConfiguration configuration, String rootKey) {
        String normalizedRootKey = rootKey == null || rootKey.isBlank() ? null : rootKey.trim().toUpperCase();
        if (normalizedRootKey != null && !WanValidation.isHex(normalizedRootKey, 32)) {
            throw new IllegalArgumentException("NS terminal root key is invalid");
        }
        if (configuration.getSecurityMode() != 0 && normalizedRootKey == null) {
            throw new IllegalArgumentException("NS terminal root key is required for the selected security mode");
        }
        DeviceCredentials credentials = deviceCredentialsService.findDeviceCredentialsByDeviceId(
                registry.getTenantId(), registry.getDeviceId());
        if (credentials == null || credentials.getCredentialsType() != DeviceCredentialsType.WAN_CREDENTIALS) {
            throw new IllegalArgumentException("WAN terminal credentials are unavailable");
        }
        WanDeviceCredentials wanCredentials = new WanDeviceCredentials();
        wanCredentials.setRootKey(normalizedRootKey);
        credentials.setCredentialsValue(JacksonUtil.toString(wanCredentials));
        deviceCredentialsService.updateDeviceCredentials(registry.getTenantId(), credentials);
    }

    private String resolveRelatedGatewayForCreation(TenantId tenantId, UUID connectionId,
                                                    WanTerminalConfiguration terminal) {
        if (terminal.getRelatedGatewayId() == null) {
            return null;
        }
        Device gateway = deviceService.findDeviceById(tenantId, terminal.getRelatedGatewayId());
        if (gateway == null || !isGateway(gateway) || gateway.getDeviceData() == null
                || !(gateway.getDeviceData().getTransportConfiguration()
                instanceof WanDeviceTransportConfiguration gatewayConfiguration)
                || gatewayConfiguration.getDeviceType() != WanDeviceType.GATEWAY) {
            throw new DataValidationException("Related WAN gateway must exist in the current tenant");
        }
        DeviceProfile gatewayProfile = deviceProfileService.findDeviceProfileById(
                tenantId, gateway.getDeviceProfileId());
        if (gatewayProfile == null || !(gatewayProfile.getProfileData().getTransportConfiguration()
                instanceof WanDeviceProfileTransportConfiguration gatewayWanProfile)
                || !connectionId.equals(gatewayWanProfile.getConnectionId())) {
            throw new DataValidationException("Related WAN gateway must use the same NS connection");
        }
        return gatewayConfiguration.getGateway().getGwId();
    }

    private DeviceId resolveRelatedGatewayFromNs(WanDeviceRegistry registry, String relatedExternalId) {
        String normalized = normalizeRelatedExternalId(relatedExternalId);
        if (normalized == null) {
            return null;
        }
        if (!WanValidation.isHex(normalized, 16)) {
            throw new IllegalArgumentException("NS terminal related gateway id is invalid");
        }
        WanDeviceRegistry gateway = registryService.findGatewayByExternalId(
                registry.getTenantId(), registry.getConnectionId(), normalized);
        if (gateway == null) {
            throw new IllegalArgumentException("NS terminal related gateway is not registered in the same connection");
        }
        return gateway.getDeviceId();
    }

    private String normalizeRelatedExternalId(String relatedExternalId) {
        return relatedExternalId == null || relatedExternalId.isBlank()
                ? null : relatedExternalId.trim().toUpperCase();
    }

    private void validateTransition(WanDeviceSyncStatus current, WanDeviceSyncStatus target) {
        boolean allowed = switch (current) {
            case PENDING -> target == WanDeviceSyncStatus.SYNCING;
            case SYNCING -> target == WanDeviceSyncStatus.CREATING
                    || target == WanDeviceSyncStatus.ACTIVE
                    || target == WanDeviceSyncStatus.UNKNOWN
                    || target == WanDeviceSyncStatus.FAILED;
            case CREATING -> target == WanDeviceSyncStatus.ACTIVE
                    || target == WanDeviceSyncStatus.UNKNOWN
                    || target == WanDeviceSyncStatus.FAILED;
            case RECREATING -> target == WanDeviceSyncStatus.ACTIVE
                    || target == WanDeviceSyncStatus.FAILED;
            case DELETING -> target == WanDeviceSyncStatus.DELETING
                    || target == WanDeviceSyncStatus.FAILED;
            case ACTIVE, UNKNOWN, FAILED -> false;
        };
        if (!allowed) {
            throw new IllegalArgumentException("Invalid WAN sync transition from " + current + " to " + target);
        }
    }

    private String normalizeError(String error) {
        if (error == null || error.isBlank()) {
            return null;
        }
        String trimmed = error.trim();
        return trimmed.length() <= 4096 ? trimmed : trimmed.substring(0, 4096);
    }

    private boolean isGateway(Device device) {
        return device != null && device.getId() != null && device.getDeviceData() != null
                && device.getAdditionalInfo() != null
                && device.getAdditionalInfo().path(DataConstants.GATEWAY_PARAMETER).asBoolean(false);
    }
}
