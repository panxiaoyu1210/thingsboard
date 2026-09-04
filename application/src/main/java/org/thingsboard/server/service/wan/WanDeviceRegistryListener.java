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

import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.dao.eventsourcing.DeleteEntityEvent;
import org.thingsboard.server.dao.eventsourcing.SaveEntityEvent;
import org.thingsboard.server.queue.util.TbCoreComponent;

@Component
@TbCoreComponent
@RequiredArgsConstructor
public class WanDeviceRegistryListener {

    private final WanDeviceRegistryManager registryManager;

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onDeviceCreated(SaveEntityEvent<?> event) {
        if (Boolean.TRUE.equals(event.getCreated()) && event.getEntity() instanceof Device device) {
            registryManager.registerCreatedDevice(device);
        }
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onDeviceDeleted(DeleteEntityEvent<?> event) {
        if (event.getEntity() instanceof Device device) {
            registryManager.prepareDeletion(event.getTenantId(), device.getId());
        }
    }
}
