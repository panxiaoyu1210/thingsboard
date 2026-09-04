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
import org.thingsboard.server.common.data.device.data.WanDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.profile.WanDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.wan.WanDeviceRegistryService;
import org.thingsboard.server.queue.util.TbCoreComponent;

@Service
@TbCoreComponent
@RequiredArgsConstructor
public class WanDeviceRegistryManager {

    private final DeviceProfileService deviceProfileService;
    private final DeviceService deviceService;
    private final WanDeviceRegistryService registryService;

    @Transactional
    public WanDeviceRegistry registerCreatedGateway(Device device) {
        if (!isGateway(device)
                || !(device.getDeviceData().getTransportConfiguration()
                instanceof WanDeviceTransportConfiguration configuration)
                || configuration.getDeviceType() != WanDeviceType.GATEWAY) {
            return null;
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
        registry.setDeviceType(WanDeviceType.GATEWAY);
        registry.setExternalId(configuration.getGateway().getGwId());
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

    @Transactional
    public WanDeviceRegistry update(DeviceId deviceId, WanDeviceSyncStatus targetStatus,
                                    String error, String gatewayConfiguration) {
        WanDeviceRegistry registry = registryService.findByDeviceId(deviceId);
        if (registry == null) {
            return null;
        }
        validateTransition(registry.getSyncStatus(), targetStatus);
        if (gatewayConfiguration != null) {
            applyGatewayConfiguration(registry, gatewayConfiguration);
        }
        registry.setSyncStatus(targetStatus);
        registry.setError(normalizeError(error));
        if (targetStatus == WanDeviceSyncStatus.ACTIVE
                || targetStatus == WanDeviceSyncStatus.UNKNOWN
                || targetStatus == WanDeviceSyncStatus.FAILED) {
            registry.setLastSyncTime(System.currentTimeMillis());
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
            case ACTIVE, UNKNOWN, FAILED, RECREATING, DELETING -> false;
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
