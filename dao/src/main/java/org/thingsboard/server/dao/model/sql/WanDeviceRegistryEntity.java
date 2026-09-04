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
package org.thingsboard.server.dao.model.sql;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.dao.model.ToData;

import java.util.UUID;

import static org.thingsboard.server.dao.model.ModelConstants.CREATED_TIME_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.DEVICE_ID_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.ID_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.TENANT_ID_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.VERSION_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_DEVICE_REGISTRY_CONFIGURATION_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_DEVICE_REGISTRY_CONNECTION_ID_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_DEVICE_REGISTRY_DEVICE_NAME_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_DEVICE_REGISTRY_DEVICE_TYPE_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_DEVICE_REGISTRY_ERROR_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_DEVICE_REGISTRY_EXTERNAL_ID_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_DEVICE_REGISTRY_LAST_SYNC_TIME_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_DEVICE_REGISTRY_NEXT_SYNC_TIME_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_DEVICE_REGISTRY_STATUS_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_DEVICE_REGISTRY_TABLE_NAME;

@Data
@NoArgsConstructor
@Entity
@Table(name = WAN_DEVICE_REGISTRY_TABLE_NAME)
public class WanDeviceRegistryEntity implements ToData<WanDeviceRegistry> {

    @Id
    @Column(name = ID_PROPERTY, columnDefinition = "uuid")
    private UUID id;

    @Column(name = CREATED_TIME_PROPERTY, updatable = false, nullable = false)
    private long createdTime;

    @Column(name = TENANT_ID_COLUMN, nullable = false, columnDefinition = "uuid")
    private UUID tenantId;

    @Column(name = DEVICE_ID_PROPERTY, nullable = false, unique = true, columnDefinition = "uuid")
    private UUID deviceId;

    @Column(name = WAN_DEVICE_REGISTRY_CONNECTION_ID_COLUMN, nullable = false, columnDefinition = "uuid")
    private UUID connectionId;

    @Enumerated(EnumType.STRING)
    @Column(name = WAN_DEVICE_REGISTRY_DEVICE_TYPE_COLUMN, nullable = false)
    private WanDeviceType deviceType;

    @Column(name = WAN_DEVICE_REGISTRY_EXTERNAL_ID_COLUMN, nullable = false)
    private String externalId;

    @Column(name = WAN_DEVICE_REGISTRY_DEVICE_NAME_COLUMN, nullable = false)
    private String deviceName;

    @Column(name = WAN_DEVICE_REGISTRY_CONFIGURATION_COLUMN, nullable = false, length = 1_000_000)
    private String configuration;

    @Enumerated(EnumType.STRING)
    @Column(name = WAN_DEVICE_REGISTRY_STATUS_COLUMN, nullable = false)
    private WanDeviceSyncStatus syncStatus;

    @Column(name = WAN_DEVICE_REGISTRY_LAST_SYNC_TIME_COLUMN)
    private Long lastSyncTime;

    @Column(name = WAN_DEVICE_REGISTRY_NEXT_SYNC_TIME_COLUMN)
    private Long nextSyncTime;

    @Column(name = WAN_DEVICE_REGISTRY_ERROR_COLUMN, length = 4096)
    private String error;

    @Version
    @Column(name = VERSION_PROPERTY)
    private Long version;

    public WanDeviceRegistryEntity(WanDeviceRegistry registry) {
        this.id = registry.getId();
        this.createdTime = registry.getCreatedTime();
        this.tenantId = registry.getTenantId().getId();
        this.deviceId = registry.getDeviceId().getId();
        this.connectionId = registry.getConnectionId();
        this.deviceType = registry.getDeviceType();
        this.externalId = registry.getExternalId();
        this.deviceName = registry.getDeviceName();
        this.configuration = registry.getConfiguration();
        this.syncStatus = registry.getSyncStatus();
        this.lastSyncTime = registry.getLastSyncTime();
        this.nextSyncTime = registry.getNextSyncTime();
        this.error = registry.getError();
        this.version = registry.getVersion();
    }

    @Override
    public WanDeviceRegistry toData() {
        WanDeviceRegistry registry = new WanDeviceRegistry();
        registry.setId(id);
        registry.setCreatedTime(createdTime);
        registry.setTenantId(TenantId.fromUUID(tenantId));
        registry.setDeviceId(new DeviceId(deviceId));
        registry.setConnectionId(connectionId);
        registry.setDeviceType(deviceType);
        registry.setExternalId(externalId);
        registry.setDeviceName(deviceName);
        registry.setConfiguration(configuration);
        registry.setSyncStatus(syncStatus);
        registry.setLastSyncTime(lastSyncTime);
        registry.setNextSyncTime(nextSyncTime);
        registry.setError(error);
        registry.setVersion(version);
        return registry;
    }
}
