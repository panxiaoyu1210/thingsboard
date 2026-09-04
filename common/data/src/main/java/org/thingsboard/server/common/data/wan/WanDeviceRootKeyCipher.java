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

import org.thingsboard.server.common.data.transport.wan.WanValidation;

public final class WanDeviceRootKeyCipher {

    private static final String KEY_CONTEXT = "wan-device-root-key";
    private final WanSecretCipher cipher;

    public WanDeviceRootKeyCipher(String encryptionKey) {
        cipher = new WanSecretCipher(encryptionKey, KEY_CONTEXT, "WAN terminal root key",
                "WAN_CONNECTION_PASSWORD_ENCRYPTION_KEY");
    }

    public String encrypt(String rootKey) {
        return rootKey == null || rootKey.isBlank() ? null : cipher.encrypt(rootKey);
    }

    public String decrypt(String protectedRootKey) {
        if (protectedRootKey == null || protectedRootKey.isBlank()) {
            return null;
        }
        if (WanValidation.isHex(protectedRootKey, 32)) {
            return protectedRootKey.toUpperCase();
        }
        return cipher.decrypt(protectedRootKey);
    }

    public boolean isProtected(String rootKey) {
        return rootKey != null && rootKey.startsWith(WanSecretCipher.PREFIX);
    }
}
