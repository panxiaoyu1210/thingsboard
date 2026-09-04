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

import org.junit.jupiter.api.Test;
import org.thingsboard.server.common.data.wan.WanConnectionPasswordCipher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WanTransportPasswordServiceTest {

    @Test
    void decryptsCoreCiphertextAndRedactsRuntimeConfiguration() {
        String encryptionKey = "tenant-independent-test-key";
        String plaintext = "broker-password-that-must-not-leak";
        String encryptedPassword = new WanConnectionPasswordCipher(encryptionKey).encrypt(plaintext);
        WanTransportPasswordService passwordService = new WanTransportPasswordService(encryptionKey);

        assertThat(passwordService.decrypt(encryptedPassword)).isEqualTo(plaintext);

        WanConnectionConfig configuration = new WanConnectionConfig(
                UUID.randomUUID(), UUID.randomUUID(), "NS", "mqtt.example.org", 8883, true,
                "client-id", "user", plaintext, "ns/publish", "ns/subscribe",
                1, true, 5_000, 24, 1);
        assertThat(configuration.toString()).contains("password=REDACTED").doesNotContain(plaintext);
    }

    @Test
    void rejectsCiphertextEncryptedWithAnotherKey() {
        String encryptedPassword = new WanConnectionPasswordCipher("core-key").encrypt("broker-password");
        WanTransportPasswordService passwordService = new WanTransportPasswordService("transport-key");

        assertThatThrownBy(() -> passwordService.decrypt(encryptedPassword))
                .isInstanceOf(IllegalStateException.class);
    }

}
