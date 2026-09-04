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
package org.thingsboard.server.common.data.wan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;
import org.thingsboard.server.common.data.HasTenantId;
import org.thingsboard.server.common.data.HasVersion;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

@Data
@Schema
@ToString(exclude = "configuration")
public class WanDeviceRegistry implements HasTenantId, HasVersion, Serializable {

    @Serial
    private static final long serialVersionUID = 3946845586818435347L;

    private UUID id;
    private long createdTime;
    private TenantId tenantId;
    private DeviceId deviceId;
    private UUID connectionId;
    private WanDeviceType deviceType;
    private String externalId;
    private String relatedExternalId;
    private String deviceName;
    @JsonIgnore
    private String configuration;
    private WanDeviceSyncStatus syncStatus;
    private Long lastSyncTime;
    private Long nextSyncTime;
    private String error;
    private Long version;
}
