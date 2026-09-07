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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.transport.SessionMsgListener;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.gen.transport.TransportProtos;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class WanRpcSessionManagerTest {

    @Test
    void registersDelegatesAndRemovesAsyncRpcSessions() {
        WanDeviceRouteRegistry routes = new WanDeviceRouteRegistry();
        WanSessionInfoFactory sessionInfoFactory = Mockito.mock(WanSessionInfoFactory.class);
        WanDownlinkService downlinkService = Mockito.mock(WanDownlinkService.class);
        TransportService transportService = Mockito.mock(TransportService.class);
        WanDeviceDescriptor device = device();
        TransportProtos.SessionInfoProto sessionInfo = TransportProtos.SessionInfoProto.newBuilder()
                .setSessionIdMSB(1).setSessionIdLSB(2).build();
        when(sessionInfoFactory.create(eq(device), any(UUID.class))).thenReturn(sessionInfo);
        WanRpcSessionManager manager = new WanRpcSessionManager(
                routes, sessionInfoFactory, downlinkService, transportService);

        manager.start();
        routes.replace(List.of(device), Map.of(device.connectionId(), connection(device)));
        manager.refresh();

        assertThat(manager.registeredSessionCount()).isOne();
        ArgumentCaptor<SessionMsgListener> listener = ArgumentCaptor.forClass(SessionMsgListener.class);
        verify(transportService).registerAsyncSession(eq(sessionInfo), listener.capture());
        verify(transportService).process(eq(sessionInfo),
                eq(TransportProtos.SubscribeToRPCMsg.newBuilder()
                        .setSessionType(TransportProtos.SessionType.ASYNC).build()), any());
        TransportProtos.ToDeviceRpcRequestMsg request =
                TransportProtos.ToDeviceRpcRequestMsg.newBuilder().setMethodName("wanDownlink").build();
        listener.getValue().onToDeviceRpcRequest(new UUID(1, 2), request);
        verify(downlinkService).handle(device, sessionInfo, request);

        routes.clear();

        assertThat(manager.registeredSessionCount()).isZero();
        verify(transportService).process(eq(sessionInfo),
                eq(TransportProtos.SubscribeToRPCMsg.newBuilder()
                        .setSessionType(TransportProtos.SessionType.ASYNC)
                        .setUnsubscribe(true).build()), any());
        verify(transportService).deregisterSession(sessionInfo);
        manager.stop();
    }

    @Test
    void cleansUpLocalSessionWhenSubscriptionFails() {
        WanDeviceRouteRegistry routes = new WanDeviceRouteRegistry();
        WanSessionInfoFactory sessionInfoFactory = Mockito.mock(WanSessionInfoFactory.class);
        WanDownlinkService downlinkService = Mockito.mock(WanDownlinkService.class);
        TransportService transportService = Mockito.mock(TransportService.class);
        WanDeviceDescriptor device = device();
        TransportProtos.SessionInfoProto sessionInfo = TransportProtos.SessionInfoProto.newBuilder()
                .setSessionIdMSB(3).setSessionIdLSB(4).build();
        when(sessionInfoFactory.create(eq(device), any(UUID.class))).thenReturn(sessionInfo);
        doThrow(new RuntimeException("subscription failed")).when(transportService)
                .process(eq(sessionInfo), any(TransportProtos.SubscribeToRPCMsg.class), any());
        WanRpcSessionManager manager = new WanRpcSessionManager(
                routes, sessionInfoFactory, downlinkService, transportService);

        manager.start();
        routes.replace(List.of(device), Map.of(device.connectionId(), connection(device)));

        assertThat(manager.registeredSessionCount()).isZero();
        verify(transportService).deregisterSession(sessionInfo);
        manager.stop();
    }

    private WanDeviceDescriptor device() {
        return new WanDeviceDescriptor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "Terminal", "default", false,
                WanDeviceType.TERMINAL, "0000000000001002", new byte[]{1});
    }

    private WanConnectionConfig connection(WanDeviceDescriptor device) {
        return new WanConnectionConfig(device.connectionId(), device.tenantId(), "NS", "localhost", 1883,
                false, "client", null, null, "ns/publish", "ns/subscribe",
                1, true, 5_000, 24, 1);
    }

}
