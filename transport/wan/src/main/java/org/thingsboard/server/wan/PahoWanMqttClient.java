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

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class PahoWanMqttClient implements WanMqttClient, MqttCallbackExtended {

    private final WanConnectionConfig configuration;
    private final WanMessageHandler messageHandler;
    private final int connectTimeoutSeconds;
    private final int keepAliveSeconds;
    private final MqttAsyncClient client;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PahoWanMqttClient(WanConnectionConfig configuration, WanMessageHandler messageHandler,
                             int connectTimeoutSeconds, int keepAliveSeconds) throws MqttException {
        this(configuration, messageHandler, connectTimeoutSeconds, keepAliveSeconds,
                new MqttAsyncClient(serverUri(configuration), configuration.clientId(), new MemoryPersistence()));
    }

    PahoWanMqttClient(WanConnectionConfig configuration, WanMessageHandler messageHandler,
                      int connectTimeoutSeconds, int keepAliveSeconds, MqttAsyncClient client) {
        this.configuration = configuration;
        this.messageHandler = messageHandler;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.keepAliveSeconds = keepAliveSeconds;
        this.client = client;
    }

    @Override
    public WanConnectionConfig configuration() {
        return configuration;
    }

    @Override
    public void start() throws MqttException {
        client.setCallback(this);
        IMqttToken token = client.connect(connectOptions());
        token.waitForCompletion(TimeUnit.SECONDS.toMillis(connectTimeoutSeconds));
        if (!client.isConnected()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        if (closed.get()) {
            return;
        }
        try {
            client.subscribe(configuration.nsPublishTopic(), configuration.qos());
            log.info("WAN connection [{}] subscribed to NS publish topic after {}connect",
                    configuration.id(), reconnect ? "re" : "");
        } catch (MqttException e) {
            log.warn("WAN connection [{}] failed to subscribe to NS publish topic", configuration.id(), e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        if (!closed.get()) {
            log.warn("WAN connection [{}] lost; MQTT automatic reconnect is active", configuration.id());
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        messageHandler.onMessage(configuration.id(), topic, message.getPayload().clone());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnectForcibly();
            }
        } catch (MqttException e) {
            log.debug("WAN connection [{}] disconnect failed during close", configuration.id());
        }
        try {
            client.close();
        } catch (MqttException e) {
            log.debug("WAN connection [{}] client close failed", configuration.id());
        }
    }

    private MqttConnectOptions connectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(connectTimeoutSeconds);
        options.setKeepAliveInterval(keepAliveSeconds);
        if (configuration.username() != null) {
            options.setUserName(configuration.username());
        }
        if (configuration.password() != null) {
            options.setPassword(configuration.password().toCharArray());
        }
        if (configuration.tls()) {
            options.setHttpsHostnameVerificationEnabled(true);
        }
        return options;
    }

    private static String serverUri(WanConnectionConfig configuration) {
        String scheme = configuration.tls() ? "ssl" : "tcp";
        String host = configuration.brokerHost();
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return scheme + "://" + host + ":" + configuration.brokerPort();
    }

}
