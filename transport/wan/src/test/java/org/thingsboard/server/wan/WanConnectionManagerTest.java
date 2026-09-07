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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.thingsboard.server.common.data.DataConstants;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanConnectionManagerTest {

    private WanTransportConfigurationProvider provider;
    private WanMqttClientFactory clientFactory;
    private WanConnectionManager manager;

    @BeforeEach
    void setUp() {
        provider = Mockito.mock(WanTransportConfigurationProvider.class);
        clientFactory = Mockito.mock(WanMqttClientFactory.class);
        manager = new WanConnectionManager(provider, clientFactory);
    }

    @Test
    void reconcilesAddedChangedRemovedAndFailedConnections() throws Exception {
        WanConnectionConfig first = connection(UUID.randomUUID(), "broker-one", true, 1);
        WanConnectionConfig second = connection(UUID.randomUUID(), "broker-two", true, 1);
        WanDeviceDescriptor device = new WanDeviceDescriptor(
                UUID.randomUUID(), UUID.randomUUID(), first.id(), new byte[]{1});
        WanMqttClient firstClient = client(first);
        WanMqttClient secondClient = client(second);
        when(clientFactory.create(first)).thenReturn(firstClient);
        when(clientFactory.create(second)).thenReturn(secondClient);
        when(provider.load()).thenReturn(new WanConfigurationSnapshot(List.of(first, second), List.of(device)));

        manager.refresh();
        manager.refresh();

        assertThat(manager.getName()).isEqualTo(DataConstants.WAN_TRANSPORT_NAME);
        assertThat(manager.activeConnectionCount()).isEqualTo(2);
        assertThat(manager.devices()).containsExactly(device);
        verify(firstClient, times(1)).start();
        verify(secondClient, times(1)).start();

        WanConnectionConfig changed = connection(first.id(), "broker-one-updated", true, 2);
        WanMqttClient replacement = client(changed);
        when(clientFactory.create(changed)).thenReturn(replacement);
        when(provider.load()).thenReturn(new WanConfigurationSnapshot(List.of(changed), List.of()));

        manager.refresh();

        verify(firstClient).close();
        verify(secondClient).close();
        verify(replacement).start();
        assertThat(manager.activeConnectionCount()).isEqualTo(1);
        assertThat(manager.devices()).isEmpty();

        when(provider.load()).thenThrow(new RuntimeException("Core temporarily unavailable"));
        manager.refresh();
        assertThat(manager.activeConnectionCount()).isZero();
        verify(replacement).close();

        manager.stop();
        verify(provider).releaseOwnership();
        assertThat(manager.activeConnectionCount()).isZero();
    }

    @Test
    void ignoresDisabledConnections() throws Exception {
        WanConnectionConfig disabled = connection(UUID.randomUUID(), "disabled", false, 1);
        when(provider.load()).thenReturn(new WanConfigurationSnapshot(List.of(disabled), List.of()));

        manager.refresh();

        assertThat(manager.activeConnectionCount()).isZero();
        verify(clientFactory, never()).create(disabled);
    }

    @Test
    void featureFlagControlsTransportServiceRegistration() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(WanTransportConfigurationProvider.class, () -> provider)
                .withBean(WanMqttClientFactory.class, () -> clientFactory)
                .withBean(WanDeviceRegistryClient.class, () -> Mockito.mock(WanDeviceRegistryClient.class))
                .withBean(WanNsResponseCorrelator.class, WanNsResponseCorrelator::new)
                .withBean(WanGatewayCommandFactory.class, WanGatewayCommandFactory::new)
                .withBean(WanTerminalCommandFactory.class, WanTerminalCommandFactory::new)
                .withUserConfiguration(WanConnectionManager.class, WanNsRequestClient.class,
                        WanDeviceSyncService.class, WanDeviceSyncTrigger.class, WanPendingSyncScheduler.class);

        runner.withPropertyValues("transport.wan.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(WanConnectionManager.class);
                    assertThat(context).doesNotHaveBean(WanNsRequestClient.class);
                    assertThat(context).doesNotHaveBean(WanDeviceSyncService.class);
                    assertThat(context).doesNotHaveBean(WanPendingSyncScheduler.class);
                });

        when(provider.load()).thenReturn(new WanConfigurationSnapshot(List.of(), List.of()));
        runner.withPropertyValues("transport.wan.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(WanConnectionManager.class);
                    assertThat(context).hasSingleBean(WanNsRequestClient.class);
                    assertThat(context).hasSingleBean(WanDeviceSyncService.class);
                    assertThat(context).hasSingleBean(WanPendingSyncScheduler.class);
                });
    }

    private WanMqttClient client(WanConnectionConfig configuration) {
        WanMqttClient client = Mockito.mock(WanMqttClient.class);
        when(client.configuration()).thenReturn(configuration);
        return client;
    }

    private WanConnectionConfig connection(UUID id, String host, boolean enabled, long version) {
        return new WanConnectionConfig(id, UUID.randomUUID(), "NS", host, 1883, false,
                "client-" + id, "user", "password", "ns/publish", "ns/subscribe",
                1, enabled, 5_000, 24, version);
    }

}
