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

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.RestClientException;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.map.TiandituSearchService;
import org.thingsboard.server.service.map.TiandituSearchService.MapBounds;
import org.thingsboard.server.service.map.TiandituSearchService.TiandituPlace;

import java.util.List;

@RestController
@TbCoreComponent
@RequestMapping("/api/map/tianditu")
public class TiandituMapController {

    private final TiandituSearchService searchService;

    public TiandituMapController(TiandituSearchService searchService) {
        this.searchService = searchService;
    }

    @ApiOperation(value = "Search Tianditu places (searchTiandituPlaces)",
            notes = "Searches places within the supplied map viewport and returns a normalized bounded result list.")
    @PreAuthorize("hasAnyAuthority('SYS_ADMIN', 'TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping("/search")
    public List<TiandituPlace> search(
            @RequestParam String query,
            @RequestParam double west,
            @RequestParam double south,
            @RequestParam double east,
            @RequestParam double north,
            @RequestParam int zoom) throws ThingsboardException {
        try {
            return searchService.search(query, new MapBounds(west, south, east, north), zoom);
        } catch (IllegalArgumentException e) {
            throw new ThingsboardException(e.getMessage(), ThingsboardErrorCode.BAD_REQUEST_PARAMS);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Tianditu search is unavailable");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Tianditu search request failed");
        }
    }

}
