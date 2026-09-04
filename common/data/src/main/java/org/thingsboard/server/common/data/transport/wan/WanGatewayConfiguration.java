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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Data
@Schema
public class WanGatewayConfiguration implements Serializable {

    private static final long serialVersionUID = 8164467060445872391L;

    private String gwId;
    private Integer freqMajor;
    private Integer freqMinor;
    private Integer nwkNum;
    private Integer tddNum;
    private Integer rateNum;
    private List<WanRateConfiguration> rateCfgs = new ArrayList<>();

    @JsonIgnore
    public boolean isValid() {
        return WanValidation.isHex(gwId, 16)
                && WanValidation.isInRange(freqMajor, 1, 10)
                && WanValidation.isInRange(freqMinor, 1, 8)
                && WanValidation.isInRange(nwkNum, 1, 32)
                && WanValidation.isInRange(tddNum, 1, 255)
                && WanValidation.isInRange(rateNum, 1, 4)
                && rateCfgs != null
                && rateNum == rateCfgs.size()
                && rateCfgs.stream().allMatch(config -> config != null && config.isValid())
                && rateCfgs.stream().map(WanRateConfiguration::getRateMode).allMatch(new HashSet<>()::add);
    }

}
