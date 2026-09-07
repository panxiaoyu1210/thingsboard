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
import org.mockito.Mockito;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class WanUplinkRoutingTest {

    @Test
    void resolvesOnlyUniqueDevicesFromTheConnectionTenant() throws Exception {
        UUID connectionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        WanConnectionConfig connection = connection(connectionId, tenantId);
        WanDeviceDescriptor terminal = descriptor(connectionId, tenantId, UUID.randomUUID());
        WanTransportConfigurationProvider provider = Mockito.mock(WanTransportConfigurationProvider.class);
        WanMqttClientFactory clientFactory = Mockito.mock(WanMqttClientFactory.class);
        WanMqttClient client = Mockito.mock(WanMqttClient.class);
        when(client.configuration()).thenReturn(connection);
        when(clientFactory.create(connection)).thenReturn(client);
        when(provider.load()).thenReturn(new WanConfigurationSnapshot(List.of(connection), List.of(terminal)));
        WanDeviceRouteRegistry routeRegistry = new WanDeviceRouteRegistry();
        WanConnectionManager manager = new WanConnectionManager(provider, clientFactory, routeRegistry);

        manager.refresh();

        assertThat(manager.resolveDevice(connectionId, "000000000000ab12")).isEqualTo(terminal);

        WanDeviceDescriptor duplicate = descriptor(connectionId, tenantId, UUID.randomUUID());
        when(provider.load()).thenReturn(new WanConfigurationSnapshot(
                List.of(connection), List.of(terminal, duplicate)));
        manager.refresh();
        assertThatThrownBy(() -> manager.resolveDevice(connectionId, "000000000000AB12"))
                .isInstanceOf(WanUplinkException.class)
                .hasMessageContaining("ambiguous");

        WanDeviceDescriptor foreign = descriptor(connectionId, UUID.randomUUID(), UUID.randomUUID());
        when(provider.load()).thenReturn(new WanConfigurationSnapshot(List.of(connection), List.of(foreign)));
        manager.refresh();
        assertThatThrownBy(() -> manager.resolveDevice(connectionId, "000000000000AB12"))
                .isInstanceOf(WanUplinkException.class)
                .hasMessageContaining("unknown");
        manager.stop();
    }

    private WanConnectionConfig connection(UUID connectionId, UUID tenantId) {
        return new WanConnectionConfig(connectionId, tenantId, "NS", "localhost", 1883, false,
                "client", null, null, "ns/publish", "ns/subscribe",
                1, true, 5_000, 24, 1);
    }

    private WanDeviceDescriptor descriptor(UUID connectionId, UUID tenantId, UUID deviceId) {
        return new WanDeviceDescriptor(deviceId, tenantId, UUID.randomUUID(), UUID.randomUUID(),
                connectionId, "Terminal", "default", false, WanDeviceType.TERMINAL,
                "000000000000AB12", new byte[]{1});
    }

}
