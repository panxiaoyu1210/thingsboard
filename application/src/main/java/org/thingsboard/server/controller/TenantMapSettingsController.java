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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.queue.util.TbCoreComponent;

import java.util.ArrayList;
import java.util.List;

@RestController
@TbCoreComponent
@RequestMapping("/api")
public class TenantMapSettingsController extends BaseController {

    static final String CENTER_LATITUDE_ATTRIBUTE = "mapDefaultCenterLatitude";
    static final String CENTER_LONGITUDE_ATTRIBUTE = "mapDefaultCenterLongitude";
    static final String DEFAULT_ZOOM_ATTRIBUTE = "mapDefaultZoom";
    static final String LOCATION_NAME_ATTRIBUTE = "mapDefaultLocationName";

    private static final List<String> ATTRIBUTE_KEYS = List.of(
            CENTER_LATITUDE_ATTRIBUTE,
            CENTER_LONGITUDE_ATTRIBUTE,
            DEFAULT_ZOOM_ATTRIBUTE,
            LOCATION_NAME_ATTRIBUTE
    );

    @ApiOperation(value = "Get current tenant map settings (getTenantMapSettings)",
            notes = "Returns the optional default map viewport configured for the current tenant.")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping("/tenant/mapSettings")
    public DeferredResult<TenantMapSettings> getTenantMapSettings() throws ThingsboardException {
        TenantId tenantId = getTenantId();
        return wrapFuture(Futures.transform(
                attributesService.find(tenantId, tenantId, AttributeScope.SERVER_SCOPE, ATTRIBUTE_KEYS),
                this::toMapSettings,
                MoreExecutors.directExecutor()
        ));
    }

    @ApiOperation(value = "Save current tenant map settings (saveTenantMapSettings)",
            notes = "Saves or clears the default map viewport for the current tenant. " +
                    "This endpoint intentionally does not grant generic tenant attribute write access.")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PutMapping("/tenant/mapSettings")
    public DeferredResult<TenantMapSettings> saveTenantMapSettings(@RequestBody TenantMapSettings settings)
            throws ThingsboardException {
        TenantMapSettings normalized = validateAndNormalize(settings);
        TenantId tenantId = getTenantId();
        ListenableFuture<?> updateFuture;
        if (normalized.isEmpty()) {
            updateFuture = attributesService.removeAll(
                    tenantId, tenantId, AttributeScope.SERVER_SCOPE, ATTRIBUTE_KEYS);
        } else {
            long timestamp = System.currentTimeMillis();
            List<AttributeKvEntry> attributes = new ArrayList<>(4);
            attributes.add(new BaseAttributeKvEntry(
                    new DoubleDataEntry(CENTER_LATITUDE_ATTRIBUTE, normalized.centerLatitude()), timestamp));
            attributes.add(new BaseAttributeKvEntry(
                    new DoubleDataEntry(CENTER_LONGITUDE_ATTRIBUTE, normalized.centerLongitude()), timestamp));
            attributes.add(new BaseAttributeKvEntry(
                    new LongDataEntry(DEFAULT_ZOOM_ATTRIBUTE, normalized.defaultZoom().longValue()), timestamp));
            if (normalized.locationName() != null) {
                attributes.add(new BaseAttributeKvEntry(
                        new StringDataEntry(LOCATION_NAME_ATTRIBUTE, normalized.locationName()), timestamp));
            }
            ListenableFuture<?> saveFuture = attributesService.save(
                    tenantId, tenantId, AttributeScope.SERVER_SCOPE, attributes);
            if (normalized.locationName() == null) {
                ListenableFuture<?> removeNameFuture = attributesService.removeAll(
                        tenantId, tenantId, AttributeScope.SERVER_SCOPE, List.of(LOCATION_NAME_ATTRIBUTE));
                updateFuture = Futures.whenAllSucceed(saveFuture, removeNameFuture)
                        .call(() -> null, MoreExecutors.directExecutor());
            } else {
                updateFuture = saveFuture;
            }
        }
        return wrapFuture(Futures.transform(updateFuture, ignored -> normalized, MoreExecutors.directExecutor()));
    }

    private TenantMapSettings validateAndNormalize(TenantMapSettings settings) throws ThingsboardException {
        if (settings == null) {
            throw badRequest("Tenant map settings cannot be null");
        }
        String locationName = settings.locationName() == null ? null : settings.locationName().trim();
        if (locationName != null && locationName.isEmpty()) {
            locationName = null;
        }
        if (locationName != null && locationName.length() > 255) {
            throw badRequest("Location name cannot exceed 255 characters");
        }

        boolean hasLatitude = settings.centerLatitude() != null;
        boolean hasLongitude = settings.centerLongitude() != null;
        boolean hasZoom = settings.defaultZoom() != null;
        if (!hasLatitude && !hasLongitude && !hasZoom) {
            if (locationName != null) {
                throw badRequest("Location name requires a complete map viewport");
            }
            return TenantMapSettings.empty();
        }
        if (!hasLatitude || !hasLongitude || !hasZoom) {
            throw badRequest("Center latitude, center longitude and default zoom must be provided together");
        }
        if (!Double.isFinite(settings.centerLatitude()) || settings.centerLatitude() < -90 || settings.centerLatitude() > 90) {
            throw badRequest("Center latitude must be between -90 and 90");
        }
        if (!Double.isFinite(settings.centerLongitude()) || settings.centerLongitude() < -180 || settings.centerLongitude() > 180) {
            throw badRequest("Center longitude must be between -180 and 180");
        }
        if (settings.defaultZoom() < 1 || settings.defaultZoom() > 18) {
            throw badRequest("Default zoom must be between 1 and 18");
        }
        return new TenantMapSettings(
                settings.centerLatitude(), settings.centerLongitude(), settings.defaultZoom(), locationName);
    }

    private TenantMapSettings toMapSettings(List<AttributeKvEntry> attributes) {
        Double latitude = null;
        Double longitude = null;
        Integer zoom = null;
        String locationName = null;
        for (AttributeKvEntry attribute : attributes) {
            switch (attribute.getKey()) {
                case CENTER_LATITUDE_ATTRIBUTE -> latitude = attribute.getDoubleValue().orElse(null);
                case CENTER_LONGITUDE_ATTRIBUTE -> longitude = attribute.getDoubleValue().orElse(null);
                case DEFAULT_ZOOM_ATTRIBUTE -> zoom = attribute.getLongValue().map(Long::intValue).orElse(null);
                case LOCATION_NAME_ATTRIBUTE -> locationName = attribute.getStrValue().orElse(null);
                default -> {
                }
            }
        }
        if (latitude == null || longitude == null || zoom == null ||
                !Double.isFinite(latitude) || latitude < -90 || latitude > 90 ||
                !Double.isFinite(longitude) || longitude < -180 || longitude > 180 ||
                zoom < 1 || zoom > 18) {
            return TenantMapSettings.empty();
        }
        return new TenantMapSettings(latitude, longitude, zoom, locationName);
    }

    private ThingsboardException badRequest(String message) {
        return new ThingsboardException(message, ThingsboardErrorCode.BAD_REQUEST_PARAMS);
    }

    public record TenantMapSettings(Double centerLatitude, Double centerLongitude, Integer defaultZoom,
                                    String locationName) {

        public static TenantMapSettings empty() {
            return new TenantMapSettings(null, null, null, null);
        }

        public boolean isEmpty() {
            return centerLatitude == null && centerLongitude == null && defaultZoom == null;
        }
    }

}
