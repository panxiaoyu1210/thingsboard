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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanTerminalConfiguration;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class WanDeviceRegistryClient {

    private final TransportService transportService;
    private final int pageSize;
    private final int batchSize;
    private final long taskLeaseMs;
    private final String ownerId;
    private final Clock clock;

    @Autowired
    public WanDeviceRegistryClient(TransportService transportService,
                                   @Value("${transport.wan.bootstrap_page_size:200}") int pageSize,
                                   @Value("${transport.wan.sync_batch_size:100}") int batchSize,
                                   @Value("${transport.wan.task_lease_ms:900000}") long taskLeaseMs,
                                   TbServiceInfoProvider serviceInfoProvider,
                                   Clock clock) {
        this(transportService, pageSize, batchSize, taskLeaseMs, serviceInfoProvider.getServiceId(), clock);
    }

    public WanDeviceRegistryClient(TransportService transportService, int pageSize) {
        this(transportService, pageSize, pageSize, 900_000L, "wan-test", Clock.systemUTC());
    }

    WanDeviceRegistryClient(TransportService transportService, int pageSize, int batchSize,
                            long taskLeaseMs, String ownerId, Clock clock) {
        this.transportService = transportService;
        this.pageSize = pageSize;
        this.batchSize = batchSize;
        this.taskLeaseMs = taskLeaseMs;
        this.ownerId = ownerId;
        this.clock = clock;
    }

    public WanDeviceRegistrySnapshot get(UUID deviceId) {
        TransportProtos.GetWanDeviceRegistryResponseMsg response = transportService.getWanDeviceRegistry(
                TransportProtos.GetWanDeviceRegistryRequestMsg.newBuilder()
                        .setDeviceIdMSB(deviceId.getMostSignificantBits())
                        .setDeviceIdLSB(deviceId.getLeastSignificantBits())
                        .build());
        return response.hasRegistry() ? fromProto(response.getRegistry()) : null;
    }

    public WanDeviceRegistrySnapshot claim(UUID deviceId) {
        long now = clock.millis();
        TransportProtos.GetWanDeviceRegistryResponseMsg response = transportService.getWanDeviceRegistry(
                TransportProtos.GetWanDeviceRegistryRequestMsg.newBuilder()
                        .setDeviceIdMSB(deviceId.getMostSignificantBits())
                        .setDeviceIdLSB(deviceId.getLeastSignificantBits())
                        .setOwnerId(ownerId)
                        .setNow(now)
                        .setLeaseUntil(Math.addExact(now, taskLeaseMs))
                        .build());
        return response.hasRegistry() ? fromProto(response.getRegistry()) : null;
    }

    public List<WanDeviceRegistrySnapshot> claimAvailable() {
        long now = clock.millis();
        TransportProtos.GetPendingWanDeviceRegistriesResponseMsg response =
                transportService.getPendingWanDeviceRegistries(
                        TransportProtos.GetPendingWanDeviceRegistriesRequestMsg.newBuilder()
                                .setPageSize(batchSize)
                                .setOwnerId(ownerId)
                                .setNow(now)
                                .setLeaseUntil(Math.addExact(now, taskLeaseMs))
                                .setClaimAvailable(true)
                                .build());
        return response.getRegistriesList().stream().map(this::fromProto).toList();
    }

    public void release(UUID deviceId) {
        transportService.updateWanDeviceRegistry(
                TransportProtos.UpdateWanDeviceRegistryRequestMsg.newBuilder()
                        .setDeviceIdMSB(deviceId.getMostSignificantBits())
                        .setDeviceIdLSB(deviceId.getLeastSignificantBits())
                        .setSyncStatus(WanDeviceSyncStatus.PENDING.name())
                        .setLockOwnerId(ownerId)
                        .setReleaseTask(true)
                        .build());
    }

    public List<UUID> getPendingDeviceIds() {
        return getDeviceIds(WanDeviceSyncStatus.PENDING);
    }

    public List<UUID> getDeviceIds(WanDeviceSyncStatus status) {
        List<UUID> result = new ArrayList<>();
        int page = 0;
        boolean hasNext;
        do {
            TransportProtos.GetPendingWanDeviceRegistriesResponseMsg response =
                    transportService.getPendingWanDeviceRegistries(
                            TransportProtos.GetPendingWanDeviceRegistriesRequestMsg.newBuilder()
                                    .setPage(page++)
                                    .setPageSize(pageSize)
                                    .setSyncStatus(status.name())
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
        return update(deviceId, status, error, gatewayConfiguration, null, null, null, false);
    }

    public WanDeviceRegistrySnapshot updateTerminal(UUID deviceId, WanDeviceSyncStatus status,
                                                    WanTerminalConfiguration terminalConfiguration,
                                                    String rootKey, String relatedExternalId) {
        return update(deviceId, status, null, null, terminalConfiguration, rootKey, relatedExternalId, false);
    }

    public WanDeviceRegistrySnapshot updateDeletionFailure(UUID deviceId, WanDeviceSyncStatus status,
                                                           String error) {
        return update(deviceId, status, error, null, null, null, null, true);
    }

    private WanDeviceRegistrySnapshot update(UUID deviceId, WanDeviceSyncStatus status, String error,
                                             WanGatewayConfiguration gatewayConfiguration,
                                             WanTerminalConfiguration terminalConfiguration,
                                             String rootKey, String relatedExternalId,
                                             boolean deletionOperation) {
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
        if (terminalConfiguration != null) {
            request.setTerminalConfiguration(JacksonUtil.toString(terminalConfiguration));
        }
        if (rootKey != null) {
            request.setTerminalRootKey(rootKey);
        }
        if (relatedExternalId != null) {
            request.setRelatedExternalId(relatedExternalId);
        }
        request.setDeletionOperation(deletionOperation)
                .setLockOwnerId(ownerId)
                .setOperationTime(clock.millis());
        TransportProtos.GetWanDeviceRegistryResponseMsg response =
                transportService.updateWanDeviceRegistry(request.build());
        return response.hasRegistry() ? fromProto(response.getRegistry()) : null;
    }

    public void completeDeletion(UUID deviceId) {
        transportService.updateWanDeviceRegistry(
                TransportProtos.UpdateWanDeviceRegistryRequestMsg.newBuilder()
                        .setDeviceIdMSB(deviceId.getMostSignificantBits())
                        .setDeviceIdLSB(deviceId.getLeastSignificantBits())
                        .setSyncStatus(WanDeviceSyncStatus.DELETING.name())
                        .setDeleteRegistry(true)
                        .setDeletionOperation(true)
                        .setLockOwnerId(ownerId)
                        .setOperationTime(clock.millis())
                        .build());
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
                registry.getVersion(),
                registry.hasRelatedExternalId() ? registry.getRelatedExternalId() : null,
                registry.hasTerminalRootKey() ? registry.getTerminalRootKey() : null,
                registry.hasLastSuccessfulSyncTime() ? registry.getLastSuccessfulSyncTime() : null,
                registry.hasDeletionConnectionIdMSB() && registry.hasDeletionConnectionIdLSB()
                        ? new UUID(registry.getDeletionConnectionIdMSB(), registry.getDeletionConnectionIdLSB()) : null,
                registry.hasDeletionExternalId() ? registry.getDeletionExternalId() : null,
                registry.getRetryCount());
    }
}
