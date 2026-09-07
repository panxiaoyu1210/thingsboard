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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.gen.transport.TransportProtos;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WanBroadcastServiceTest {

    @Test
    void targetsGatewayByDefaultAndRequiresExplicitAllFlagForNetworkBroadcast() throws Exception {
        WanConnectionManager manager = Mockito.mock(WanConnectionManager.class);
        TransportService transportService = Mockito.mock(TransportService.class);
        WanDownlinkService service = new WanDownlinkService(manager, transportService, Clock.systemUTC());
        WanDeviceDescriptor gateway = gateway();
        TransportProtos.SessionInfoProto session = TransportProtos.SessionInfoProto.getDefaultInstance();

        service.handle(gateway, session, request("{\"data\":\"01020304\"}"));
        service.handle(gateway, session,
                request("{\"broadcastAll\":true,\"data\":\"0102030405\"}"));

        ArgumentCaptor<byte[]> commands = ArgumentCaptor.forClass(byte[].class);
        verify(manager, Mockito.times(2)).publish(eq(gateway.connectionId()), commands.capture(), eq(1),
                Mockito.longThat(timeout -> timeout > 0 && timeout <= 60_000L));
        JsonNode targeted = JacksonUtil.fromBytes(commands.getAllValues().get(0));
        JsonNode network = JacksonUtil.fromBytes(commands.getAllValues().get(1));
        assertThat(targeted.path("req_opt").asText()).isEqualTo("push_broadcast");
        assertThat(targeted.path("req_body").path("gw_id").asText())
                .isEqualTo("8C3F74C81C703000");
        assertThat(targeted.path("req_body").path("data").asText()).isEqualTo("01020304");
        assertThat(network.path("req_body").has("gw_id")).isFalse();
        assertThat(network.path("req_body").path("data").asText()).isEqualTo("0102030405");
        assertThat(network.path("req_id").asInt()).isNotEqualTo(targeted.path("req_id").asInt());
    }

    @Test
    void rejectsImplicitOrOversizedNetworkBroadcastParameters() throws Exception {
        WanConnectionManager manager = Mockito.mock(WanConnectionManager.class);
        TransportService transportService = Mockito.mock(TransportService.class);
        WanDownlinkService service = new WanDownlinkService(manager, transportService, Clock.systemUTC());
        WanDeviceDescriptor gateway = gateway();
        TransportProtos.SessionInfoProto session = TransportProtos.SessionInfoProto.getDefaultInstance();

        service.handle(gateway, session,
                request("{\"broadcastAll\":\"true\",\"data\":\"0102\"}"));
        service.handle(gateway, session,
                request("{\"broadcastAll\":true,\"data\":\"" + "01".repeat(36) + "\"}"));

        verify(manager, never()).publish(any(), any(), anyInt(), anyLong());
    }

    private TransportProtos.ToDeviceRpcRequestMsg request(String params) {
        return TransportProtos.ToDeviceRpcRequestMsg.newBuilder()
                .setRequestId(1)
                .setMethodName(WanDownlinkService.BROADCAST_METHOD)
                .setParams(params)
                .setExpirationTime(System.currentTimeMillis() + 60_000L)
                .build();
    }

    private WanDeviceDescriptor gateway() {
        return new WanDeviceDescriptor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "Gateway", "default", true,
                WanDeviceType.GATEWAY, "8C3F74C81C703000", new byte[]{1});
    }

}
