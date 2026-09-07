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

import java.util.UUID;

public record WanDeviceDescriptor(UUID deviceId, UUID tenantId, UUID customerId,
                                  UUID deviceProfileId, UUID connectionId,
                                  String deviceName, String deviceType, boolean gateway,
                                  WanDeviceType wanDeviceType, String externalId,
                                  byte[] transportConfiguration) {

    public WanDeviceDescriptor(UUID deviceId, UUID deviceProfileId, UUID connectionId,
                               byte[] transportConfiguration) {
        this(deviceId, null, null, deviceProfileId, connectionId,
                null, null, false, null, null, transportConfiguration);
    }

}
