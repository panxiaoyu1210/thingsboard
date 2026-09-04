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
package org.thingsboard.server.common.data.transport.wan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.thingsboard.server.common.data.id.DeviceId;

import java.io.Serializable;

@Data
@Schema
public class WanTerminalConfiguration implements Serializable {

    private static final long serialVersionUID = -2118174706677030938L;

    private String devEui;
    private Integer devType;
    private Integer securityMode;
    private DeviceId relatedGatewayId;

    @JsonIgnore
    public boolean isValid() {
        return WanValidation.isHex(devEui, 16)
                && WanValidation.isInRange(devType, 0, 1)
                && WanValidation.isInRange(securityMode, 0, 5);
    }

}
