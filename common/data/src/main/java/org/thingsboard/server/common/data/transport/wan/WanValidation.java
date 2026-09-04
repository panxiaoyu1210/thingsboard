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

import java.util.regex.Pattern;

public final class WanValidation {

    private static final Pattern HEX_PATTERN = Pattern.compile("^[0-9A-Fa-f]+$");

    private WanValidation() {
    }

    public static boolean isHex(String value, int length) {
        return value != null && value.length() == length && HEX_PATTERN.matcher(value).matches();
    }

    public static boolean isInRange(Integer value, int min, int max) {
        return value != null && value >= min && value <= max;
    }

}
