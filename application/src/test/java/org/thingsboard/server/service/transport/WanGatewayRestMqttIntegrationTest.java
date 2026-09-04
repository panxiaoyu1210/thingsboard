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
package org.thingsboard.server.service.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.hivemq.HiveMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.device.data.DefaultDeviceConfiguration;
import org.thingsboard.server.common.data.device.data.DeviceData;
import org.thingsboard.server.common.data.device.data.WanDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.profile.WanDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanRateConfiguration;
import org.thingsboard.server.common.data.wan.WanConnection;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.common.transport.DeviceUpdatedEvent;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.controller.AbstractControllerTest;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.wan.DefaultWanMessageHandler;
import org.thingsboard.server.wan.PahoWanMqttClient;
import org.thingsboard.server.wan.WanConfigurationSnapshot;
import org.thingsboard.server.wan.WanConnectionConfig;
import org.thingsboard.server.wan.WanConnectionManager;
import org.thingsboard.server.wan.WanDeviceRegistryClient;
import org.thingsboard.server.wan.WanGatewayCommandFactory;
import org.thingsboard.server.wan.WanDeviceSyncService;
import org.thingsboard.server.wan.WanDeviceSyncTrigger;
import org.thingsboard.server.wan.WanNsRequestClient;
import org.thingsboard.server.wan.WanNsResponseCorrelator;
import org.thingsboard.server.wan.WanTerminalCommandFactory;
import org.thingsboard.server.wan.WanTransportConfigurationProvider;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DaoSqlTest
public class WanGatewayRestMqttIntegrationTest extends AbstractControllerTest {

    private static final String NS_PUBLISH_TOPIC = "tenant/ns/responses";
    private static final String NS_SUBSCRIBE_TOPIC = "tenant/ns/requests";

    @Autowired
    private DefaultTransportApiService transportApiService;

    @Before
    public void login() throws Exception {
        loginTenantAdmin();
    }

    @Test
    public void createsGatewayThroughRestAndSynchronizesItThroughMqtt() throws Exception {
        HiveMQContainer broker = new HiveMQContainer(DockerImageName.parse("hivemq/hivemq-ce:2025.2"));
        broker.start();
        MqttAsyncClient nsClient = nsClient(broker);
        WanConnectionManager connectionManager = null;
        try {
            List<JsonNode> received = new CopyOnWriteArrayList<>();
            startNs(nsClient, received);

            WanConnection connection = doPost("/api/wan/connection", connection(broker), WanConnection.class);
            WanDeviceProfileTransportConfiguration profileConfiguration =
                    new WanDeviceProfileTransportConfiguration();
            profileConfiguration.setConnectionId(connection.getId());
            DeviceProfile profile = doPost("/api/deviceProfile",
                    createDeviceProfile("WAN REST MQTT Profile", profileConfiguration), DeviceProfile.class);
            Device device = doPost("/api/device", gateway(profile), Device.class);

            WanDeviceRegistry pending = doGet(
                    "/api/wan/device/" + device.getId().getId() + "/sync", WanDeviceRegistry.class);
            assertThat(pending.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);

            WanNsResponseCorrelator correlator = new WanNsResponseCorrelator();
            WanTransportConfigurationProvider provider = mock(WanTransportConfigurationProvider.class);
            when(provider.load()).thenReturn(new WanConfigurationSnapshot(
                    List.of(connectionConfig(connection)), List.of()));
            connectionManager = new WanConnectionManager(provider,
                    configuration -> new PahoWanMqttClient(configuration,
                            new DefaultWanMessageHandler(correlator), 5, 30));
            connectionManager.refresh();

            WanDeviceRegistryClient registryClient = new WanDeviceRegistryClient(coreTransportService(), 200);
            WanDeviceSyncService syncService = new WanDeviceSyncService(registryClient, connectionManager,
                    new WanNsRequestClient(connectionManager, correlator), new WanGatewayCommandFactory(),
                    new WanTerminalCommandFactory());
            WanDeviceSyncTrigger trigger = new WanDeviceSyncTrigger(syncService);
            trigger.onDeviceUpdated(new DeviceUpdatedEvent(device));

            WanDeviceRegistry active = doGet(
                    "/api/wan/device/" + device.getId().getId() + "/sync", WanDeviceRegistry.class);
            assertThat(active.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.ACTIVE);
            assertThat(active.getLastSyncTime()).isNotNull();
            assertThat(active.getLastSuccessfulSyncTime()).isEqualTo(active.getLastSyncTime());
            assertThat(active.getError()).isNull();
            assertThat(received).hasSize(2);
            assertThat(received).extracting(node -> node.get("req_opt").asText())
                    .containsExactly("get_gateway", "add_gateway");
            assertThat(received).extracting(node -> node.get("req_id").asInt()).doesNotHaveDuplicates();
            JsonNode gatewayIds = received.get(0).path("req_body").path("gw_ids");
            assertThat(gatewayIds).hasSize(1);
            assertThat(gatewayIds.path(0).asText()).isEqualTo("8C3F74C81C703000");
            assertGatewayRequest(received.get(1).path("req_body").path(0));

            WanDeviceRegistry requested = doPost(
                    "/api/wan/device/" + device.getId().getId() + "/sync", WanDeviceRegistry.class);
            assertThat(requested.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
            trigger.onDeviceUpdated(new DeviceUpdatedEvent(device));
            WanDeviceRegistry failed = doGet(
                    "/api/wan/device/" + device.getId().getId() + "/sync", WanDeviceRegistry.class);
            assertThat(failed.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.FAILED);
            assertThat(failed.getError()).contains("gateway rejected");
            assertThat(failed.getLastSuccessfulSyncTime()).isEqualTo(active.getLastSuccessfulSyncTime());
            Device unchangedDevice = doGet("/api/device/" + device.getId().getId(), Device.class);
            WanGatewayConfiguration unchangedConfiguration = ((WanDeviceTransportConfiguration)
                    unchangedDevice.getDeviceData().getTransportConfiguration()).getGateway();
            assertThat(unchangedConfiguration.getFreqMajor()).isEqualTo(1);

            WanDeviceRegistry retry = doPost(
                    "/api/wan/device/" + device.getId().getId() + "/sync/retry", WanDeviceRegistry.class);
            assertThat(retry.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
            trigger.onDeviceUpdated(new DeviceUpdatedEvent(device));
            WanDeviceRegistry reconciled = doGet(
                    "/api/wan/device/" + device.getId().getId() + "/sync", WanDeviceRegistry.class);
            assertThat(reconciled.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.ACTIVE);
            assertThat(reconciled.getError()).isNull();
            Device updatedDevice = doGet("/api/device/" + device.getId().getId(), Device.class);
            WanGatewayConfiguration updatedConfiguration = ((WanDeviceTransportConfiguration)
                    updatedDevice.getDeviceData().getTransportConfiguration()).getGateway();
            assertThat(updatedConfiguration.getFreqMajor()).isEqualTo(5);
            assertThat(received).extracting(node -> node.get("req_opt").asText())
                    .containsExactly("get_gateway", "add_gateway", "get_gateway", "get_gateway");
        } finally {
            if (connectionManager != null) {
                connectionManager.stop();
            }
            if (nsClient.isConnected()) {
                nsClient.disconnectForcibly();
            }
            nsClient.close();
            broker.stop();
        }
    }

    private TransportService coreTransportService() {
        TransportService service = mock(TransportService.class);
        when(service.getWanDeviceRegistry(any())).thenAnswer(invocation -> transportApiService
                .handle(invocation.<TransportProtos.GetWanDeviceRegistryRequestMsg>getArgument(0))
                .getWanDeviceRegistryResponseMsg());
        when(service.updateWanDeviceRegistry(any())).thenAnswer(invocation -> transportApiService
                .handle(invocation.<TransportProtos.UpdateWanDeviceRegistryRequestMsg>getArgument(0))
                .getWanDeviceRegistryResponseMsg());
        return service;
    }

    private void startNs(MqttAsyncClient nsClient, List<JsonNode> received) throws Exception {
        AtomicInteger gatewayQueries = new AtomicInteger();
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
                if (WanGatewayCommandFactory.GET_GATEWAY.equals(request.path("req_opt").asText())) {
                    int query = gatewayQueries.incrementAndGet();
                    if (query == 2) {
                        response.put("rsp_code", 7);
                        response.put("rsp_desc", "gateway rejected");
                        response.putArray("rsp_body");
                    } else {
                        response.put("rsp_code", 0);
                        response.put("rsp_desc", "网关查询成功");
                        if (query == 1) {
                            response.putArray("rsp_body");
                        } else {
                            ObjectNode gateway = response.putArray("rsp_body").addObject();
                            gateway.put("gw_id", "8C3F74C81C703000");
                            gateway.put("freq_major", 5);
                            gateway.put("freq_minor", 6);
                            gateway.put("nwk_num", 7);
                            gateway.put("tdd_num", 8);
                            gateway.put("rate_num", 1);
                            ObjectNode rate = gateway.putArray("rate_cfgs").addObject();
                            rate.put("rate_mode", 4);
                            rate.put("uplink_len", 300);
                            rate.put("downlink_len", 301);
                        }
                    }
                } else {
                    response.putArray("rsp_code").add(0);
                    response.putArray("rsp_desc").add("网关添加成功");
                }
                nsClient.publish(NS_PUBLISH_TOPIC,
                                JacksonUtil.toString(response).getBytes(StandardCharsets.UTF_8), 1, false)
                        .waitForCompletion(5_000);
            }

            @Override
            public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
            }
        });
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        nsClient.connect(options).waitForCompletion(5_000);
        nsClient.subscribe(NS_SUBSCRIBE_TOPIC, 1).waitForCompletion(5_000);
    }

    private MqttAsyncClient nsClient(HiveMQContainer broker) throws Exception {
        return new MqttAsyncClient("tcp://" + broker.getHost() + ":" + broker.getMqttPort(),
                "mock-ns-" + UUID.randomUUID(), new MemoryPersistence());
    }

    private WanConnection connection(HiveMQContainer broker) {
        WanConnection connection = new WanConnection();
        connection.setName("REST MQTT NS");
        connection.setBrokerHost(broker.getHost());
        connection.setBrokerPort(broker.getMqttPort());
        connection.setClientId("rest-mqtt-" + UUID.randomUUID());
        connection.setNsPublishTopic(NS_PUBLISH_TOPIC);
        connection.setNsSubscribeTopic(NS_SUBSCRIBE_TOPIC);
        connection.setQos(1);
        connection.setEnabled(true);
        connection.setRequestTimeoutMs(5_000);
        connection.setSyncIntervalHours(24);
        return connection;
    }

    private WanConnectionConfig connectionConfig(WanConnection connection) {
        return new WanConnectionConfig(connection.getId(), connection.getTenantId().getId(), connection.getName(),
                connection.getBrokerHost(), connection.getBrokerPort(), connection.isTls(), connection.getClientId(),
                connection.getUsername(), null, connection.getNsPublishTopic(), connection.getNsSubscribeTopic(),
                connection.getQos(), connection.isEnabled(), connection.getRequestTimeoutMs(),
                connection.getSyncIntervalHours(), connection.getVersion());
    }

    private Device gateway(DeviceProfile profile) {
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
        WanDeviceTransportConfiguration transport = new WanDeviceTransportConfiguration();
        transport.setDeviceType(WanDeviceType.GATEWAY);
        transport.setGateway(gateway);
        DeviceData data = new DeviceData();
        data.setConfiguration(new DefaultDeviceConfiguration());
        data.setTransportConfiguration(transport);
        Device device = new Device();
        device.setName("WAN REST MQTT Gateway");
        device.setDeviceProfileId(profile.getId());
        device.setDeviceData(data);
        device.setAdditionalInfo(JacksonUtil.newObjectNode().put(DataConstants.GATEWAY_PARAMETER, true));
        return device;
    }

    private void assertGatewayRequest(JsonNode gateway) {
        assertThat(gateway.path("gw_id").asText()).isEqualTo("8C3F74C81C703000");
        assertThat(gateway.path("freq_major").asInt()).isEqualTo(1);
        assertThat(gateway.path("freq_minor").asInt()).isEqualTo(2);
        assertThat(gateway.path("nwk_num").asInt()).isEqualTo(3);
        assertThat(gateway.path("tdd_num").asInt()).isEqualTo(4);
        assertThat(gateway.path("rate_num").asInt()).isEqualTo(1);
        assertThat(gateway.path("rate_cfgs")).hasSize(1);
        assertThat(gateway.path("description").asText()).isEqualTo("WAN REST MQTT Gateway");
    }
}
