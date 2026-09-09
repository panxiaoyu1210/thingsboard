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
package org.thingsboard.server.controller;

import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.alarm.AlarmCreateOrUpdateActiveRequest;
import org.thingsboard.server.common.data.alarm.AlarmSeverity;
import org.thingsboard.server.common.data.alarm.AlarmUpdateRequest;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.transport.wan.WanDeviceType;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.server.dao.wan.WanDeviceRegistryService;
import org.thingsboard.server.service.telemetry.AlarmSubscriptionService;
import org.thingsboard.server.service.wan.WanDeviceAlarmSummaryService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.thingsboard.server.service.wan.WanDeviceAlarmSummaryService.ACTIVE_ALARM_COUNT_ATTRIBUTE;
import static org.thingsboard.server.service.wan.WanDeviceAlarmSummaryService.HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE;

@DaoSqlTest
public class WanDeviceAlarmSummaryIntegrationTest extends AbstractControllerTest {

    @Autowired
    private AlarmSubscriptionService alarmSubscriptionService;

    @Autowired
    private WanDeviceRegistryService wanDeviceRegistryService;

    @Autowired
    private WanDeviceAlarmSummaryService alarmSummaryService;

    @Test
    public void shouldProjectMultipleAlarmLifecycleToWanDeviceAttributes() throws Exception {
        loginTenantAdmin();
        Device device = createDevice("WAN alarm summary device");
        registerWanDevice(device);
        long timestamp = System.currentTimeMillis();

        Alarm warning = createAlarm(device, "Warning alarm", AlarmSeverity.WARNING, timestamp);
        awaitSummary(device, 1L, AlarmSeverity.WARNING);

        warning.setSeverity(AlarmSeverity.MAJOR);
        alarmSubscriptionService.updateAlarm(AlarmUpdateRequest.fromAlarm(warning));
        awaitSummary(device, 1L, AlarmSeverity.MAJOR);

        attributesService.removeAll(tenantId, device.getId(), AttributeScope.SERVER_SCOPE,
                List.of(ACTIVE_ALARM_COUNT_ATTRIBUTE, HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE)).get();
        alarmSummaryService.reconcile();
        awaitSummary(device, 1L, AlarmSeverity.MAJOR);

        Alarm critical = createAlarm(device, "Critical alarm", AlarmSeverity.CRITICAL, timestamp + 1);
        awaitSummary(device, 2L, AlarmSeverity.CRITICAL);

        alarmSubscriptionService.acknowledgeAlarm(tenantId, critical.getId(), timestamp + 2);
        awaitSummary(device, 2L, AlarmSeverity.CRITICAL);

        alarmSubscriptionService.clearAlarm(tenantId, critical.getId(), timestamp + 3, null);
        awaitSummary(device, 1L, AlarmSeverity.MAJOR);

        assertThat(alarmSubscriptionService.deleteAlarm(tenantId, warning.getId())).isTrue();
        awaitSummary(device, 0L, null);
    }

    private Alarm createAlarm(Device device, String type, AlarmSeverity severity, long timestamp) {
        return alarmSubscriptionService.createAlarm(AlarmCreateOrUpdateActiveRequest.builder()
                .tenantId(tenantId)
                .originator(device.getId())
                .type(type)
                .severity(severity)
                .startTs(timestamp)
                .build()).getAlarm();
    }

    private void registerWanDevice(Device device) {
        WanDeviceRegistry registry = new WanDeviceRegistry();
        registry.setTenantId(tenantId);
        registry.setDeviceId(device.getId());
        registry.setConnectionId(UUID.randomUUID());
        registry.setDeviceType(WanDeviceType.TERMINAL);
        registry.setExternalId("0000000000000029");
        registry.setDeviceName(device.getName());
        registry.setConfiguration("{}");
        registry.setSyncStatus(WanDeviceSyncStatus.ACTIVE);
        wanDeviceRegistryService.save(registry);
    }

    private void awaitSummary(Device device, long expectedCount, AlarmSeverity expectedSeverity) {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<AttributeKvEntry> entries = attributesService.find(
                    tenantId, device.getId(), AttributeScope.SERVER_SCOPE,
                    List.of(ACTIVE_ALARM_COUNT_ATTRIBUTE, HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE)).get();
            Map<String, AttributeKvEntry> attributes = entries.stream()
                    .collect(Collectors.toMap(AttributeKvEntry::getKey, Function.identity()));
            assertThat(attributes.get(ACTIVE_ALARM_COUNT_ATTRIBUTE).getLongValue())
                    .contains(expectedCount);
            if (expectedSeverity == null) {
                assertThat(attributes).doesNotContainKey(HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE);
            } else {
                assertThat(attributes.get(HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE).getStrValue())
                        .contains(expectedSeverity.name());
            }
        });
    }

}
