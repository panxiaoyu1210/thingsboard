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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WanDeviceRootKeyCipherTest {

    private static final String ROOT_KEY = "0102030405060708090A0B0C0D0E0F10";

    @Test
    void encryptsRootKeyWithDedicatedKeyContext() {
        WanDeviceRootKeyCipher cipher = new WanDeviceRootKeyCipher("test-encryption-key");

        String protectedValue = cipher.encrypt(ROOT_KEY);

        assertThat(protectedValue).startsWith("v1:").doesNotContain(ROOT_KEY);
        assertThat(cipher.isProtected(protectedValue)).isTrue();
        assertThat(cipher.decrypt(protectedValue)).isEqualTo(ROOT_KEY);
        assertThatThrownBy(() -> new WanConnectionPasswordCipher("test-encryption-key")
                .decrypt(protectedValue)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsLegacyPlaintextForMigrationCompatibility() {
        WanDeviceRootKeyCipher cipher = new WanDeviceRootKeyCipher("test-encryption-key");

        assertThat(cipher.decrypt(ROOT_KEY.toLowerCase())).isEqualTo(ROOT_KEY);
    }
}
