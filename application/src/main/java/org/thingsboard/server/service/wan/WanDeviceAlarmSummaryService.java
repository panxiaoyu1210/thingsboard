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

import com.google.common.util.concurrent.SettableFuture;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.rule.engine.api.AttributesDeleteRequest;
import org.thingsboard.rule.engine.api.AttributesSaveRequest;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.alarm.AlarmSeverity;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.wan.WanDeviceRegistry;
import org.thingsboard.server.common.data.wan.WanDeviceSyncStatus;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.dao.alarm.AlarmService;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.dao.wan.WanDeviceRegistryService;
import org.thingsboard.server.queue.discovery.PartitionService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.telemetry.TelemetrySubscriptionService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@TbCoreComponent
@Slf4j
@RequiredArgsConstructor
public class WanDeviceAlarmSummaryService {

    public static final String ACTIVE_ALARM_COUNT_ATTRIBUTE = "activeAlarmCount";
    public static final String HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE = "highestActiveAlarmSeverity";

    private static final List<String> SUMMARY_ATTRIBUTE_KEYS = List.of(
            ACTIVE_ALARM_COUNT_ATTRIBUTE, HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE);

    private final AlarmService alarmService;
    private final AttributesService attributesService;
    private final TelemetrySubscriptionService telemetrySubscriptionService;
    private final WanDeviceRegistryService wanDeviceRegistryService;
    private final PartitionService partitionService;

    private final Set<DeviceContext> pendingRefreshes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean workerRunning = new AtomicBoolean();
    private final AtomicBoolean reconciliationPending = new AtomicBoolean();

    @Value("${wan.alarm-summary.enabled:true}")
    private boolean enabled;

    @Value("${wan.alarm-summary.reconciliation-page-size:500}")
    private int reconciliationPageSize;

    @Value("${wan.alarm-summary.operation-timeout-ms:10000}")
    private long operationTimeoutMs;

    private ExecutorService worker;

    @PostConstruct
    public void init() {
        worker = Executors.newSingleThreadExecutor(
                ThingsBoardThreadFactory.forName("wan-alarm-summary"));
    }

    @PreDestroy
    public void destroy() {
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    public void refresh(TenantId tenantId, EntityId originatorId) {
        if (!enabled || originatorId == null || originatorId.getEntityType() != EntityType.DEVICE) {
            return;
        }
        pendingRefreshes.add(new DeviceContext(tenantId, new DeviceId(originatorId.getId())));
        scheduleWorker();
    }

    @Scheduled(initialDelayString = "${wan.alarm-summary.reconciliation-initial-delay-ms:30000}",
            fixedDelayString = "${wan.alarm-summary.reconciliation-interval-ms:3600000}")
    public void reconcile() {
        if (!enabled || !reconciliationPending.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            try {
                reconcileNow();
            } finally {
                reconciliationPending.set(false);
            }
        });
    }

    private void reconcileNow() {
        int page = 0;
        PageData<WanDeviceRegistry> registries;
        do {
            registries = wanDeviceRegistryService.findAll(new PageLink(Math.max(1, reconciliationPageSize), page++));
            for (WanDeviceRegistry registry : registries.getData()) {
                if (registry.getSyncStatus() == WanDeviceSyncStatus.DELETING) {
                    continue;
                }
                try {
                    if (partitionService.resolve(
                            ServiceType.TB_CORE, registry.getTenantId(), registry.getDeviceId()).isMyPartition()) {
                        refreshRegisteredDevice(registry.getTenantId(), registry.getDeviceId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.warn("[{}][{}] Failed to reconcile WAN device alarm summary",
                            registry.getTenantId(), registry.getDeviceId(), e);
                }
            }
        } while (registries.hasNext());
    }

    void refreshNow(TenantId tenantId, DeviceId deviceId) throws Exception {
        WanDeviceRegistry registry = wanDeviceRegistryService.findByDeviceId(tenantId, deviceId);
        if (registry == null || registry.getSyncStatus() == WanDeviceSyncStatus.DELETING) {
            return;
        }
        refreshRegisteredDevice(tenantId, deviceId);
    }

    private void refreshRegisteredDevice(TenantId tenantId, DeviceId deviceId) throws Exception {
        Map<AlarmSeverity, Long> countsBySeverity =
                alarmService.findActiveAlarmCountsBySeverity(tenantId, deviceId);
        long activeAlarmCount = countsBySeverity.values().stream().mapToLong(Long::longValue).sum();
        AlarmSeverity highestSeverity = countsBySeverity.keySet().stream()
                .min(AlarmSeverity::compareTo)
                .orElse(null);

        CurrentSummary current = readCurrentSummary(tenantId, deviceId);
        String highestSeverityName = highestSeverity != null ? highestSeverity.name() : null;
        if (Objects.equals(current.activeAlarmCount(), activeAlarmCount) &&
                Objects.equals(current.highestSeverity(), highestSeverityName)) {
            return;
        }

        List<AttributeKvEntry> attributes = new ArrayList<>(2);
        long timestamp = System.currentTimeMillis();
        attributes.add(new BaseAttributeKvEntry(
                new LongDataEntry(ACTIVE_ALARM_COUNT_ATTRIBUTE, activeAlarmCount), timestamp));
        if (highestSeverityName != null) {
            attributes.add(new BaseAttributeKvEntry(
                    new StringDataEntry(HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE, highestSeverityName), timestamp));
        }
        telemetrySubscriptionService.saveAttributesInternal(AttributesSaveRequest.builder()
                        .tenantId(tenantId)
                        .entityId(deviceId)
                        .scope(AttributeScope.SERVER_SCOPE)
                        .entries(attributes)
                        .build())
                .get(operationTimeoutMs, TimeUnit.MILLISECONDS);

        if (highestSeverityName == null && current.highestSeverity() != null) {
            SettableFuture<Void> deleteFuture = SettableFuture.create();
            telemetrySubscriptionService.deleteAttributes(AttributesDeleteRequest.builder()
                    .tenantId(tenantId)
                    .entityId(deviceId)
                    .scope(AttributeScope.SERVER_SCOPE)
                    .keys(List.of(HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE))
                    .future(deleteFuture)
                    .build());
            deleteFuture.get(operationTimeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private CurrentSummary readCurrentSummary(TenantId tenantId, DeviceId deviceId) throws Exception {
        Collection<AttributeKvEntry> attributes = attributesService.find(
                        tenantId, deviceId, AttributeScope.SERVER_SCOPE, SUMMARY_ATTRIBUTE_KEYS)
                .get(operationTimeoutMs, TimeUnit.MILLISECONDS);
        Long activeAlarmCount = null;
        String highestSeverity = null;
        for (AttributeKvEntry attribute : attributes) {
            if (ACTIVE_ALARM_COUNT_ATTRIBUTE.equals(attribute.getKey())) {
                activeAlarmCount = attribute.getLongValue().orElse(null);
            } else if (HIGHEST_ACTIVE_ALARM_SEVERITY_ATTRIBUTE.equals(attribute.getKey())) {
                highestSeverity = attribute.getStrValue().orElse(null);
            }
        }
        return new CurrentSummary(activeAlarmCount, highestSeverity);
    }

    private void scheduleWorker() {
        if (workerRunning.compareAndSet(false, true)) {
            worker.execute(this::drainPendingRefreshes);
        }
    }

    private void drainPendingRefreshes() {
        try {
            do {
                List<DeviceContext> batch = List.copyOf(pendingRefreshes);
                pendingRefreshes.removeAll(batch);
                for (DeviceContext context : batch) {
                    try {
                        refreshNow(context.tenantId(), context.deviceId());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception e) {
                        log.warn("[{}][{}] Failed to refresh WAN device alarm summary",
                                context.tenantId(), context.deviceId(), e);
                    }
                }
            } while (!pendingRefreshes.isEmpty());
        } finally {
            workerRunning.set(false);
            if (!pendingRefreshes.isEmpty()) {
                scheduleWorker();
            }
        }
    }

    private record DeviceContext(TenantId tenantId, DeviceId deviceId) {
    }

    private record CurrentSummary(Long activeAlarmCount, String highestSeverity) {
    }

}
