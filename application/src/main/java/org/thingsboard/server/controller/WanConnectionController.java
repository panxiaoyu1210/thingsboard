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

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.wan.WanConnection;
import org.thingsboard.server.common.data.wan.WanConnectionTestResult;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.dao.wan.WanConnectionService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.wan.WanConnectionTester;

import static org.thingsboard.server.controller.ControllerConstants.PAGE_DATA_PARAMETERS;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_NUMBER_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_SIZE_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.SORT_ORDER_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.SORT_PROPERTY_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;

@RestController
@TbCoreComponent
@RequiredArgsConstructor
@RequestMapping("/api")
public class WanConnectionController extends BaseController {

    private final WanConnectionService wanConnectionService;
    private final WanConnectionTester wanConnectionTester;

    @ApiOperation(value = "Create or update WAN NS connection (saveWanConnection)",
            notes = "Creates or updates a tenant-owned WAN network server connection. Omit password on update to retain it. "
                    + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping("/wan/connection")
    public WanConnection saveWanConnection(@RequestBody WanConnection connection) throws ThingsboardException {
        return wanConnectionService.saveWanConnection(getTenantId(), connection);
    }

    @ApiOperation(value = "Get WAN NS connection (getWanConnection)",
            notes = "Returns a tenant-owned WAN connection with its password masked. " + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping("/wan/connection/{connectionId}")
    public WanConnection getWanConnection(@PathVariable String connectionId) throws ThingsboardException {
        return checkNotNull(wanConnectionService.findWanConnectionById(getTenantId(), toUUID(connectionId)),
                "WAN connection with id [" + connectionId + "] is not found");
    }

    @ApiOperation(value = "Get WAN NS connections (getWanConnections)",
            notes = "Returns a page of tenant-owned WAN connections with passwords masked. "
                    + PAGE_DATA_PARAMETERS + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping("/wan/connections")
    public PageData<WanConnection> getWanConnections(
            @Parameter(description = PAGE_SIZE_DESCRIPTION, required = true)
            @RequestParam int pageSize,
            @Parameter(description = PAGE_NUMBER_DESCRIPTION, required = true)
            @RequestParam int page,
            @RequestParam(required = false) String textSearch,
            @Parameter(description = SORT_PROPERTY_DESCRIPTION,
                    schema = @Schema(allowableValues = {"createdTime", "name", "brokerHost", "enabled"}))
            @RequestParam(required = false) String sortProperty,
            @Parameter(description = SORT_ORDER_DESCRIPTION,
                    schema = @Schema(allowableValues = {"ASC", "DESC"}))
            @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
        return wanConnectionService.findWanConnections(getTenantId(), pageLink);
    }

    @ApiOperation(value = "Test WAN NS connection (testWanConnection)",
            notes = "Attempts an MQTT connection with the submitted settings. A masked or omitted password retains the saved value. "
                    + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping("/wan/connection/test")
    public WanConnectionTestResult testWanConnection(@RequestBody WanConnection connection) throws ThingsboardException {
        WanConnection resolved = wanConnectionService.prepareConnectionTest(getTenantId(), connection);
        return wanConnectionTester.test(resolved);
    }

    @ApiOperation(value = "Delete WAN NS connection (deleteWanConnection)",
            notes = "Deletes a tenant-owned WAN connection unless a WAN device profile still references it. "
                    + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @DeleteMapping("/wan/connection/{connectionId}")
    public void deleteWanConnection(@PathVariable String connectionId) throws ThingsboardException {
        wanConnectionService.deleteWanConnection(getTenantId(), toUUID(connectionId));
    }

}
