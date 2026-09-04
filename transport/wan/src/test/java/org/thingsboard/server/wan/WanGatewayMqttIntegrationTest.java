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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.testcontainers.hivemq.HiveMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.device.data.WanDeviceTransportConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanRateConfiguration;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Testcontainers
class WanGatewayMqttIntegrationTest {

    @Container
    private final HiveMQContainer broker =
            new HiveMQContainer(DockerImageName.parse("hivemq/hivemq-ce:2025.2"));

    @Test
    void queriesMissingGatewayAddsItAndBecomesActive() throws Exception {
        String requestTopic = "tenant/ns/requests";
        String responseTopic = "tenant/ns/responses";
        UUID connectionId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        MqttAsyncClient nsClient = nsClient();
        WanConnectionManager manager = null;
        try {
            nsClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    JsonNode request = JacksonUtil.fromBytes(message.getPayload());
                    received.add(request);
                    ObjectNode response = JacksonUtil.newObjectNode();
                    response.set("req_id", request.get("req_id"));
                    response.set("req_opt", request.get("req_opt"));
                    if (WanGatewayCommandFactory.GET_GATEWAY.equals(request.get("req_opt").asText())) {
                        response.put("rsp_code", 0);
                        response.put("rsp_desc", "网关查询成功");
                        response.set("rsp_body", JacksonUtil.newArrayNode());
                    } else {
                        ArrayNode codes = response.putArray("rsp_code");
                        codes.add(0);
                        response.putArray("rsp_desc").add("网关添加成功");
                    }
                    nsClient.publish(responseTopic,
                                    JacksonUtil.toString(response).getBytes(StandardCharsets.UTF_8), 1, false)
                            .waitForCompletion(5_000);
                }

                @Override
                public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
                }
            });
            MqttConnectOptions nsOptions = new MqttConnectOptions();
            nsOptions.setCleanSession(true);
            nsClient.connect(nsOptions).waitForCompletion(5_000);
            nsClient.subscribe(requestTopic, 1).waitForCompletion(5_000);

            WanConnectionConfig connection = new WanConnectionConfig(
                    connectionId, UUID.randomUUID(), "NS", broker.getHost(), broker.getMqttPort(), false,
                    "wan-transport-" + UUID.randomUUID(), null, null, responseTopic, requestTopic,
                    1, true, 5_000, 24, 1);
            WanNsResponseCorrelator correlator = new WanNsResponseCorrelator();
            WanTransportConfigurationProvider provider = Mockito.mock(WanTransportConfigurationProvider.class);
            when(provider.load()).thenReturn(new WanConfigurationSnapshot(List.of(connection), List.of()));
            WanMessageHandler messageHandler = new DefaultWanMessageHandler(correlator);
            manager = new WanConnectionManager(provider,
                    configuration -> new PahoWanMqttClient(configuration, messageHandler, 5, 30));
            manager.refresh();

            WanDeviceRegistryClient registryClient = Mockito.mock(WanDeviceRegistryClient.class);
            when(registryClient.get(deviceId)).thenReturn(registry(deviceId, connectionId));
            WanGatewaySyncService syncService = new WanGatewaySyncService(
                    registryClient, manager, new WanNsRequestClient(manager, correlator),
                    new WanGatewayCommandFactory());

            syncService.synchronize(deviceId);

            InOrder order = Mockito.inOrder(registryClient);
            order.verify(registryClient).update(deviceId, WanDeviceSyncStatus.SYNCING, null, null);
            order.verify(registryClient).update(deviceId, WanDeviceSyncStatus.CREATING, null, null);
            order.verify(registryClient).update(deviceId, WanDeviceSyncStatus.ACTIVE, null, null);
            assertThat(received).hasSize(2);
            assertThat(received).extracting(node -> node.get("req_opt").asText())
                    .containsExactly("get_gateway", "add_gateway");
            assertThat(received).extracting(node -> node.get("req_id").asInt()).doesNotHaveDuplicates();
            assertThat(received.get(0).get("req_body").get("gw_ids")).hasSize(1);
            JsonNode addGateway = received.get(1).get("req_body").get(0);
            assertThat(addGateway.get("gw_id").asText()).isEqualTo("8C3F74C81C703000");
            assertThat(addGateway.get("rate_cfgs").get(0).get("uplink_len").asInt()).isEqualTo(100);
        } finally {
            if (manager != null) {
                manager.stop();
            }
            if (nsClient.isConnected()) {
                nsClient.disconnectForcibly();
            }
            nsClient.close();
        }
    }

    private MqttAsyncClient nsClient() throws Exception {
        return new MqttAsyncClient(
                "tcp://" + broker.getHost() + ":" + broker.getMqttPort(),
                "mock-ns-" + UUID.randomUUID(), new MemoryPersistence());
    }

    private WanDeviceRegistrySnapshot registry(UUID deviceId, UUID connectionId) {
        return new WanDeviceRegistrySnapshot(deviceId, UUID.randomUUID(), connectionId,
                WanDeviceType.GATEWAY, "8C3F74C81C703000", "Gateway One",
                JacksonUtil.toString(deviceConfiguration()), WanDeviceSyncStatus.PENDING,
                null, null, null, 1);
    }

    private WanDeviceTransportConfiguration deviceConfiguration() {
        WanRateConfiguration rate = new WanRateConfiguration();
        rate.setRateMode(0);
        rate.setUplinkLen(100);
        rate.setDownlinkLen(120);
        WanGatewayConfiguration gateway = new WanGatewayConfiguration();
        gateway.setGwId("8C3F74C81C703000");
        gateway.setFreqMajor(1);
        gateway.setFreqMinor(2);
        gateway.setNwkNum(3);
        gateway.setTddNum(4);
        gateway.setRateNum(1);
        gateway.setRateCfgs(List.of(rate));
        WanDeviceTransportConfiguration configuration = new WanDeviceTransportConfiguration();
        configuration.setDeviceType(WanDeviceType.GATEWAY);
        configuration.setGateway(gateway);
        return configuration;
    }
}
