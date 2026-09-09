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
package org.thingsboard.server.service.wan;

import com.google.common.util.concurrent.Futures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.rule.engine.api.AttributesDeleteRequest;
import org.thingsboard.rule.engine.api.AttributesSaveRequest;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.alarm.AlarmSeverity;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.AttributesSaveResult;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.dao.alarm.AlarmService;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.dao.wan.WanDeviceRegistryService;
import org.thingsboard.server.queue.discovery.PartitionService;
import org.thingsboard.server.service.telemetry.TelemetrySubscriptionService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.thingsboard.server.service.wan.WanDeviceAlarmSummaryService.ACTIVE_ALARM_COUNT_ATTRIBUTE;
import static org.thingsboard.server.service.wan.WanDeviceAlarmSummaryService.HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE;

@ExtendWith(MockitoExtension.class)
class WanDeviceAlarmSummaryServiceTest {

    @Mock
    private AlarmService alarmService;
    @Mock
    private AttributesService attributesService;
    @Mock
    private TelemetrySubscriptionService telemetrySubscriptionService;
    @Mock
    private WanDeviceRegistryService wanDeviceRegistryService;
    @Mock
    private PartitionService partitionService;

    private WanDeviceAlarmSummaryService service;
    private TenantId tenantId;
    private DeviceId deviceId;

    @BeforeEach
    void setUp() {
        service = new WanDeviceAlarmSummaryService(
                alarmService, attributesService, telemetrySubscriptionService,
                wanDeviceRegistryService, partitionService);
        ReflectionTestUtils.setField(service, "operationTimeoutMs", 1_000L);
        tenantId = TenantId.fromUUID(UUID.randomUUID());
        deviceId = new DeviceId(UUID.randomUUID());
    }

    @Test
    void shouldSaveCountAndHighestSeverityForMultipleActiveAlarms() throws Exception {
        givenWanRegistry();
        when(alarmService.findActiveAlarmCountsBySeverity(tenantId, deviceId))
                .thenReturn(Map.of(AlarmSeverity.CRITICAL, 1L, AlarmSeverity.WARNING, 2L));
        when(attributesService.find(tenantId, deviceId, AttributeScope.SERVER_SCOPE,
                List.of(ACTIVE_ALARM_COUNT_ATTRIBUTE, HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE)))
                .thenReturn(Futures.immediateFuture(List.of()));
        when(telemetrySubscriptionService.saveAttributesInternal(any()))
                .thenReturn(Futures.immediateFuture(AttributesSaveResult.EMPTY));

        service.refreshNow(tenantId, deviceId);

        ArgumentCaptor<AttributesSaveRequest> requestCaptor = ArgumentCaptor.forClass(AttributesSaveRequest.class);
        verify(telemetrySubscriptionService).saveAttributesInternal(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getEntries())
                .extracting(AttributeKvEntry::getKey, AttributeKvEntry::getValue)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(ACTIVE_ALARM_COUNT_ATTRIBUTE, 3L),
                        org.assertj.core.groups.Tuple.tuple(HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE, "CRITICAL"));
    }

    @Test
    void shouldRestoreNormalStateAndDeleteStaleSeverity() throws Exception {
        givenWanRegistry();
        when(alarmService.findActiveAlarmCountsBySeverity(tenantId, deviceId)).thenReturn(Map.of());
        when(attributesService.find(tenantId, deviceId, AttributeScope.SERVER_SCOPE,
                List.of(ACTIVE_ALARM_COUNT_ATTRIBUTE, HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE)))
                .thenReturn(Futures.immediateFuture(List.of(
                        attribute(new LongDataEntry(ACTIVE_ALARM_COUNT_ATTRIBUTE, 2L)),
                        attribute(new StringDataEntry(HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE, "CRITICAL")))));
        when(telemetrySubscriptionService.saveAttributesInternal(any()))
                .thenReturn(Futures.immediateFuture(AttributesSaveResult.EMPTY));
        doAnswer(invocation -> {
            AttributesDeleteRequest request = invocation.getArgument(0);
            request.getCallback().onSuccess(null);
            return null;
        }).when(telemetrySubscriptionService).deleteAttributes(any());

        service.refreshNow(tenantId, deviceId);

        ArgumentCaptor<AttributesSaveRequest> saveCaptor = ArgumentCaptor.forClass(AttributesSaveRequest.class);
        verify(telemetrySubscriptionService).saveAttributesInternal(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getEntries())
                .extracting(AttributeKvEntry::getKey, AttributeKvEntry::getValue)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(ACTIVE_ALARM_COUNT_ATTRIBUTE, 0L));
        ArgumentCaptor<AttributesDeleteRequest> deleteCaptor = ArgumentCaptor.forClass(AttributesDeleteRequest.class);
        verify(telemetrySubscriptionService).deleteAttributes(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().getKeys()).containsExactly(HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE);
    }

    @Test
    void shouldSkipUnchangedSummary() throws Exception {
        givenWanRegistry();
        when(alarmService.findActiveAlarmCountsBySeverity(tenantId, deviceId))
                .thenReturn(Map.of(AlarmSeverity.MAJOR, 1L));
        when(attributesService.find(tenantId, deviceId, AttributeScope.SERVER_SCOPE,
                List.of(ACTIVE_ALARM_COUNT_ATTRIBUTE, HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE)))
                .thenReturn(Futures.immediateFuture(List.of(
                        attribute(new LongDataEntry(ACTIVE_ALARM_COUNT_ATTRIBUTE, 1L)),
                        attribute(new StringDataEntry(HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE, "MAJOR")))));

        service.refreshNow(tenantId, deviceId);

        verify(telemetrySubscriptionService, never()).saveAttributesInternal(any());
        verify(telemetrySubscriptionService, never()).deleteAttributes(any());
    }

    @Test
    void shouldSkipDevicesOutsideWanRegistry() throws Exception {
        when(wanDeviceRegistryService.findByDeviceId(tenantId, deviceId)).thenReturn(null);

        service.refreshNow(tenantId, deviceId);

        verify(alarmService, never()).findActiveAlarmCountsBySeverity(any(), any());
        verify(telemetrySubscriptionService, never()).saveAttributesInternal(any());
    }

    @Test
    void shouldIgnoreNonDeviceAlarmOriginators() {
        ReflectionTestUtils.setField(service, "enabled", true);

        service.refresh(tenantId, new AssetId(UUID.randomUUID()));

        verify(wanDeviceRegistryService, never()).findByDeviceId(any(), any());
        verify(alarmService, never()).findActiveAlarmCountsBySeverity(any(), any());
    }

    private void givenWanRegistry() {
        WanDeviceRegistry registry = new WanDeviceRegistry();
        registry.setTenantId(tenantId);
        registry.setDeviceId(deviceId);
        when(wanDeviceRegistryService.findByDeviceId(tenantId, deviceId)).thenReturn(registry);
    }

    private BaseAttributeKvEntry attribute(org.thingsboard.server.common.data.kv.KvEntry entry) {
        return new BaseAttributeKvEntry(entry, System.currentTimeMillis());
    }

}
