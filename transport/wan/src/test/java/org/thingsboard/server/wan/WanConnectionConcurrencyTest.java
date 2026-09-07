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
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WanConnectionConcurrencyTest {

    @Test
    void releasesAClaimWhenTheConnectionConcurrencyLimitIsBusy() throws Exception {
        UUID connectionId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        WanDeviceRegistryClient registryClient = Mockito.mock(WanDeviceRegistryClient.class);
        WanConnectionManager connectionManager = Mockito.mock(WanConnectionManager.class);
        WanNsRequestClient requestClient = Mockito.mock(WanNsRequestClient.class);
        WanDeviceSyncService service = new WanDeviceSyncService(
                registryClient, connectionManager, requestClient,
                new WanGatewayCommandFactory(), new WanTerminalCommandFactory());
        ReflectionTestUtils.setField(service, "connectionConcurrency", 1);
        when(connectionManager.hasConnection(connectionId)).thenReturn(true);
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch allowResponse = new CountDownLatch(1);
        when(requestClient.execute(eq(connectionId), any())).thenAnswer(invocation -> {
            requestStarted.countDown();
            assertThat(allowResponse.await(5, TimeUnit.SECONDS)).isTrue();
            return existingGateway();
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> first = executor.submit(() -> service.synchronize(registry(firstId, connectionId)));
            assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue();

            service.synchronize(registry(secondId, connectionId));

            verify(registryClient).release(secondId);
            allowResponse.countDown();
            first.get(5, TimeUnit.SECONDS);
            verify(registryClient).update(eq(firstId), eq(WanDeviceSyncStatus.ACTIVE),
                    Mockito.isNull(), any());
        } finally {
            allowResponse.countDown();
            executor.shutdownNow();
        }
    }

    private WanDeviceRegistrySnapshot registry(UUID deviceId, UUID connectionId) {
        return new WanDeviceRegistrySnapshot(deviceId, UUID.randomUUID(), connectionId,
                WanDeviceType.GATEWAY, "8C3F74C81C703000", "Gateway", "{}",
                WanDeviceSyncStatus.PENDING, null, null, null, 1L);
    }

    private JsonNode existingGateway() {
        return JacksonUtil.toJsonNode("""
                {"rsp_code":0,"rsp_body":[{"gw_id":"8C3F74C81C703000","freq_major":1,
                "freq_minor":2,"nwk_num":3,"tdd_num":4,"rate_num":1,
                "rate_cfgs":[{"rate_mode":0,"uplink_len":100,"downlink_len":120}]}]}
                """);
    }

}
