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
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultWanMessageHandlerTest {

    @Test
    void dispatchesResponsesBeforeUplinksAndRejectsMalformedPayload() {
        UUID connectionId = UUID.randomUUID();
        WanNsResponseCorrelator correlator = Mockito.mock(WanNsResponseCorrelator.class);
        WanUplinkService uplinkService = Mockito.mock(WanUplinkService.class);
        DefaultWanMessageHandler handler = new DefaultWanMessageHandler(correlator, uplinkService);
        byte[] response = bytes("{\"req_id\":1,\"req_opt\":\"get_terminal\",\"rsp_code\":0}");
        when(correlator.onMessage(Mockito.eq(connectionId), any(JsonNode.class))).thenReturn(true);

        handler.onMessage(connectionId, "ns/publish", response);

        verify(uplinkService, never()).onMessage(any(), any());

        Mockito.reset(correlator, uplinkService);
        byte[] uplink = bytes("{\"req_id\":1,\"req_opt\":\"push_uplink\",\"req_body\":{}}");
        when(uplinkService.onMessage(Mockito.eq(connectionId), any(JsonNode.class))).thenReturn(true);
        handler.onMessage(connectionId, "ns/publish", uplink);
        verify(uplinkService).onMessage(Mockito.eq(connectionId), any(JsonNode.class));

        Mockito.reset(correlator, uplinkService);
        handler.onMessage(connectionId, "ns/publish", bytes("not-json"));
        verify(correlator, never()).onMessage(any(), any(JsonNode.class));
        verify(uplinkService, never()).onMessage(any(), any());

        Mockito.reset(correlator, uplinkService);
        doThrow(new RuntimeException("Core unavailable")).when(uplinkService)
                .onMessage(Mockito.eq(connectionId), any(JsonNode.class));
        handler.onMessage(connectionId, "ns/publish", uplink);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

}
