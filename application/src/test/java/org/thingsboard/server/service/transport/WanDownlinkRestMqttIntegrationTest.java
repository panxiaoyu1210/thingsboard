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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
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
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanRateConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanTerminalConfiguration;
import org.thingsboard.server.common.data.wan.WanConnection;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.controller.AbstractControllerTest;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.server.queue.discovery.TbServiceInfoProvider;
import org.thingsboard.server.wan.DefaultWanMessageHandler;
import org.thingsboard.server.wan.PahoWanMqttClient;
import org.thingsboard.server.wan.WanConfigurationSnapshot;
import org.thingsboard.server.wan.WanConnectionConfig;
import org.thingsboard.server.wan.WanConnectionManager;
import org.thingsboard.server.wan.WanDeviceDescriptor;
import org.thingsboard.server.wan.WanDeviceRouteRegistry;
import org.thingsboard.server.wan.WanDownlinkService;
import org.thingsboard.server.wan.WanNsResponseCorrelator;
import org.thingsboard.server.wan.WanRpcSessionManager;
import org.thingsboard.server.wan.WanSessionInfoFactory;
import org.thingsboard.server.wan.WanTransportConfigurationProvider;
import org.thingsboard.server.wan.WanUplinkMessageParser;
import org.thingsboard.server.wan.WanUplinkService;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DaoSqlTest
public class WanDownlinkRestMqttIntegrationTest extends AbstractControllerTest {

    private static final String NS_PUBLISH_TOPIC = "tenant/downlink/responses";
    private static final String NS_SUBSCRIBE_TOPIC = "tenant/downlink/requests";
    private static final String TERMINAL_EUI = "0000000000001002";
    private static final String GATEWAY_ID = "8C3F74C81C703000";

    @Autowired
    private TransportService transportService;
    @Autowired
    private TbServiceInfoProvider serviceInfoProvider;

    @Before
    public void login() throws Exception {
        loginTenantAdmin();
    }

    @Test
    public void sendsTerminalDownlinkAndGatewayBroadcastsThroughServerRpc() throws Exception {
        HiveMQContainer broker = new HiveMQContainer(DockerImageName.parse("hivemq/hivemq-ce:2025.2"));
        broker.start();
        MqttAsyncClient nsClient = new MqttAsyncClient(
                "tcp://" + broker.getHost() + ":" + broker.getMqttPort(),
                "mock-downlink-ns-" + UUID.randomUUID(), new MemoryPersistence());
        WanConnectionManager connectionManager = null;
        WanRpcSessionManager rpcSessionManager = null;
        try {
            List<JsonNode> received = new CopyOnWriteArrayList<>();
            List<Integer> receivedQos = new CopyOnWriteArrayList<>();
            connectNs(nsClient, received, receivedQos);
            WanConnection connection = doPost("/api/wan/connection", connection(broker), WanConnection.class);
            WanDeviceProfileTransportConfiguration profileConfiguration =
                    new WanDeviceProfileTransportConfiguration();
            profileConfiguration.setConnectionId(connection.getId());
            DeviceProfile profile = doPost("/api/deviceProfile",
                    createDeviceProfile("WAN Downlink Profile", profileConfiguration), DeviceProfile.class);
            Device terminal = doPost("/api/device", terminal(profile), Device.class);
            Device gateway = doPost("/api/device", gateway(profile), Device.class);

            WanDeviceRouteRegistry routeRegistry = new WanDeviceRouteRegistry();
            WanTransportConfigurationProvider provider = mock(WanTransportConfigurationProvider.class);
            when(provider.load()).thenReturn(new WanConfigurationSnapshot(
                    List.of(connectionConfig(connection)),
                    List.of(descriptor(terminal, connection.getId(), WanDeviceType.TERMINAL, TERMINAL_EUI),
                            descriptor(gateway, connection.getId(), WanDeviceType.GATEWAY, GATEWAY_ID))));
            WanSessionInfoFactory sessionInfoFactory = new WanSessionInfoFactory(serviceInfoProvider);
            WanNsResponseCorrelator correlator = new WanNsResponseCorrelator();
            WanUplinkService uplinkService = new WanUplinkService(new WanUplinkMessageParser(),
                    routeRegistry, transportService, sessionInfoFactory, Clock.systemUTC());
            connectionManager = new WanConnectionManager(provider,
                    configuration -> new PahoWanMqttClient(configuration,
                            new DefaultWanMessageHandler(correlator, uplinkService), 5, 30),
                    routeRegistry);
            WanDownlinkService downlinkService = new WanDownlinkService(
                    connectionManager, transportService, Clock.systemUTC());
            rpcSessionManager = new WanRpcSessionManager(
                    routeRegistry, sessionInfoFactory, downlinkService, transportService);
            connectionManager.refresh();
            rpcSessionManager.start();
            WanRpcSessionManager finalRpcSessionManager = rpcSessionManager;
            await().atMost(java.time.Duration.ofSeconds(10))
                    .until(() -> finalRpcSessionManager.readySessionCount() == 2);

            ObjectNode terminalResponse = sendRpc(terminal, "wanDownlink",
                    JacksonUtil.newObjectNode().put("port", 3).put("data", "05060708"));
            ObjectNode targetedResponse = sendRpc(gateway, "wanBroadcast",
                    JacksonUtil.newObjectNode().put("data", "01020304"));
            ObjectNode networkResponse = sendRpc(gateway, "wanBroadcast",
                    JacksonUtil.newObjectNode().put("broadcastAll", true).put("data", "0102030405"));

            assertSent(terminalResponse);
            assertSent(targetedResponse);
            assertSent(networkResponse);
            await().atMost(java.time.Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(received).hasSize(3));
            JsonNode downlink = received.get(0);
            JsonNode targeted = received.get(1);
            JsonNode network = received.get(2);
            assertThat(downlink.path("req_opt").asText()).isEqualTo("push_downlink");
            assertThat(downlink.path("req_body").path("dev_eui").asText()).isEqualTo(TERMINAL_EUI);
            assertThat(downlink.path("req_body").path("port").asInt()).isEqualTo(3);
            assertThat(downlink.path("req_body").path("data").asText()).isEqualTo("05060708");
            assertThat(targeted.path("req_opt").asText()).isEqualTo("push_broadcast");
            assertThat(targeted.path("req_body").path("gw_id").asText()).isEqualTo(GATEWAY_ID);
            assertThat(targeted.path("req_body").path("data").asText()).isEqualTo("01020304");
            assertThat(network.path("req_body").has("gw_id")).isFalse();
            assertThat(network.path("req_body").path("data").asText()).isEqualTo("0102030405");
            assertThat(receivedQos).containsExactly(1, 1, 1);
        } finally {
            if (rpcSessionManager != null) {
                rpcSessionManager.stop();
            }
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

    private ObjectNode sendRpc(Device device, String method, ObjectNode params) throws Exception {
        ObjectNode rpc = JacksonUtil.newObjectNode();
        rpc.put("method", method);
        rpc.set("params", params);
        rpc.put("timeout", 10_000);
        return doPostAsync("/api/rpc/twoway/" + device.getId().getId(),
                JacksonUtil.toString(rpc), ObjectNode.class, MockMvcResultMatchers.status().isOk());
    }

    private void assertSent(ObjectNode response) {
        assertThat(response.path("success").asBoolean()).isTrue();
        assertThat(response.path("status").asText()).isEqualTo("SENT");
    }

    private void connectNs(MqttAsyncClient nsClient, List<JsonNode> received,
                           List<Integer> receivedQos) throws Exception {
        nsClient.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                received.add(JacksonUtil.fromBytes(message.getPayload()));
                receivedQos.add(message.getQos());
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

    private Device terminal(DeviceProfile profile) {
        WanTerminalConfiguration terminal = new WanTerminalConfiguration();
        terminal.setDevEui(TERMINAL_EUI);
        terminal.setDevType(0);
        terminal.setSecurityMode(0);
        WanDeviceTransportConfiguration transportConfiguration = new WanDeviceTransportConfiguration();
        transportConfiguration.setDeviceType(WanDeviceType.TERMINAL);
        transportConfiguration.setTerminal(terminal);
        return device("WAN Downlink Terminal", profile, false, transportConfiguration);
    }

    private Device gateway(DeviceProfile profile) {
        WanRateConfiguration rate = new WanRateConfiguration();
        rate.setRateMode(0);
        rate.setUplinkLen(100);
        rate.setDownlinkLen(120);
        WanGatewayConfiguration gateway = new WanGatewayConfiguration();
        gateway.setGwId(GATEWAY_ID);
        gateway.setFreqMajor(1);
        gateway.setFreqMinor(2);
        gateway.setNwkNum(3);
        gateway.setTddNum(4);
        gateway.setRateNum(1);
        gateway.setRateCfgs(List.of(rate));
        WanDeviceTransportConfiguration transportConfiguration = new WanDeviceTransportConfiguration();
        transportConfiguration.setDeviceType(WanDeviceType.GATEWAY);
        transportConfiguration.setGateway(gateway);
        return device("WAN Broadcast Gateway", profile, true, transportConfiguration);
    }

    private Device device(String name, DeviceProfile profile, boolean gateway,
                          WanDeviceTransportConfiguration transportConfiguration) {
        DeviceData deviceData = new DeviceData();
        deviceData.setConfiguration(new DefaultDeviceConfiguration());
        deviceData.setTransportConfiguration(transportConfiguration);
        Device device = new Device();
        device.setName(name);
        device.setType("default");
        device.setDeviceProfileId(profile.getId());
        device.setDeviceData(deviceData);
        ObjectNode additionalInfo = JacksonUtil.newObjectNode();
        additionalInfo.put(DataConstants.GATEWAY_PARAMETER, gateway);
        device.setAdditionalInfo(additionalInfo);
        return device;
    }

    private WanDeviceDescriptor descriptor(Device device, UUID connectionId,
                                           WanDeviceType role, String externalId) {
        UUID customerId = device.getCustomerId() == null ? EntityId.NULL_UUID : device.getCustomerId().getId();
        return new WanDeviceDescriptor(device.getId().getId(), device.getTenantId().getId(), customerId,
                device.getDeviceProfileId().getId(), connectionId, device.getName(), device.getType(),
                role == WanDeviceType.GATEWAY, role, externalId,
                JacksonUtil.writeValueAsBytes(device.getDeviceData().getTransportConfiguration()));
    }

    private WanConnection connection(HiveMQContainer broker) {
        WanConnection connection = new WanConnection();
        connection.setName("Downlink NS");
        connection.setBrokerHost(broker.getHost());
        connection.setBrokerPort(broker.getMqttPort());
        connection.setClientId("wan-downlink-" + UUID.randomUUID());
        connection.setNsPublishTopic(NS_PUBLISH_TOPIC);
        connection.setNsSubscribeTopic(NS_SUBSCRIBE_TOPIC);
        connection.setQos(0);
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

}
