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

import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.wan.WanConnection;

import java.util.List;
import java.util.UUID;

public interface WanConnectionDao {

    WanConnection save(TenantId tenantId, WanConnection connection);

    WanConnection findById(TenantId tenantId, UUID connectionId);

    PageData<WanConnection> findByTenantId(TenantId tenantId, PageLink pageLink);

    PageData<WanConnection> findEnabled(PageLink pageLink);

    List<WanConnection> claimEnabled(String ownerId, long now, long leaseUntil);

    void releaseOwned(String ownerId);

    boolean existsByName(TenantId tenantId, String name, UUID excludedId);

    boolean isReferencedByDeviceProfile(TenantId tenantId, UUID connectionId);

    boolean removeById(TenantId tenantId, UUID connectionId);

}
