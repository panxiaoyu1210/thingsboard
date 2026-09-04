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

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.model.sql.WanDeviceRegistryEntity;
import org.thingsboard.server.dao.util.SqlDao;
import org.thingsboard.server.dao.wan.WanDeviceRegistryDao;

import java.util.UUID;

@Component
@SqlDao
@RequiredArgsConstructor
public class JpaWanDeviceRegistryDao implements WanDeviceRegistryDao {

    private final WanDeviceRegistryRepository repository;

    @Override
    public WanDeviceRegistry save(WanDeviceRegistry registry) {
        return repository.saveAndFlush(new WanDeviceRegistryEntity(registry)).toData();
    }

    @Override
    public WanDeviceRegistry findByDeviceId(TenantId tenantId, DeviceId deviceId) {
        return repository.findByTenantIdAndDeviceId(tenantId.getId(), deviceId.getId())
                .map(WanDeviceRegistryEntity::toData)
                .orElse(null);
    }

    @Override
    public WanDeviceRegistry findByDeviceIdForUpdate(TenantId tenantId, DeviceId deviceId) {
        return repository.findByTenantIdAndDeviceIdForUpdate(tenantId.getId(), deviceId.getId())
                .map(WanDeviceRegistryEntity::toData)
                .orElse(null);
    }

    @Override
    public WanDeviceRegistry findByDeviceId(DeviceId deviceId) {
        return repository.findByDeviceId(deviceId.getId())
                .map(WanDeviceRegistryEntity::toData)
                .orElse(null);
    }

    @Override
    public WanDeviceRegistry findGatewayByExternalId(TenantId tenantId, UUID connectionId, String externalId) {
        return repository.findByTenantIdAndConnectionIdAndDeviceTypeAndExternalIdIgnoreCase(
                        tenantId.getId(), connectionId, WanDeviceType.GATEWAY, externalId)
                .map(WanDeviceRegistryEntity::toData)
                .orElse(null);
    }

    @Override
    public void deleteByDeviceId(DeviceId deviceId) {
        repository.deleteByDeviceId(deviceId.getId());
    }

    @Override
    public PageData<WanDeviceRegistry> findBySyncStatus(WanDeviceSyncStatus syncStatus, PageLink pageLink) {
        return DaoUtil.toPageData(repository.findBySyncStatus(syncStatus, DaoUtil.toPageable(pageLink)));
    }
}
