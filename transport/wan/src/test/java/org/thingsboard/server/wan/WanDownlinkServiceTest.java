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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.rpc.RpcStatus;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.gen.transport.TransportProtos;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WanDownlinkServiceTest {

    private WanConnectionManager connectionManager;
    private TransportService transportService;
    private WanDownlinkService service;
    private WanDeviceDescriptor terminal;
    private TransportProtos.SessionInfoProto sessionInfo;

    @BeforeEach
    void setUp() {
        connectionManager = Mockito.mock(WanConnectionManager.class);
        transportService = Mockito.mock(TransportService.class);
        service = new WanDownlinkService(connectionManager, transportService,
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));
        terminal = descriptor(WanDeviceType.TERMINAL, false, "0000000000001002");
        sessionInfo = TransportProtos.SessionInfoProto.newBuilder().setNodeId("test").build();
    }

    @Test
    void publishesTerminalDownlinkWithQosOneAndReturnsSent() throws Exception {
        TransportProtos.ToDeviceRpcRequestMsg request = request(
                WanDownlinkService.DOWNLINK_METHOD, "{\"port\":3,\"data\":\"05060708\"}");

        service.handle(terminal, sessionInfo, request);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(connectionManager).publish(eq(terminal.connectionId()), payload.capture(), eq(1), eq(1_000L));
        JsonNode command = JacksonUtil.fromBytes(payload.getValue());
        assertThat(command.path("req_id").asInt()).isPositive();
        assertThat(command.path("req_opt").asText()).isEqualTo("push_downlink");
        assertThat(command.path("req_body").path("dev_eui").asText())
                .isEqualTo("0000000000001002");
        assertThat(command.path("req_body").path("port").asInt()).isEqualTo(3);
        assertThat(command.path("req_body").path("data").asText()).isEqualTo("05060708");
        verify(transportService).process(sessionInfo, request, RpcStatus.SENT, TransportServiceCallback.EMPTY);
        assertSuccessResponse();
    }

    @Test
    void rejectsInvalidRolePortDataAndExpiredRequestsWithoutPublishing() throws Exception {
        service.handle(descriptor(WanDeviceType.GATEWAY, true, "8C3F74C81C703000"), sessionInfo,
                request(WanDownlinkService.DOWNLINK_METHOD, "{\"port\":3,\"data\":\"0506\"}"));
        service.handle(terminal, sessionInfo,
                request(WanDownlinkService.DOWNLINK_METHOD, "{\"port\":256,\"data\":\"0506\"}"));
        service.handle(terminal, sessionInfo,
                request(WanDownlinkService.DOWNLINK_METHOD, "{\"port\":3,\"data\":\"XYZ\"}"));
        TransportProtos.ToDeviceRpcRequestMsg expired = request(
                WanDownlinkService.DOWNLINK_METHOD, "{\"port\":3,\"data\":\"0506\"}")
                .toBuilder().setExpirationTime(999L).build();
        service.handle(terminal, sessionInfo, expired);

        verify(connectionManager, never()).publish(any(), any(), anyInt(), anyLong());
        ArgumentCaptor<TransportProtos.ToDeviceRpcResponseMsg> responses =
                ArgumentCaptor.forClass(TransportProtos.ToDeviceRpcResponseMsg.class);
        verify(transportService, Mockito.times(4)).process(eq(sessionInfo), responses.capture(),
                eq(TransportServiceCallback.EMPTY));
        assertThat(responses.getAllValues()).allSatisfy(response -> {
            JsonNode error = JacksonUtil.toJsonNode(response.getError());
            assertThat(error.path("success").asBoolean()).isFalse();
            assertThat(error.path("status").asText()).isEqualTo("FAILED");
        });
    }

    @Test
    void returnsFailureWhenBrokerPublishFails() throws Exception {
        doThrow(new RuntimeException("broker offline")).when(connectionManager)
                .publish(eq(terminal.connectionId()), any(byte[].class), eq(1), eq(1_000L));

        service.handle(terminal, sessionInfo,
                request(WanDownlinkService.DOWNLINK_METHOD, "{\"port\":3,\"data\":\"0506\"}"));

        verify(transportService, never()).process(sessionInfo, request(
                WanDownlinkService.DOWNLINK_METHOD, "{\"port\":3,\"data\":\"0506\"}"),
                RpcStatus.SENT, TransportServiceCallback.EMPTY);
        ArgumentCaptor<TransportProtos.ToDeviceRpcResponseMsg> response =
                ArgumentCaptor.forClass(TransportProtos.ToDeviceRpcResponseMsg.class);
        verify(transportService).process(eq(sessionInfo), response.capture(), eq(TransportServiceCallback.EMPTY));
        JsonNode error = JacksonUtil.toJsonNode(response.getValue().getError());
        assertThat(error.path("success").asBoolean()).isFalse();
        assertThat(error.path("status").asText()).isEqualTo("FAILED");
        assertThat(error.path("error").asText()).contains("broker offline");
    }

    @Test
    void brokerAckRemainsSuccessfulWhenSentStatusReportingFails() throws Exception {
        TransportProtos.ToDeviceRpcRequestMsg request = request(
                WanDownlinkService.DOWNLINK_METHOD, "{\"port\":3,\"data\":\"0506\"}");
        doThrow(new RuntimeException("status queue unavailable")).when(transportService)
                .process(sessionInfo, request, RpcStatus.SENT, TransportServiceCallback.EMPTY);

        service.handle(terminal, sessionInfo, request);

        verify(connectionManager).publish(eq(terminal.connectionId()), any(byte[].class),
                eq(1), eq(1_000L));
        assertSuccessResponse();
    }

    @Test
    void oneWayPublishFailureUpdatesRpcStatus() throws Exception {
        TransportProtos.ToDeviceRpcRequestMsg request = request(
                WanDownlinkService.DOWNLINK_METHOD, "{\"port\":3,\"data\":\"0506\"}")
                .toBuilder().setOneway(true).build();
        doThrow(new RuntimeException("publish timeout")).when(connectionManager)
                .publish(eq(terminal.connectionId()), any(byte[].class), eq(1), eq(1_000L));

        service.handle(terminal, sessionInfo, request);

        verify(transportService).process(sessionInfo, request,
                RpcStatus.FAILED, TransportServiceCallback.EMPTY);
        verify(transportService, never()).process(eq(sessionInfo),
                any(TransportProtos.ToDeviceRpcResponseMsg.class), eq(TransportServiceCallback.EMPTY));
    }

    private void assertSuccessResponse() {
        ArgumentCaptor<TransportProtos.ToDeviceRpcResponseMsg> response =
                ArgumentCaptor.forClass(TransportProtos.ToDeviceRpcResponseMsg.class);
        verify(transportService).process(eq(sessionInfo), response.capture(), eq(TransportServiceCallback.EMPTY));
        JsonNode payload = JacksonUtil.toJsonNode(response.getValue().getPayload());
        assertThat(payload.path("success").asBoolean()).isTrue();
        assertThat(payload.path("status").asText()).isEqualTo("SENT");
        assertThat(response.getValue().getError()).isEmpty();
    }

    private TransportProtos.ToDeviceRpcRequestMsg request(String method, String params) {
        return TransportProtos.ToDeviceRpcRequestMsg.newBuilder()
                .setRequestId(7)
                .setRequestIdMSB(1)
                .setRequestIdLSB(2)
                .setMethodName(method)
                .setParams(params)
                .setExpirationTime(2_000L)
                .build();
    }

    private WanDeviceDescriptor descriptor(WanDeviceType type, boolean gateway, String externalId) {
        return new WanDeviceDescriptor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "WAN Device", "default", gateway,
                type, externalId, "{}".getBytes(StandardCharsets.UTF_8));
    }

}
