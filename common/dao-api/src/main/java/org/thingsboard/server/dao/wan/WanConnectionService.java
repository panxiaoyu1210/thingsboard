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

import java.util.UUID;

public interface WanConnectionService {

    WanConnection saveWanConnection(TenantId tenantId, WanConnection connection);

    WanConnection findWanConnectionById(TenantId tenantId, UUID connectionId);

    WanConnection findWanConnectionWithCredentials(TenantId tenantId, UUID connectionId);

    PageData<WanConnection> findWanConnections(TenantId tenantId, PageLink pageLink);

    PageData<WanConnection> findEnabledWanConnections(PageLink pageLink);

    WanConnection prepareConnectionTest(TenantId tenantId, WanConnection connection);

    void deleteWanConnection(TenantId tenantId, UUID connectionId);

}
