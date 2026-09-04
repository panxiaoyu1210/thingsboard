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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.wan.WanDeviceRegistryService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;

import static org.thingsboard.server.controller.ControllerConstants.TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH;

@RestController
@TbCoreComponent
@RequiredArgsConstructor
@RequestMapping("/api")
public class WanDeviceSyncController extends BaseController {

    private final WanDeviceRegistryService registryService;

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
}
