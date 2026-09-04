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

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.eventsourcing.SaveEntityEvent;

import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WanDeviceSyncListenerTest {

    @Test
    void handlesOnlyCreatedDevices() {
        WanDeviceRegistryManager manager = Mockito.mock(WanDeviceRegistryManager.class);
        WanDeviceRegistryListener listener = new WanDeviceRegistryListener(manager);
        TenantId tenantId = TenantId.fromUUID(UUID.randomUUID());
        Device device = new Device(new DeviceId(UUID.randomUUID()));
        device.setTenantId(tenantId);

        listener.onDeviceCreated(SaveEntityEvent.builder()
                .tenantId(tenantId).entityId(device.getId()).entity(device).created(true).build());
        verify(manager).registerCreatedDevice(device);

        listener.onDeviceCreated(SaveEntityEvent.builder()
                .tenantId(tenantId).entityId(device.getId()).entity(device).created(false).build());
        verify(manager, Mockito.times(1)).registerCreatedDevice(device);

        listener.onDeviceCreated(SaveEntityEvent.builder()
                .tenantId(tenantId).entityId(device.getId()).entity("not-device").created(true).build());
        verify(manager, never()).registerCreatedDevice(null);
    }
}
