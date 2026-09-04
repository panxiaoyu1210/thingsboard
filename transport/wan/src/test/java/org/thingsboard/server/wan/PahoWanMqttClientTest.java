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

import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PahoWanMqttClientTest {

    @Test
    void enablesReconnectRestoresSubscriptionAndForwardsMessages() throws Exception {
        UUID connectionId = UUID.randomUUID();
        WanConnectionConfig configuration = new WanConnectionConfig(
                connectionId, UUID.randomUUID(), "NS", "mqtt.example.org", 8883, true,
                "client-id", "user", "password", "ns/publish", "ns/subscribe",
                2, true, 5_000, 24, 1);
        WanMessageHandler messageHandler = Mockito.mock(WanMessageHandler.class);
        MqttAsyncClient mqttClient = Mockito.mock(MqttAsyncClient.class);
        IMqttToken connectToken = Mockito.mock(IMqttToken.class);
        when(mqttClient.connect(Mockito.any(MqttConnectOptions.class))).thenReturn(connectToken);
        when(mqttClient.isConnected()).thenReturn(true);
        PahoWanMqttClient client = new PahoWanMqttClient(configuration, messageHandler, 10, 30, mqttClient);

        client.start();

        ArgumentCaptor<MqttConnectOptions> optionsCaptor = ArgumentCaptor.forClass(MqttConnectOptions.class);
        verify(mqttClient).connect(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().isAutomaticReconnect()).isTrue();
        assertThat(optionsCaptor.getValue().isCleanSession()).isTrue();
        assertThat(optionsCaptor.getValue().getConnectionTimeout()).isEqualTo(10);
        verify(connectToken).waitForCompletion(10_000);

        client.connectComplete(true, "ssl://mqtt.example.org:8883");
        verify(mqttClient).subscribe("ns/publish", 2);

        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        client.messageArrived("ns/publish", new MqttMessage(payload));
        verify(messageHandler).onMessage(connectionId, "ns/publish", payload);

        client.close();
        verify(mqttClient).disconnectForcibly();
        verify(mqttClient).close();
    }

}
