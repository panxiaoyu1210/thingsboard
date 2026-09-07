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
package org.thingsboard.server.dao.sql.wan;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.dao.model.sql.WanDeviceRegistryEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WanDeviceRegistryRepository extends JpaRepository<WanDeviceRegistryEntity, UUID> {

    Optional<WanDeviceRegistryEntity> findByTenantIdAndDeviceId(UUID tenantId, UUID deviceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT registry FROM WanDeviceRegistryEntity registry "
            + "WHERE registry.tenantId = :tenantId AND registry.deviceId = :deviceId")
    Optional<WanDeviceRegistryEntity> findByTenantIdAndDeviceIdForUpdate(
            @Param("tenantId") UUID tenantId, @Param("deviceId") UUID deviceId);

    Optional<WanDeviceRegistryEntity> findByDeviceId(UUID deviceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT registry FROM WanDeviceRegistryEntity registry WHERE registry.deviceId = :deviceId")
    Optional<WanDeviceRegistryEntity> findByDeviceIdForUpdate(@Param("deviceId") UUID deviceId);

    @Query(value = """
            SELECT registry.*
            FROM wan_device_registry registry
            JOIN wan_connection connection ON connection.id = registry.connection_id
            WHERE registry.device_id = :deviceId
              AND connection.enabled = TRUE
              AND connection.ownership_owner_id = :ownerId
              AND connection.ownership_until > :now
            FOR UPDATE OF registry
            """, nativeQuery = true)
    Optional<WanDeviceRegistryEntity> findClaimableByDeviceIdForUpdate(@Param("deviceId") UUID deviceId,
                                                                       @Param("ownerId") String ownerId,
                                                                       @Param("now") long now);

    void deleteByDeviceId(UUID deviceId);

    @Query(value = """
            SELECT registry.*
            FROM wan_device_registry registry
            JOIN wan_connection connection ON connection.id = registry.connection_id
            WHERE connection.enabled = TRUE
              AND connection.ownership_owner_id = :ownerId
              AND connection.ownership_until > :now
              AND (registry.lock_owner_id IS NULL OR registry.lock_until IS NULL OR registry.lock_until <= :now)
              AND (
                    registry.sync_status IN ('PENDING', 'RECREATING', 'DELETING', 'SYNCING', 'CREATING')
                    OR (connection.sync_enabled = TRUE
                        AND registry.sync_status IN ('ACTIVE', 'UNKNOWN')
                        AND (registry.next_sync_time IS NULL OR registry.next_sync_time <= :now))
                  )
            ORDER BY CASE registry.sync_status
                       WHEN 'DELETING' THEN 0
                       WHEN 'RECREATING' THEN 1
                       WHEN 'PENDING' THEN 2
                       WHEN 'SYNCING' THEN 3
                       WHEN 'CREATING' THEN 4
                       ELSE 5
                     END,
                     registry.next_sync_time NULLS FIRST,
                     registry.created_time,
                     registry.id
            LIMIT :batchSize
            FOR UPDATE OF registry SKIP LOCKED
            """, nativeQuery = true)
    List<WanDeviceRegistryEntity> findClaimableForUpdate(
            @Param("ownerId") String ownerId,
            @Param("now") long now,
            @Param("batchSize") int batchSize);

    Optional<WanDeviceRegistryEntity> findByTenantIdAndConnectionIdAndDeviceTypeAndExternalIdIgnoreCase(
            UUID tenantId, UUID connectionId, WanDeviceType deviceType, String externalId);

    Page<WanDeviceRegistryEntity> findBySyncStatus(WanDeviceSyncStatus syncStatus, Pageable pageable);
}
