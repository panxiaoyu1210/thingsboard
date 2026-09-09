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
package org.thingsboard.server.dao.wan;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WanDeviceRegistryServiceImpl implements WanDeviceRegistryService {

    private final WanDeviceRegistryDao registryDao;

    @Override
    @Transactional
    public WanDeviceRegistry save(WanDeviceRegistry registry) {
        if (registry.getId() == null) {
            registry.setId(UUID.randomUUID());
            registry.setCreatedTime(System.currentTimeMillis());
        }
        return registryDao.save(registry);
    }

    @Override
    public WanDeviceRegistry findByDeviceId(TenantId tenantId, DeviceId deviceId) {
        return registryDao.findByDeviceId(tenantId, deviceId);
    }

    @Override
    public WanDeviceRegistry findByDeviceIdForUpdate(TenantId tenantId, DeviceId deviceId) {
        return registryDao.findByDeviceIdForUpdate(tenantId, deviceId);
    }

    @Override
    public WanDeviceRegistry findByDeviceIdForUpdate(DeviceId deviceId) {
        return registryDao.findByDeviceIdForUpdate(deviceId);
    }

    @Override
    public WanDeviceRegistry findClaimableByDeviceIdForUpdate(DeviceId deviceId, String ownerId, long now) {
        return registryDao.findClaimableByDeviceIdForUpdate(deviceId, ownerId, now);
    }

    @Override
    public WanDeviceRegistry findByDeviceId(DeviceId deviceId) {
        return registryDao.findByDeviceId(deviceId);
    }

    @Override
    public WanDeviceRegistry findGatewayByExternalId(TenantId tenantId, UUID connectionId, String externalId) {
        return registryDao.findGatewayByExternalId(tenantId, connectionId, externalId);
    }

    @Override
    @Transactional
    public void deleteByDeviceId(DeviceId deviceId) {
        registryDao.deleteByDeviceId(deviceId);
    }

    @Override
    public List<WanDeviceRegistry> findClaimableForUpdate(String ownerId, long now, int batchSize) {
        return registryDao.findClaimableForUpdate(ownerId, now, batchSize);
    }

    @Override
    public PageData<WanDeviceRegistry> findBySyncStatus(WanDeviceSyncStatus syncStatus, PageLink pageLink) {
        return registryDao.findBySyncStatus(syncStatus, pageLink);
    }

    @Override
    public PageData<WanDeviceRegistry> findAll(PageLink pageLink) {
        return registryDao.findAll(pageLink);
    }
}
