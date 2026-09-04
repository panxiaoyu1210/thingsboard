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
package org.thingsboard.server.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.wan.WanDeviceRegistryService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.wan.WanDeviceRegistryManager;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH;

@RestController
@TbCoreComponent
@RequiredArgsConstructor
@RequestMapping("/api")
public class WanDeviceSyncController extends BaseController {

    private final WanDeviceRegistryService registryService;
    private final WanDeviceRegistryManager registryManager;
    private final TbClusterService clusterService;

    @ApiOperation(value = "Get WAN device synchronization state (getWanDeviceSync)",
            notes = "Returns the persisted WAN synchronization state without triggering synchronization. "
                    + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping("/wan/device/{deviceId}/sync")
    public WanDeviceRegistry getWanDeviceSync(@PathVariable String deviceId) throws ThingsboardException {
        DeviceId id = new DeviceId(toUUID(deviceId));
        var device = checkDeviceId(id, Operation.READ);
        return checkNotNull(registryService.findByDeviceId(device.getTenantId(), id),
                "WAN synchronization state for device [" + deviceId + "] is not found");
    }

    @ApiOperation(value = "Synchronize WAN device from NS (synchronizeWanDeviceFromNs)",
            notes = "Queues an idempotent WAN reconciliation where NS is authoritative. "
                    + "The regular Device GET endpoint remains read-only.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping("/wan/device/{deviceId}/sync")
    public WanDeviceRegistry synchronizeWanDeviceFromNs(@PathVariable String deviceId) throws ThingsboardException {
        return requestSync(deviceId, false);
    }

    @ApiOperation(value = "Retry failed WAN device synchronization (retryWanDeviceSync)",
            notes = "Moves a failed WAN synchronization back to the pending queue.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping("/wan/device/{deviceId}/sync/retry")
    public WanDeviceRegistry retryWanDeviceSync(@PathVariable String deviceId) throws ThingsboardException {
        return requestSync(deviceId, true);
    }

    @ApiOperation(value = "Recreate WAN device from platform configuration (recreateWanDevice)",
            notes = "Queues a destructive WAN operation that deletes the NS device, adds the current platform "
                    + "configuration, and verifies it with a query.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping("/wan/device/{deviceId}/sync/recreate")
    public WanDeviceRegistry recreateWanDevice(@PathVariable String deviceId) throws ThingsboardException {
        DeviceId id = new DeviceId(toUUID(deviceId));
        var device = checkDeviceId(id, Operation.WRITE);
        WanDeviceRegistry registry = checkNotNull(
                registryManager.requestRecreate(device.getTenantId(), id),
                "WAN synchronization state for device [" + deviceId + "] is not found");
        clusterService.onWanDeviceSyncRequested(device);
        return registry;
    }

    private WanDeviceRegistry requestSync(String deviceId, boolean retryOnly) throws ThingsboardException {
        DeviceId id = new DeviceId(toUUID(deviceId));
        var device = checkDeviceId(id, Operation.WRITE);
        WanDeviceRegistry registry = checkNotNull(
                registryManager.requestSync(device.getTenantId(), id, retryOnly),
                "WAN synchronization state for device [" + deviceId + "] is not found");
        clusterService.onWanDeviceSyncRequested(device);
        return registry;
    }
}
