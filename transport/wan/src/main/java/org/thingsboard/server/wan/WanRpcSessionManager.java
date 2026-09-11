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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.transport.SessionMsgListener;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.gen.transport.TransportProtos;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "transport.wan", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WanRpcSessionManager {

    private final WanDeviceRouteRegistry deviceRouteRegistry;
    private final WanSessionInfoFactory sessionInfoFactory;
    private final WanDownlinkService downlinkService;
    private final TransportService transportService;
    private final ConcurrentMap<UUID, RegisteredSession> sessions = new ConcurrentHashMap<>();
    private final Runnable routeChangeListener = this::refresh;

    @PostConstruct
    public void start() {
        deviceRouteRegistry.addChangeListener(routeChangeListener);
        refresh();
    }

    @Scheduled(initialDelayString = "${transport.wan.rpc_session_refresh_interval_ms:30000}",
            fixedDelayString = "${transport.wan.rpc_session_refresh_interval_ms:30000}")
    public synchronized void refresh() {
        Map<UUID, WanDeviceDescriptor> desired = new HashMap<>();
        deviceRouteRegistry.activeDevices().forEach(device -> desired.putIfAbsent(device.deviceId(), device));
        sessions.entrySet().removeIf(entry -> {
            WanDeviceDescriptor next = desired.get(entry.getKey());
            if (!entry.getValue().failed().get()
                    && next != null && sameSession(entry.getValue().device(), next)) {
                return false;
            }
            close(entry.getValue());
            return true;
        });
        desired.forEach((deviceId, device) -> sessions.computeIfAbsent(deviceId, ignored -> register(device)));
    }

    @PreDestroy
    public synchronized void stop() {
        deviceRouteRegistry.removeChangeListener(routeChangeListener);
        sessions.values().forEach(this::close);
        sessions.clear();
    }

    int registeredSessionCount() {
        return sessions.size();
    }

    public int readySessionCount() {
        return (int) sessions.values().stream().filter(session -> session.ready().get()).count();
    }

    private RegisteredSession register(WanDeviceDescriptor device) {
        TransportProtos.SessionInfoProto sessionInfo = null;
        try {
            sessionInfo = sessionInfoFactory.create(device, UUID.randomUUID());
            WanSessionListener listener = new WanSessionListener(device, sessionInfo);
            RegisteredSession session = new RegisteredSession(
                    device, sessionInfo, new AtomicBoolean(), new AtomicBoolean());
            transportService.registerAsyncSession(sessionInfo, listener);
            transportService.process(sessionInfo, TransportProtos.SubscribeToRPCMsg.newBuilder()
                    .setSessionType(TransportProtos.SessionType.ASYNC)
                    .build(), false, new TransportServiceCallback<>() {
                        @Override
                        public void onSuccess(Void ignored) {
                            session.ready().set(true);
                        }

                        @Override
                        public void onError(Throwable error) {
                            session.failed().set(true);
                            log.warn("Unable to subscribe WAN RPC session for device [{}]",
                                    device.deviceId(), error);
                        }
                    });
            return session;
        } catch (RuntimeException e) {
            log.warn("Unable to register WAN RPC session for device [{}]", device.deviceId(), e);
            if (sessionInfo != null) {
                try {
                    transportService.deregisterSession(sessionInfo);
                } catch (RuntimeException cleanupError) {
                    log.debug("Unable to clean up failed WAN RPC session for device [{}]",
                            device.deviceId(), cleanupError);
                }
            }
            return null;
        }
    }

    private void close(RegisteredSession session) {
        try {
            transportService.process(session.sessionInfo(), TransportProtos.SubscribeToRPCMsg.newBuilder()
                    .setSessionType(TransportProtos.SessionType.ASYNC)
                    .setUnsubscribe(true)
                    .build(), false, TransportServiceCallback.EMPTY);
        } catch (RuntimeException e) {
            log.debug("Unable to unsubscribe WAN RPC session for device [{}]", session.device().deviceId(), e);
        }
        try {
            transportService.deregisterSession(session.sessionInfo());
        } catch (RuntimeException e) {
            log.debug("Unable to deregister WAN RPC session for device [{}]", session.device().deviceId(), e);
        }
    }

    private boolean sameSession(WanDeviceDescriptor first, WanDeviceDescriptor second) {
        return Objects.equals(first.deviceId(), second.deviceId())
                && Objects.equals(first.tenantId(), second.tenantId())
                && Objects.equals(first.customerId(), second.customerId())
                && Objects.equals(first.deviceProfileId(), second.deviceProfileId())
                && Objects.equals(first.connectionId(), second.connectionId())
                && Objects.equals(first.deviceName(), second.deviceName())
                && Objects.equals(first.deviceType(), second.deviceType())
                && first.gateway() == second.gateway()
                && first.wanDeviceType() == second.wanDeviceType()
                && Objects.equals(first.externalId(), second.externalId());
    }

    private void removeSession(UUID deviceId, UUID sessionId) {
        RegisteredSession current = sessions.get(deviceId);
        if (current == null) {
            return;
        }
        UUID currentSessionId = new UUID(current.sessionInfo().getSessionIdMSB(),
                current.sessionInfo().getSessionIdLSB());
        if (currentSessionId.equals(sessionId) && sessions.remove(deviceId, current)) {
            try {
                transportService.deregisterSession(current.sessionInfo());
            } catch (RuntimeException e) {
                log.debug("Unable to deregister remotely closed WAN RPC session for device [{}]", deviceId, e);
            }
        }
    }

    private record RegisteredSession(WanDeviceDescriptor device,
                                     TransportProtos.SessionInfoProto sessionInfo,
                                     AtomicBoolean ready,
                                     AtomicBoolean failed) {
    }

    private class WanSessionListener implements SessionMsgListener {

        private final WanDeviceDescriptor device;
        private final TransportProtos.SessionInfoProto sessionInfo;

        private WanSessionListener(WanDeviceDescriptor device,
                                   TransportProtos.SessionInfoProto sessionInfo) {
            this.device = device;
            this.sessionInfo = sessionInfo;
        }

        @Override
        public void onToDeviceRpcRequest(UUID sessionId,
                                         TransportProtos.ToDeviceRpcRequestMsg request) {
            downlinkService.handle(device, sessionInfo, request);
        }

        @Override
        public void onRemoteSessionCloseCommand(UUID sessionId,
                                                TransportProtos.SessionCloseNotificationProto notification) {
            removeSession(device.deviceId(), sessionId);
        }

        @Override
        public void onDeviceDeleted(DeviceId deviceId) {
            RegisteredSession removed = sessions.remove(deviceId.getId());
            if (removed != null) {
                close(removed);
            }
        }

        @Override
        public void onGetAttributesResponse(TransportProtos.GetAttributeResponseMsg response) {
        }

        @Override
        public void onAttributeUpdate(UUID sessionId,
                                      TransportProtos.AttributeUpdateNotificationMsg notification) {
        }

        @Override
        public void onToServerRpcResponse(TransportProtos.ToServerRpcResponseMsg response) {
        }
    }

}
