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

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WanSessionInfoFactory {

    private final TbServiceInfoProvider serviceInfoProvider;

    public TransportProtos.SessionInfoProto create(WanDeviceDescriptor device, UUID sessionId) {
        if (device.deviceId() == null || device.tenantId() == null || device.customerId() == null
                || device.deviceProfileId() == null || device.deviceName() == null
                || device.deviceType() == null) {
            throw new IllegalArgumentException("WAN device session information is incomplete");
        }
        return TransportProtos.SessionInfoProto.newBuilder()
                .setNodeId(serviceInfoProvider.getServiceId())
                .setSessionIdMSB(sessionId.getMostSignificantBits())
                .setSessionIdLSB(sessionId.getLeastSignificantBits())
                .setTenantIdMSB(device.tenantId().getMostSignificantBits())
                .setTenantIdLSB(device.tenantId().getLeastSignificantBits())
                .setDeviceIdMSB(device.deviceId().getMostSignificantBits())
                .setDeviceIdLSB(device.deviceId().getLeastSignificantBits())
                .setDeviceName(device.deviceName())
                .setDeviceType(device.deviceType())
                .setDeviceProfileIdMSB(device.deviceProfileId().getMostSignificantBits())
                .setDeviceProfileIdLSB(device.deviceProfileId().getLeastSignificantBits())
                .setCustomerIdMSB(device.customerId().getMostSignificantBits())
                .setCustomerIdLSB(device.customerId().getLeastSignificantBits())
                .setIsGateway(device.gateway())
                .build();
    }

}
