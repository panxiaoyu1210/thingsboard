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
package org.thingsboard.server.common.data.device.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanTerminalConfiguration;

@Data
@Schema
public class WanDeviceTransportConfiguration implements DeviceTransportConfiguration {

    private static final long serialVersionUID = 1851623347211267580L;

    private WanDeviceType deviceType;
    private WanGatewayConfiguration gateway;
    private WanTerminalConfiguration terminal;

    @Override
    public DeviceTransportType getType() {
        return DeviceTransportType.WAN;
    }

    @Override
    public void validate() {
        boolean valid = switch (deviceType) {
            case GATEWAY -> gateway != null && terminal == null && gateway.isValid();
            case TERMINAL -> terminal != null && gateway == null && terminal.isValid();
            case null -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("WAN transport configuration is not valid");
        }
    }

    public void validateGatewayFlag(boolean gatewayDevice) {
        if (gatewayDevice != (deviceType == WanDeviceType.GATEWAY)) {
            throw new IllegalArgumentException("WAN device type must match the gateway flag");
        }
    }

    @JsonIgnore
    public String getExternalId() {
        return deviceType == WanDeviceType.GATEWAY ? gateway.getGwId() : terminal.getDevEui();
    }

}
