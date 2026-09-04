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
import org.thingsboard.server.common.data.SaveDeviceWithCredentialsRequest;
import org.thingsboard.server.common.data.device.credentials.WanDeviceCredentials;
import org.thingsboard.server.common.data.device.data.DefaultDeviceConfiguration;
import org.thingsboard.server.common.data.device.data.DeviceData;
import org.thingsboard.server.common.data.device.data.WanDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.profile.WanDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.transport.wan.WanGatewayConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanRateConfiguration;
import org.thingsboard.server.common.data.transport.wan.WanTerminalConfiguration;
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
import org.thingsboard.server.wan.WanDeviceSyncService;
import org.thingsboard.server.wan.WanDeviceSyncTrigger;
import org.thingsboard.server.wan.WanGatewayCommandFactory;
import org.thingsboard.server.wan.WanNsRequestClient;
import org.thingsboard.server.wan.WanNsResponseCorrelator;
import org.thingsboard.server.wan.WanTerminalCommandFactory;
import org.thingsboard.server.wan.WanTransportConfigurationProvider;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DaoSqlTest
public class WanTerminalRestMqttIntegrationTest extends AbstractControllerTest {

    private static final String NS_PUBLISH_TOPIC = "tenant/terminal/responses";
    private static final String NS_SUBSCRIBE_TOPIC = "tenant/terminal/requests";
    private static final String GATEWAY_EXTERNAL_ID = "8C3F74C81C703000";
    private static final String MISSING_TERMINAL_EUI = "0000000000001001";
    private static final String EXISTING_TERMINAL_EUI = "0000000000001002";
    private static final String CREATE_ROOT_KEY = "0102030405060708090A0B0C0D0E0F10";
    private static final String ORIGINAL_ROOT_KEY = "22222222222222222222222222222222";
    private static final String NS_ROOT_KEY = "11111111111111111111111111111111";

    @Autowired
    private DefaultTransportApiService transportApiService;

    @Before
    public void login() throws Exception {
        loginTenantAdmin();
    }

    @Test
    public void createsAndAdoptsTerminalsThroughRestAndMqttWithoutExposingRootKeys() throws Exception {
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
                    createDeviceProfile("WAN Terminal REST MQTT Profile", profileConfiguration), DeviceProfile.class);
            Device gateway = doPost("/api/device", gateway(profile), Device.class);
            Device missingTerminal = saveTerminal(profile, gateway.getId(), MISSING_TERMINAL_EUI,
                    "Missing Terminal", CREATE_ROOT_KEY);
            Device existingTerminal = saveTerminal(profile, gateway.getId(), EXISTING_TERMINAL_EUI,
                    "Existing Terminal", ORIGINAL_ROOT_KEY);

            assertPendingWithResolvedGateway(missingTerminal);
            assertPendingWithResolvedGateway(existingTerminal);
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

            trigger.onDeviceUpdated(new DeviceUpdatedEvent(missingTerminal));
            trigger.onDeviceUpdated(new DeviceUpdatedEvent(existingTerminal));

            assertActive(missingTerminal);
            assertActive(existingTerminal);
            assertThat(received).extracting(node -> node.path("req_opt").asText())
                    .containsExactly("get_terminal", "add_terminal", "get_terminal");
            JsonNode get = received.get(0);
            assertThat(get.path("req_body").path("dev_euis")).hasSize(1);
            assertThat(get.path("req_body").path("dev_euis").path(0).asText())
                    .isEqualTo(MISSING_TERMINAL_EUI);
            assertAddRequest(received.get(1).path("req_body").path(0));
            assertThat(received).extracting(node -> node.path("req_id").asInt()).doesNotHaveDuplicates();

            Device adopted = doGet("/api/device/" + existingTerminal.getId().getId(), Device.class);
            WanTerminalConfiguration adoptedConfiguration = ((WanDeviceTransportConfiguration)
                    adopted.getDeviceData().getTransportConfiguration()).getTerminal();
            assertThat(adoptedConfiguration.getDevType()).isZero();
            assertThat(adoptedConfiguration.getSecurityMode()).isEqualTo(4);
            assertThat(adoptedConfiguration.getRelatedGatewayId()).isEqualTo(gateway.getId());
            assertProtectedCredentials(missingTerminal.getId(), CREATE_ROOT_KEY);
            assertProtectedCredentials(existingTerminal.getId(), NS_ROOT_KEY);
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

    private void assertPendingWithResolvedGateway(Device terminal) throws Exception {
        WanDeviceRegistry state = doGet(
                "/api/wan/device/" + terminal.getId().getId() + "/sync", WanDeviceRegistry.class);
        assertThat(state.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.PENDING);
        assertThat(state.getRelatedExternalId()).isEqualTo(GATEWAY_EXTERNAL_ID);
    }

    private void assertActive(Device terminal) throws Exception {
        WanDeviceRegistry state = doGet(
                "/api/wan/device/" + terminal.getId().getId() + "/sync", WanDeviceRegistry.class);
        assertThat(state.getSyncStatus()).isEqualTo(WanDeviceSyncStatus.ACTIVE);
        assertThat(state.getLastSyncTime()).isNotNull();
        assertThat(state.getError()).isNull();
    }

    private void assertProtectedCredentials(DeviceId deviceId, String plaintextRootKey) throws Exception {
        String storedValue = jdbcTemplate.queryForObject(
                "SELECT credentials_value FROM device_credentials WHERE device_id = ?",
                String.class, deviceId.getId());
        WanDeviceCredentials stored = JacksonUtil.fromString(storedValue, WanDeviceCredentials.class);
        assertThat(stored.getRootKey()).startsWith("v1:").doesNotContain(plaintextRootKey);
        assertThat(storedValue).doesNotContain(plaintextRootKey);

        DeviceCredentials exposed = doGet(
                "/api/device/" + deviceId.getId() + "/credentials", DeviceCredentials.class);
        WanDeviceCredentials exposedValue = JacksonUtil.fromString(
                exposed.getCredentialsValue(), WanDeviceCredentials.class);
        assertThat(exposedValue.getRootKey()).isEqualTo(WanDeviceCredentials.ROOT_KEY_MASK);
        assertThat(exposed.getCredentialsValue()).doesNotContain(plaintextRootKey);

        DeviceCredentials unchanged = doPost("/api/device/credentials", exposed, DeviceCredentials.class);
        WanDeviceCredentials unchangedValue = JacksonUtil.fromString(
                unchanged.getCredentialsValue(), WanDeviceCredentials.class);
        assertThat(unchangedValue.getRootKey()).isEqualTo(WanDeviceCredentials.ROOT_KEY_MASK);
        String storedAfterMaskedUpdate = jdbcTemplate.queryForObject(
                "SELECT credentials_value FROM device_credentials WHERE device_id = ?",
                String.class, deviceId.getId());
        assertThat(storedAfterMaskedUpdate).contains("v1:").doesNotContain(plaintextRootKey);
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
                if (WanTerminalCommandFactory.GET_TERMINAL.equals(request.path("req_opt").asText())) {
                    response.put("rsp_code", 0);
                    response.put("rsp_desc", "终端查询成功");
                    String deviceEui = request.path("req_body").path("dev_euis").path(0).asText();
                    if (EXISTING_TERMINAL_EUI.equals(deviceEui)) {
                        ObjectNode terminal = response.putArray("rsp_body").addObject();
                        terminal.put("dev_eui", EXISTING_TERMINAL_EUI);
                        terminal.put("dev_type", 0);
                        terminal.put("addr_mode", 1);
                        terminal.put("nwk_id", "0001");
                        terminal.put("nwk_addr", "1002");
                        terminal.put("security_mode", 4);
                        terminal.put("root_key", NS_ROOT_KEY);
                        terminal.put("related_id", GATEWAY_EXTERNAL_ID);
                        terminal.put("description", "Existing NS Terminal");
                    } else {
                        response.putArray("rsp_body");
                    }
                } else {
                    response.putArray("rsp_code").add(0);
                    response.putArray("rsp_desc").add("终端添加成功");
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
                "mock-terminal-ns-" + UUID.randomUUID(), new MemoryPersistence());
    }

    private WanConnection connection(HiveMQContainer broker) {
        WanConnection connection = new WanConnection();
        connection.setName("Terminal REST MQTT NS");
        connection.setBrokerHost(broker.getHost());
        connection.setBrokerPort(broker.getMqttPort());
        connection.setClientId("terminal-rest-mqtt-" + UUID.randomUUID());
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
        gateway.setGwId(GATEWAY_EXTERNAL_ID);
        gateway.setFreqMajor(1);
        gateway.setFreqMinor(2);
        gateway.setNwkNum(3);
        gateway.setTddNum(4);
        gateway.setRateNum(1);
        gateway.setRateCfgs(List.of(rate));
        WanDeviceTransportConfiguration transport = new WanDeviceTransportConfiguration();
        transport.setDeviceType(WanDeviceType.GATEWAY);
        transport.setGateway(gateway);
        return device("Related Gateway", profile, transport, true);
    }

    private Device saveTerminal(DeviceProfile profile, DeviceId gatewayId, String deviceEui,
                                String name, String rootKey) {
        WanTerminalConfiguration terminal = new WanTerminalConfiguration();
        terminal.setDevEui(deviceEui);
        terminal.setDevType(1);
        terminal.setSecurityMode(5);
        terminal.setRelatedGatewayId(gatewayId);
        WanDeviceTransportConfiguration transport = new WanDeviceTransportConfiguration();
        transport.setDeviceType(WanDeviceType.TERMINAL);
        transport.setTerminal(terminal);
        WanDeviceCredentials wanCredentials = new WanDeviceCredentials();
        wanCredentials.setRootKey(rootKey);
        DeviceCredentials credentials = new DeviceCredentials();
        credentials.setCredentialsType(DeviceCredentialsType.WAN_CREDENTIALS);
        credentials.setCredentialsId(deviceEui);
        credentials.setCredentialsValue(JacksonUtil.toString(wanCredentials));
        return doPost("/api/device-with-credentials",
                new SaveDeviceWithCredentialsRequest(device(name, profile, transport, false), credentials), Device.class);
    }

    private Device device(String name, DeviceProfile profile,
                          WanDeviceTransportConfiguration transport, boolean gateway) {
        DeviceData data = new DeviceData();
        data.setConfiguration(new DefaultDeviceConfiguration());
        data.setTransportConfiguration(transport);
        Device device = new Device();
        device.setName(name);
        device.setDeviceProfileId(profile.getId());
        device.setDeviceData(data);
        device.setAdditionalInfo(JacksonUtil.newObjectNode().put(DataConstants.GATEWAY_PARAMETER, gateway));
        return device;
    }

    private void assertAddRequest(JsonNode terminal) {
        assertThat(terminal.path("dev_eui").asText()).isEqualTo(MISSING_TERMINAL_EUI);
        assertThat(terminal.path("dev_type").asInt()).isEqualTo(1);
        assertThat(terminal.path("security_mode").asInt()).isEqualTo(5);
        assertThat(terminal.path("root_key").asText()).isEqualTo(CREATE_ROOT_KEY);
        assertThat(terminal.path("related_id").asText()).isEqualTo(GATEWAY_EXTERNAL_ID);
        assertThat(terminal.path("description").asText()).isEqualTo("Missing Terminal");
    }
}
