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
package org.thingsboard.server.wan;

import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;

import java.util.UUID;

public record WanDeviceRegistrySnapshot(UUID deviceId, UUID tenantId, UUID connectionId,
                                        WanDeviceType deviceType, String externalId, String deviceName,
                                        String configuration, WanDeviceSyncStatus syncStatus,
                                        Long lastSyncTime, Long nextSyncTime, String error, long version,
                                        String relatedExternalId, String terminalRootKey) {

    public WanDeviceRegistrySnapshot(UUID deviceId, UUID tenantId, UUID connectionId,
                                     WanDeviceType deviceType, String externalId, String deviceName,
                                     String configuration, WanDeviceSyncStatus syncStatus,
                                     Long lastSyncTime, Long nextSyncTime, String error, long version) {
        this(deviceId, tenantId, connectionId, deviceType, externalId, deviceName, configuration,
                syncStatus, lastSyncTime, nextSyncTime, error, version, null, null);
    }

    @Override
    public String toString() {
        return "WanDeviceRegistrySnapshot[deviceId=" + deviceId + ", tenantId=" + tenantId
                + ", connectionId=" + connectionId + ", deviceType=" + deviceType
                + ", externalId=" + externalId + ", deviceName=" + deviceName
                + ", syncStatus=" + syncStatus + ", lastSyncTime=" + lastSyncTime
                + ", nextSyncTime=" + nextSyncTime + ", error=" + error + ", version=" + version
                + ", relatedExternalId=" + relatedExternalId + ", terminalRootKey=REDACTED]";
    }
}
