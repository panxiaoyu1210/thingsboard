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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.thingsboard.server.dao.model.sql.WanConnectionEntity;

import java.util.Optional;
import java.util.UUID;

public interface WanConnectionRepository extends JpaRepository<WanConnectionEntity, UUID> {

    Optional<WanConnectionEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<WanConnectionEntity> findByTenantIdAndNameContainingIgnoreCase(UUID tenantId, String textSearch, Pageable pageable);

    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

    boolean existsByTenantIdAndNameIgnoreCaseAndIdNot(UUID tenantId, String name, UUID id);

    @Query(value = """
            SELECT EXISTS(
                SELECT 1
                FROM device_profile
                WHERE tenant_id = :tenantId
                  AND transport_type = 'WAN'
                  AND profile_data -> 'transportConfiguration' ->> 'connectionId' = CAST(:connectionId AS text)
            )
            """, nativeQuery = true)
    boolean isReferencedByDeviceProfile(@Param("tenantId") UUID tenantId,
                                        @Param("connectionId") UUID connectionId);

    @Transactional
    @Modifying
    long deleteByIdAndTenantId(UUID id, UUID tenantId);

}
