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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.gen.transport.TransportProtos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class WanDeviceRegistryClient {

    private final TransportService transportService;
    private final int pageSize;

    public WanDeviceRegistryClient(TransportService transportService,
                                   @Value("${transport.wan.bootstrap_page_size:200}") int pageSize) {
        this.transportService = transportService;
        this.pageSize = pageSize;
    }

    public WanDeviceRegistrySnapshot get(UUID deviceId) {
        TransportProtos.GetWanDeviceRegistryResponseMsg response = transportService.getWanDeviceRegistry(
                TransportProtos.GetWanDeviceRegistryRequestMsg.newBuilder()
                        .setDeviceIdMSB(deviceId.getMostSignificantBits())
                        .setDeviceIdLSB(deviceId.getLeastSignificantBits())
                        .build());
        return response.hasRegistry() ? fromProto(response.getRegistry()) : null;
    }

    public List<UUID> getPendingDeviceIds() {
        List<UUID> result = new ArrayList<>();
        int page = 0;
        boolean hasNext;
        do {
            TransportProtos.GetPendingWanDeviceRegistriesResponseMsg response =
                    transportService.getPendingWanDeviceRegistries(
                            TransportProtos.GetPendingWanDeviceRegistriesRequestMsg.newBuilder()
                                    .setPage(page++)
                                    .setPageSize(pageSize)
                                    .build());
            response.getRegistriesList().stream()
                    .map(registry -> new UUID(registry.getDeviceIdMSB(), registry.getDeviceIdLSB()))
                    .forEach(result::add);
            hasNext = response.getHasNextPage();
        } while (hasNext);
        return List.copyOf(result);
    }

    public WanDeviceRegistrySnapshot update(UUID deviceId, WanDeviceSyncStatus status,
                                            String error, WanGatewayConfiguration gatewayConfiguration) {
        TransportProtos.UpdateWanDeviceRegistryRequestMsg.Builder request =
                TransportProtos.UpdateWanDeviceRegistryRequestMsg.newBuilder()
                        .setDeviceIdMSB(deviceId.getMostSignificantBits())
                        .setDeviceIdLSB(deviceId.getLeastSignificantBits())
                        .setSyncStatus(status.name());
        if (error != null) {
            request.setError(error);
        }
        if (gatewayConfiguration != null) {
            request.setGatewayConfiguration(JacksonUtil.toString(gatewayConfiguration));
        }
        TransportProtos.GetWanDeviceRegistryResponseMsg response =
                transportService.updateWanDeviceRegistry(request.build());
        return response.hasRegistry() ? fromProto(response.getRegistry()) : null;
    }

    private WanDeviceRegistrySnapshot fromProto(TransportProtos.WanDeviceRegistryProto registry) {
        return new WanDeviceRegistrySnapshot(
                new UUID(registry.getDeviceIdMSB(), registry.getDeviceIdLSB()),
                new UUID(registry.getTenantIdMSB(), registry.getTenantIdLSB()),
                new UUID(registry.getConnectionIdMSB(), registry.getConnectionIdLSB()),
                WanDeviceType.valueOf(registry.getDeviceType()),
                registry.getExternalId(),
                registry.getDeviceName(),
                registry.getConfiguration(),
                WanDeviceSyncStatus.valueOf(registry.getSyncStatus()),
                registry.hasLastSyncTime() ? registry.getLastSyncTime() : null,
                registry.hasNextSyncTime() ? registry.getNextSyncTime() : null,
                registry.hasError() ? registry.getError() : null,
                registry.getVersion());
    }
}
