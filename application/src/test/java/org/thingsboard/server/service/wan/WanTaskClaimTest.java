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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.wan.WanConnection;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.dao.device.DeviceCredentialsService;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.wan.WanConnectionService;
import org.thingsboard.server.dao.wan.WanDeviceRegistryService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanTaskClaimTest {

    private static final long NOW = 1_000_000L;
    private static final long LEASE_UNTIL = NOW + 60_000L;

    private WanDeviceRegistryService registryService;
    private WanConnectionService connectionService;
    private WanDeviceRegistryManager manager;
    private TenantId tenantId;
    private UUID connectionId;

    @BeforeEach
    void setUp() {
        registryService = Mockito.mock(WanDeviceRegistryService.class);
        connectionService = Mockito.mock(WanConnectionService.class);
        manager = new WanDeviceRegistryManager(
                Mockito.mock(DeviceProfileService.class), Mockito.mock(DeviceService.class),
                Mockito.mock(DeviceCredentialsService.class), registryService, connectionService);
        tenantId = TenantId.fromUUID(UUID.randomUUID());
        connectionId = UUID.randomUUID();
        when(registryService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(connectionService.findWanConnectionById(tenantId, connectionId)).thenReturn(connection(true));
    }

    @Test
    void claimsDueAndReliableTasksWhileExcludingFutureAndFailedDevices() {
        WanDeviceRegistry due = registry(WanDeviceSyncStatus.ACTIVE, NOW);
        WanDeviceRegistry pending = registry(WanDeviceSyncStatus.PENDING, null);
        WanDeviceRegistry deleting = registry(WanDeviceSyncStatus.DELETING, null);
        WanDeviceRegistry interrupted = registry(WanDeviceSyncStatus.SYNCING, null);
        interrupted.setLockOwnerId("dead-owner");
        interrupted.setLockUntil(NOW);
        WanDeviceRegistry unscheduled = registry(WanDeviceSyncStatus.UNKNOWN, null);
        WanDeviceRegistry future = registry(WanDeviceSyncStatus.UNKNOWN, NOW + 1);
        WanDeviceRegistry failed = registry(WanDeviceSyncStatus.FAILED, null);
        when(registryService.findClaimableForUpdate("owner-a", NOW, 10))
                .thenReturn(List.of(due, pending, deleting, interrupted, unscheduled, future, failed));

        List<WanDeviceRegistry> claimed = manager.claimAvailableTasks(
                "owner-a", NOW, LEASE_UNTIL, 10);

        assertThat(claimed).containsExactly(due, pending, deleting, interrupted, unscheduled);
        assertThat(due.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
        assertThat(interrupted.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
        assertThat(unscheduled.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
        assertThat(deleting.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.DELETING);
        assertThat(claimed).allSatisfy(registry -> {
            assertThat(registry.getLockOwnerId()).isEqualTo("owner-a");
            assertThat(registry.getLockUntil()).isEqualTo(LEASE_UNTIL);
        });
        assertThat(future.getLockOwnerId()).isNull();
        assertThat(failed.getLockOwnerId()).isNull();
    }

    @Test
    void preservesDueTimeWithoutClaimingWhenAutomaticSyncIsDisabled() {
        WanDeviceRegistry due = registry(WanDeviceSyncStatus.ACTIVE, NOW);
        when(connectionService.findWanConnectionById(tenantId, connectionId)).thenReturn(connection(false));
        when(registryService.findClaimableForUpdate("owner-a", NOW, 10)).thenReturn(List.of(due));

        assertThat(manager.claimAvailableTasks("owner-a", NOW, LEASE_UNTIL, 10)).isEmpty();

        assertThat(due.getNextSyncTime()).isEqualTo(NOW);
        assertThat(due.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.ACTIVE);
        verify(registryService, never()).save(due);
    }

    @Test
    void recoversTaskOnlyAfterThePreviousLeaseExpires() {
        WanDeviceRegistry pending = registry(WanDeviceSyncStatus.PENDING, null);
        pending.setLockOwnerId("owner-a");
        pending.setLockUntil(NOW + 1);
        when(registryService.findClaimableByDeviceIdForUpdate(pending.getDeviceId(), "owner-b", NOW))
                .thenReturn(pending);

        assertThat(manager.claimTask(pending.getDeviceId(), "owner-b", NOW, LEASE_UNTIL)).isNull();

        pending.setLockUntil(NOW);
        WanDeviceRegistry recovered = manager.claimTask(
                pending.getDeviceId(), "owner-b", NOW, LEASE_UNTIL);
        assertThat(recovered.getLockOwnerId()).isEqualTo("owner-b");
        assertThat(recovered.getLockUntil()).isEqualTo(LEASE_UNTIL);
    }

    @Test
    void schedulesNextRunFromControlledCompletionTimeAndClearsTheLease() {
        WanDeviceRegistry syncing = registry(WanDeviceSyncStatus.SYNCING, null);
        syncing.setLockOwnerId("owner-a");
        syncing.setLockUntil(LEASE_UNTIL);
        when(registryService.findByDeviceIdForUpdate(syncing.getDeviceId())).thenReturn(syncing);

        WanDeviceRegistry active = manager.update(syncing.getDeviceId(), WanDeviceSyncStatus.ACTIVE,
                null, null, null, null, null, false, false, "owner-a", NOW);

        long interval = 24L * 3_600_000L;
        long jitterLimit = 3_600_000L;
        UUID id = syncing.getDeviceId().getId();
        long expectedJitter = Math.floorMod(
                id.getMostSignificantBits() ^ id.getLeastSignificantBits(), jitterLimit + 1L);
        assertThat(active.getLastSyncTime()).isEqualTo(NOW);
        assertThat(active.getLastSuccessfulSyncTime()).isEqualTo(NOW);
        assertThat(active.getNextSyncTime()).isEqualTo(NOW + interval + expectedJitter);
        assertThat(active.getLockOwnerId()).isNull();
        assertThat(active.getLockUntil()).isNull();
    }

    @Test
    void rejectsAStateUpdateFromANonOwningTransport() {
        WanDeviceRegistry syncing = registry(WanDeviceSyncStatus.SYNCING, null);
        syncing.setLockOwnerId("owner-a");
        syncing.setLockUntil(LEASE_UNTIL);
        when(registryService.findByDeviceIdForUpdate(syncing.getDeviceId())).thenReturn(syncing);

        assertThatThrownBy(() -> manager.update(syncing.getDeviceId(), WanDeviceSyncStatus.ACTIVE,
                null, null, null, null, null, false, false, "owner-b", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not owned");

        verify(registryService, never()).save(syncing);
    }

    private WanConnection connection(boolean syncEnabled) {
        WanConnection connection = new WanConnection();
        connection.setId(connectionId);
        connection.setTenantId(tenantId);
        connection.setEnabled(true);
        connection.setSyncEnabled(syncEnabled);
        connection.setSyncIntervalHours(24);
        return connection;
    }

    private WanDeviceRegistry registry(WanDeviceSyncStatus status, Long nextSyncTime) {
        WanDeviceRegistry registry = new WanDeviceRegistry();
        registry.setId(UUID.randomUUID());
        registry.setTenantId(tenantId);
        registry.setDeviceId(new DeviceId(UUID.randomUUID()));
        registry.setConnectionId(connectionId);
        registry.setDeviceType(WanDeviceType.GATEWAY);
        registry.setExternalId("8C3F74C81C703000");
        registry.setDeviceName("Gateway");
        registry.setConfiguration("{}");
        registry.setSyncStatus(status);
        registry.setNextSyncTime(nextSyncTime);
        registry.setVersion(1L);
        return registry;
    }

}
