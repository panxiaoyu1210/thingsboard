/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.sql.wan;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.wan.WanConnection;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.model.sql.WanConnectionEntity;
import org.thingsboard.server.dao.util.SqlDao;
import org.thingsboard.server.dao.wan.WanConnectionDao;
import org.thingsboard.server.exception.DataValidationException;

import java.util.UUID;

@Component
@SqlDao
@RequiredArgsConstructor
public class JpaWanConnectionDao implements WanConnectionDao {

    private final WanConnectionRepository repository;

    @Override
    public WanConnection save(TenantId tenantId, WanConnection connection) {
        try {
            return repository.saveAndFlush(new WanConnectionEntity(connection)).toData();
        } catch (DataIntegrityViolationException e) {
            throw new DataValidationException("WAN connection with such name already exists!");
        }
    }

    @Override
    public WanConnection findById(TenantId tenantId, UUID connectionId) {
        return repository.findByIdAndTenantId(connectionId, tenantId.getId())
                .map(WanConnectionEntity::toData)
                .orElse(null);
    }

    @Override
    public PageData<WanConnection> findByTenantId(TenantId tenantId, PageLink pageLink) {
        String textSearch = pageLink.getTextSearch() == null ? "" : pageLink.getTextSearch();
        return DaoUtil.toPageData(repository.findByTenantIdAndNameContainingIgnoreCase(
                tenantId.getId(), textSearch, DaoUtil.toPageable(pageLink)));
    }

    @Override
    public boolean existsByName(TenantId tenantId, String name, UUID excludedId) {
        return excludedId == null
                ? repository.existsByTenantIdAndNameIgnoreCase(tenantId.getId(), name)
                : repository.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenantId.getId(), name, excludedId);
    }

    @Override
    public boolean isReferencedByDeviceProfile(TenantId tenantId, UUID connectionId) {
        return repository.isReferencedByDeviceProfile(tenantId.getId(), connectionId);
    }

    @Override
    public boolean removeById(TenantId tenantId, UUID connectionId) {
        return repository.deleteByIdAndTenantId(connectionId, tenantId.getId()) > 0;
    }

}
