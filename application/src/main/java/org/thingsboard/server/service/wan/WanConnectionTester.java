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
package org.thingsboard.server.service.wan;

import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.SsrfProtectionValidator;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.wan.WanConnection;
import org.thingsboard.server.common.data.wan.WanConnectionTestResult;

import java.net.URI;
import java.util.concurrent.TimeUnit;

@Service
public class WanConnectionTester {

    public WanConnectionTestResult test(WanConnection connection) {
        String scheme = connection.isTls() ? "ssl" : "tcp";
        String validationScheme = connection.isTls() ? "https" : "http";
        String authority = formatHost(connection.getBrokerHost()) + ":" + connection.getBrokerPort();
        try {
            SsrfProtectionValidator.validateUri(URI.create(validationScheme + "://" + authority));
        } catch (RuntimeException e) {
            return WanConnectionTestResult.failure("HOST_NOT_ALLOWED", "WAN NS broker host is not allowed by SSRF policy");
        }

        MqttAsyncClient client = null;
        try {
            client = new MqttAsyncClient(scheme + "://" + authority, connection.getClientId(), new MemoryPersistence());
            MqttConnectOptions options = createOptions(connection);
            IMqttToken token = client.connect(options);
            token.waitForCompletion(connection.getRequestTimeoutMs());
            if (!client.isConnected()) {
                return WanConnectionTestResult.failure("CONNECTION_FAILED", "WAN NS broker did not establish a connection");
            }
            return WanConnectionTestResult.connected();
        } catch (MqttException e) {
            return WanConnectionTestResult.failure("CONNECTION_FAILED",
                    "WAN NS broker connection failed (MQTT reason code " + e.getReasonCode() + ")");
        } finally {
            close(client);
        }
    }

    private MqttConnectOptions createOptions(WanConnection connection) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(false);
        options.setCleanSession(true);
        options.setConnectionTimeout((int) Math.max(1,
                TimeUnit.MILLISECONDS.toSeconds(connection.getRequestTimeoutMs() + 999L)));
        options.setKeepAliveInterval(15);
        if (StringUtils.isNotEmpty(connection.getUsername())) {
            options.setUserName(connection.getUsername());
        }
        if (connection.getPassword() != null) {
            options.setPassword(connection.getPassword().toCharArray());
        }
        if (connection.isTls()) {
            options.setHttpsHostnameVerificationEnabled(true);
        }
        return options;
    }

    private void close(MqttAsyncClient client) {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect().waitForCompletion(2_000);
            }
        } catch (MqttException ignored) {
        }
        try {
            client.close();
        } catch (MqttException ignored) {
        }
    }

    private static String formatHost(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

}
