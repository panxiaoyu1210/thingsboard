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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.device.profile.WanDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.util.ProtoUtils;
import org.thingsboard.server.gen.transport.TransportProtos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class WanTransportConfigurationProvider {

    private final TransportService transportService;
    private final WanTransportPasswordService passwordService;

    @Value("${transport.wan.bootstrap_page_size:200}")
    private int pageSize;

    public WanConfigurationSnapshot load() {
        return new WanConfigurationSnapshot(loadConnections(), loadDevices());
    }

    private List<WanConnectionConfig> loadConnections() {
        List<WanConnectionConfig> result = new ArrayList<>();
        int page = 0;
        boolean hasNext;
        do {
            TransportProtos.GetWanConnectionsResponseMsg response = transportService.getWanConnections(
                    TransportProtos.GetWanConnectionsRequestMsg.newBuilder()
                            .setPage(page++)
                            .setPageSize(pageSize)
                            .build());
            response.getConnectionsList().stream().map(this::fromProto).forEach(result::add);
            hasNext = response.getHasNextPage();
        } while (hasNext);
        return List.copyOf(result);
    }

    private List<WanDeviceDescriptor> loadDevices() {
        List<WanDeviceDescriptor> result = new ArrayList<>();
        int page = 0;
        boolean hasNext;
        do {
            TransportProtos.GetWanDevicesResponseMsg response = transportService.getWanDevicesIds(
                    TransportProtos.GetWanDevicesRequestMsg.newBuilder()
                            .setPage(page++)
                            .setPageSize(pageSize)
                            .build());
            response.getIdsList().stream()
                    .map(UUID::fromString)
                    .map(this::loadDevice)
                    .filter(java.util.Objects::nonNull)
                    .forEach(result::add);
            hasNext = response.getHasNextPage();
        } while (hasNext);
        return List.copyOf(result);
    }

    private WanDeviceDescriptor loadDevice(UUID deviceId) {
        TransportProtos.GetDeviceResponseMsg device = transportService.getDevice(
                TransportProtos.GetDeviceRequestMsg.newBuilder()
                        .setDeviceIdMSB(deviceId.getMostSignificantBits())
                        .setDeviceIdLSB(deviceId.getLeastSignificantBits())
                        .build());
        if (device == null) {
            log.warn("WAN device [{}] disappeared during configuration refresh", deviceId);
            return null;
        }
        UUID profileId = new UUID(device.getDeviceProfileIdMSB(), device.getDeviceProfileIdLSB());
        TransportProtos.GetEntityProfileResponseMsg profileResponse = transportService.getEntityProfile(
                TransportProtos.GetEntityProfileRequestMsg.newBuilder()
                        .setEntityType(EntityType.DEVICE_PROFILE.name())
                        .setEntityIdMSB(profileId.getMostSignificantBits())
                        .setEntityIdLSB(profileId.getLeastSignificantBits())
                        .build());
        if (!profileResponse.hasDeviceProfile()) {
            log.warn("WAN device [{}] profile [{}] is unavailable during configuration refresh", deviceId, profileId);
            return null;
        }
        DeviceProfile profile = ProtoUtils.fromProto(profileResponse.getDeviceProfile());
        if (!(profile.getProfileData().getTransportConfiguration()
                instanceof WanDeviceProfileTransportConfiguration wanProfile)) {
            log.warn("WAN device [{}] profile [{}] no longer contains WAN configuration", deviceId, profileId);
            return null;
        }
        return new WanDeviceDescriptor(deviceId, profileId, wanProfile.getConnectionId(),
                device.getDeviceTransportConfiguration().toByteArray());
    }

    private WanConnectionConfig fromProto(TransportProtos.WanConnectionProto proto) {
        return new WanConnectionConfig(
                new UUID(proto.getConnectionIdMSB(), proto.getConnectionIdLSB()),
                new UUID(proto.getTenantIdMSB(), proto.getTenantIdLSB()),
                proto.getName(), proto.getBrokerHost(), proto.getBrokerPort(), proto.getTls(), proto.getClientId(),
                proto.hasUsername() ? proto.getUsername() : null,
                proto.hasEncryptedPassword() ? passwordService.decrypt(proto.getEncryptedPassword()) : null,
                proto.getNsPublishTopic(), proto.getNsSubscribeTopic(), proto.getQos(), proto.getEnabled(),
                proto.getRequestTimeoutMs(), proto.getSyncIntervalHours(), proto.getVersion());
    }

}
