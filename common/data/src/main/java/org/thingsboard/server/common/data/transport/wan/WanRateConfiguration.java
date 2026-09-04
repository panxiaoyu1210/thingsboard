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

@Data
@Schema
public class WanRateConfiguration implements Serializable {

    private static final long serialVersionUID = -4887525805308908958L;

    private Integer rateMode;
    private Integer uplinkLen;
    private Integer downlinkLen;

    @JsonIgnore
    public boolean isValid() {
        if (rateMode == null || rateMode < 0 || rateMode > 7 || uplinkLen == null || downlinkLen == null) {
            return false;
        }
        int maxLength = rateMode <= 3 ? 246 : 402;
        return uplinkLen >= 1 && uplinkLen <= maxLength && downlinkLen >= 1 && downlinkLen <= maxLength;
    }

}
