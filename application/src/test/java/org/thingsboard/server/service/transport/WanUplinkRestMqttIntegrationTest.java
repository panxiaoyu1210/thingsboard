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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.hivemq.HiveMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.device.data.DefaultDeviceConfiguration;
import org.thingsboard.server.common.data.device.data.DeviceData;
import org.thingsboard.server.common.data.device.data.WanDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.profile.WanDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
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
import org.thingsboard.server.wan.WanNsResponseCorrelator;
import org.thingsboard.server.wan.WanSessionInfoFactory;
import org.thingsboard.server.wan.WanTransportConfigurationProvider;
import org.thingsboard.server.wan.WanUplinkMessageParser;
import org.thingsboard.server.wan.WanUplinkService;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DaoSqlTest
public class WanUplinkRestMqttIntegrationTest extends AbstractControllerTest {

    private static final String NS_PUBLISH_TOPIC = "tenant/uplink/messages";
    private static final String NS_SUBSCRIBE_TOPIC = "tenant/uplink/requests";
    private static final String TERMINAL_EUI = "0000000000001002";

    @Autowired
    private TransportService transportService;

    @Before
    public void login() throws Exception {
        loginTenantAdmin();
    }

    @Test
    public void routesNsUplinkThroughRuleChainIntoTerminalTelemetry() throws Exception {
        HiveMQContainer broker = new HiveMQContainer(DockerImageName.parse("hivemq/hivemq-ce:2025.2"));
        broker.start();
        MqttAsyncClient nsClient = new MqttAsyncClient(
                "tcp://" + broker.getHost() + ":" + broker.getMqttPort(),
                "mock-uplink-ns-" + UUID.randomUUID(), new MemoryPersistence());
        WanConnectionManager connectionManager = null;
        try {
            WanConnection connection = doPost("/api/wan/connection", connection(broker), WanConnection.class);
            WanDeviceProfileTransportConfiguration profileConfiguration =
                    new WanDeviceProfileTransportConfiguration();
            profileConfiguration.setConnectionId(connection.getId());
            DeviceProfile profile = doPost("/api/deviceProfile",
                    createDeviceProfile("WAN Uplink Profile", profileConfiguration), DeviceProfile.class);
            Device terminal = doPost("/api/device", terminal(profile), Device.class);

            WanDeviceDescriptor descriptor = descriptor(terminal, connection.getId());
            WanTransportConfigurationProvider provider = mock(WanTransportConfigurationProvider.class);
            when(provider.load()).thenReturn(new WanConfigurationSnapshot(
                    List.of(connectionConfig(connection)), List.of(descriptor)));
            TbServiceInfoProvider serviceInfoProvider = mock(TbServiceInfoProvider.class);
            when(serviceInfoProvider.getServiceId()).thenReturn("wan-uplink-integration");
            WanNsResponseCorrelator correlator = new WanNsResponseCorrelator();
            WanUplinkMessageParser parser = new WanUplinkMessageParser();
            WanDeviceRouteRegistry routeRegistry = new WanDeviceRouteRegistry();
            WanUplinkService uplinkService = new WanUplinkService(parser, routeRegistry,
                    transportService, new WanSessionInfoFactory(serviceInfoProvider), Clock.systemUTC());
            connectionManager = new WanConnectionManager(provider,
                    configuration -> new PahoWanMqttClient(configuration,
                            new DefaultWanMessageHandler(correlator, uplinkService), 5, 30),
                    routeRegistry);
            connectionManager.refresh();

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            nsClient.connect(options).waitForCompletion(5_000);
            publish(nsClient, "0000000000009999", "DEADBEEF");
            publish(nsClient, TERMINAL_EUI, "01020304");

            String telemetryUrl = "/api/plugins/telemetry/DEVICE/" + terminal.getId().getId()
                    + "/values/timeseries?useStrictDataTypes=true&keys=wanData,wanRequestId,wanPort,rssi,snr"
                    + "&startTs=0&endTs="
                    + (System.currentTimeMillis() + 60_000L);
            await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted(() -> {
                ObjectNode telemetry = doGetAsync(telemetryUrl, ObjectNode.class);
                assertThat(telemetry.path("wanData")).hasSize(1);
                assertThat(telemetry.path("wanData").path(0).path("value").asText()).isEqualTo("01020304");
                assertThat(telemetry.path("wanRequestId").path(0).path("value").asText()).isEqualTo("1");
                assertThat(telemetry.path("wanPort").path(0).path("value").asText()).isEqualTo("0");
                assertThat(telemetry.path("rssi").path(0).path("value").asText()).isEqualTo("-54");
                assertThat(telemetry.path("snr").path(0).path("value").asText()).isEqualTo("18");
            });
            await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted(() -> {
                ArrayNode attributes = doGetAsync("/api/plugins/telemetry/DEVICE/"
                        + terminal.getId().getId()
                        + "/values/attributes/SERVER_SCOPE?keys=active,lastActivityTime", ArrayNode.class);
                assertThat(attributes).anySatisfy(attribute ->
                        assertThat(attribute.path("key").asText()).isEqualTo("lastActivityTime"));
            });
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

    private void publish(MqttAsyncClient nsClient, String deviceEui, String data) throws Exception {
        String payload = "{\"req_id\":1,\"req_opt\":\"push_uplink\",\"req_body\":{"
                + "\"dev_eui\":\"" + deviceEui + "\",\"rssi\":-54,\"snr\":18,"
                + "\"port\":0,\"data\":\"" + data + "\"}}";
        nsClient.publish(NS_PUBLISH_TOPIC, payload.getBytes(StandardCharsets.UTF_8), 1, false)
                .waitForCompletion(5_000);
    }

    private Device terminal(DeviceProfile profile) {
        WanTerminalConfiguration terminal = new WanTerminalConfiguration();
        terminal.setDevEui(TERMINAL_EUI);
        terminal.setDevType(0);
        terminal.setSecurityMode(0);
        WanDeviceTransportConfiguration transportConfiguration = new WanDeviceTransportConfiguration();
        transportConfiguration.setDeviceType(WanDeviceType.TERMINAL);
        transportConfiguration.setTerminal(terminal);
        DeviceData deviceData = new DeviceData();
        deviceData.setConfiguration(new DefaultDeviceConfiguration());
        deviceData.setTransportConfiguration(transportConfiguration);
        Device device = new Device();
        device.setName("WAN Uplink Terminal");
        device.setType("default");
        device.setDeviceProfileId(profile.getId());
        device.setDeviceData(deviceData);
        device.setAdditionalInfo(JacksonUtil.newObjectNode());
        return device;
    }

    private WanDeviceDescriptor descriptor(Device terminal, UUID connectionId) {
        WanDeviceTransportConfiguration configuration =
                (WanDeviceTransportConfiguration) terminal.getDeviceData().getTransportConfiguration();
        UUID customerId = terminal.getCustomerId() == null
                ? EntityId.NULL_UUID : terminal.getCustomerId().getId();
        return new WanDeviceDescriptor(terminal.getId().getId(), terminal.getTenantId().getId(), customerId,
                terminal.getDeviceProfileId().getId(), connectionId, terminal.getName(), terminal.getType(),
                false, WanDeviceType.TERMINAL, TERMINAL_EUI,
                JacksonUtil.writeValueAsBytes(configuration));
    }

    private WanConnection connection(HiveMQContainer broker) {
        WanConnection connection = new WanConnection();
        connection.setName("Uplink NS");
        connection.setBrokerHost(broker.getHost());
        connection.setBrokerPort(broker.getMqttPort());
        connection.setClientId("wan-uplink-" + UUID.randomUUID());
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

}
