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
package org.thingsboard.server.wan.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.thingsboard.common.util.JacksonUtil;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 仅用于本地黑盒验收的轻量 TurMass WAN NS。它实现验收所需的 MQTT 3.1.1 子集，
 * 不应作为生产 MQTT Broker 或 NS 使用。
 */
public final class WanMockNsApplication {

    private WanMockNsApplication() {
    }

    public static void main(String[] args) throws Exception {
        int mqttPort = environmentInt("MOCK_NS_MQTT_PORT", 1883);
        int httpPort = environmentInt("MOCK_NS_HTTP_PORT", 8080);
        String requestTopic = environment("MOCK_NS_REQUEST_TOPIC", "turmass/ns/requests");
        String responseTopic = environment("MOCK_NS_RESPONSE_TOPIC", "turmass/ns/responses");
        MockNsState state = new MockNsState();
        MockMqttBroker broker = new MockMqttBroker(mqttPort, requestTopic, responseTopic, state);
        MockHttpApi httpApi = new MockHttpApi(httpPort, responseTopic, state, broker);
        broker.start();
        httpApi.start();
        structuredLog("mock_ns_started", "mqttPort", mqttPort, "httpPort", httpPort);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            httpApi.close();
            broker.close();
        }));
        new CountDownLatch(1).await();
    }

    private static int environmentInt(String name, int defaultValue) {
        return Integer.parseInt(environment(name, Integer.toString(defaultValue)));
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static void structuredLog(String event, Object... fields) {
        ObjectNode value = JacksonUtil.newObjectNode().put("event", event);
        for (int index = 0; index + 1 < fields.length; index += 2) {
            String name = fields[index].toString();
            Object field = fields[index + 1];
            if (field instanceof Number number) {
                value.put(name, number.longValue());
            } else {
                value.put(name, String.valueOf(field));
            }
        }
        System.out.println(JacksonUtil.toString(value));
    }

    static final class MockNsState {

        private final Map<String, ObjectNode> gateways = new LinkedHashMap<>();
        private final Map<String, ObjectNode> terminals = new LinkedHashMap<>();
        private final List<ObjectNode> downlinks = new ArrayList<>();
        private final Map<String, Long> operationCounts = new LinkedHashMap<>();
        private long receivedRequests;
        private int uplinkSequence;

        synchronized void reset() {
            gateways.clear();
            terminals.clear();
            downlinks.clear();
            operationCounts.clear();
            receivedRequests = 0;
            uplinkSequence = 0;
        }

        synchronized void seed(JsonNode payload) {
            addSeed(payload.path("gateways"), gateways, "gw_id", false);
            addSeed(payload.path("terminals"), terminals, "dev_eui", true);
        }

        synchronized ObjectNode process(JsonNode request) {
            JsonNode requestId = request.get("req_id");
            JsonNode operationNode = request.get("req_opt");
            if (requestId == null || !requestId.isInt() || operationNode == null || !operationNode.isTextual()) {
                return null;
            }
            int id = requestId.intValue();
            String operation = operationNode.textValue();
            JsonNode body = request.path("req_body");
            receivedRequests++;
            operationCounts.merge(operation, 1L, Long::sum);
            structuredLog("ns_request", "operation", operation, "requestId", id);
            return switch (operation) {
                case "get_gateway" -> query(id, operation, body.path("gw_ids"), gateways, "网关查询成功");
                case "add_gateway" -> add(id, operation, body, gateways, "gw_id", false,
                        "网关添加成功", "网关参数无效");
                case "delete_gateway" -> delete(id, operation, body.path("gw_ids"), gateways, "网关删除成功");
                case "get_terminal" -> query(id, operation, body.path("dev_euis"), terminals, "终端查询成功");
                case "add_terminal" -> add(id, operation, body, terminals, "dev_eui", true,
                        "终端添加成功", "终端参数无效");
                case "delete_terminal" -> delete(id, operation, body.path("dev_euis"), terminals, "终端删除成功");
                case "push_downlink", "push_broadcast" -> {
                    ObjectNode downlink = JacksonUtil.newObjectNode().put("operation", operation);
                    downlink.set("body", body.deepCopy());
                    downlinks.add(downlink);
                    yield null;
                }
                default -> response(id, operation, 404, "不支持的操作", JacksonUtil.newArrayNode());
            };
        }

        synchronized ObjectNode nextUplink(JsonNode body) {
            ObjectNode result = JacksonUtil.newObjectNode();
            result.put("req_id", 1_000_000 + ++uplinkSequence);
            result.put("req_opt", "push_uplink");
            result.set("req_body", body.deepCopy());
            return result;
        }

        synchronized ObjectNode snapshot(int connectedClients, int responseSubscribers) {
            ObjectNode result = JacksonUtil.newObjectNode();
            ArrayNode gatewayArray = result.putArray("gateways");
            gateways.values().forEach(value -> gatewayArray.add(value.deepCopy()));
            ArrayNode terminalArray = result.putArray("terminals");
            terminals.values().forEach(value -> {
                ObjectNode redacted = value.deepCopy();
                redacted.remove("root_key");
                terminalArray.add(redacted);
            });
            ArrayNode downlinkArray = result.putArray("downlinks");
            downlinks.forEach(value -> downlinkArray.add(value.deepCopy()));
            ObjectNode counts = result.putObject("operationCounts");
            operationCounts.forEach(counts::put);
            result.put("receivedRequests", receivedRequests);
            result.put("connectedClients", connectedClients);
            result.put("responseSubscribers", responseSubscribers);
            return result;
        }

        private void addSeed(JsonNode array, Map<String, ObjectNode> target, String idField, boolean terminal) {
            if (!array.isArray()) {
                return;
            }
            array.forEach(item -> {
                if (item.isObject() && item.path(idField).isTextual()) {
                    ObjectNode normalized = item.deepCopy();
                    String id = item.path(idField).asText().toUpperCase();
                    normalized.put(idField, id);
                    if (terminal) {
                        normalizeTerminal(normalized);
                    }
                    target.put(id, normalized);
                }
            });
        }

        private ObjectNode query(int requestId, String operation, JsonNode ids,
                                 Map<String, ObjectNode> source, String description) {
            ArrayNode body = JacksonUtil.newArrayNode();
            if (ids.isArray()) {
                ids.forEach(value -> {
                    ObjectNode device = source.get(value.asText().toUpperCase());
                    if (device != null) {
                        body.add(device.deepCopy());
                    }
                });
            }
            return response(requestId, operation, 0, description, body);
        }

        private ObjectNode add(int requestId, String operation, JsonNode items,
                               Map<String, ObjectNode> target, String idField, boolean terminal,
                               String successDescription, String failureDescription) {
            ArrayNode codes = JacksonUtil.newArrayNode();
            ArrayNode descriptions = JacksonUtil.newArrayNode();
            if (items.isArray()) {
                items.forEach(item -> {
                    if (item.isObject() && item.path(idField).isTextual()) {
                        ObjectNode normalized = item.deepCopy();
                        String id = item.path(idField).asText().toUpperCase();
                        normalized.put(idField, id);
                        if (terminal) {
                            normalizeTerminal(normalized);
                        }
                        target.put(id, normalized);
                        codes.add(0);
                        descriptions.add(successDescription);
                    } else {
                        codes.add(1);
                        descriptions.add(failureDescription);
                    }
                });
            }
            return response(requestId, operation, codes, descriptions, JacksonUtil.newArrayNode());
        }

        private ObjectNode delete(int requestId, String operation, JsonNode ids,
                                  Map<String, ObjectNode> target, String description) {
            if (ids.isArray()) {
                ids.forEach(value -> target.remove(value.asText().toUpperCase()));
            }
            return response(requestId, operation, 0, description, JacksonUtil.newArrayNode());
        }

        private void normalizeTerminal(ObjectNode terminal) {
            putIfAbsent(terminal, "dev_type", 0);
            putIfAbsent(terminal, "addr_mode", 0);
            putIfAbsent(terminal, "nwk_id", "0001");
            putIfAbsent(terminal, "nwk_addr", "0001");
            putIfAbsent(terminal, "security_mode", 0);
            putIfAbsent(terminal, "root_key", "");
            putIfAbsent(terminal, "related_id", "");
        }

        private void putIfAbsent(ObjectNode node, String field, int value) {
            if (!node.has(field)) {
                node.put(field, value);
            }
        }

        private void putIfAbsent(ObjectNode node, String field, String value) {
            if (!node.has(field)) {
                node.put(field, value);
            }
        }

        private ObjectNode response(int requestId, String operation, Object code,
                                    Object description, JsonNode body) {
            ObjectNode result = JacksonUtil.newObjectNode();
            result.put("req_id", requestId);
            result.put("req_opt", operation);
            if (code instanceof JsonNode node) {
                result.set("rsp_code", node);
            } else {
                result.put("rsp_code", ((Number) code).intValue());
            }
            if (description instanceof JsonNode node) {
                result.set("rsp_desc", node);
            } else {
                result.put("rsp_desc", description.toString());
            }
            result.set("rsp_body", body);
            return result;
        }
    }

    static final class MockMqttBroker implements Closeable {

        private final int port;
        private final String requestTopic;
        private final String responseTopic;
        private final MockNsState state;
        private final Set<MqttSession> sessions = ConcurrentHashMap.newKeySet();
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicInteger packetIds = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private ServerSocket serverSocket;

        MockMqttBroker(int port, String requestTopic, String responseTopic, MockNsState state) {
            this.port = port;
            this.requestTopic = requestTopic;
            this.responseTopic = responseTopic;
            this.state = state;
        }

        void start() throws IOException {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
            executor.submit(this::acceptLoop);
        }

        int publish(String topic, JsonNode message, int qos) {
            byte[] payload = JacksonUtil.toString(message).getBytes(StandardCharsets.UTF_8);
            int delivered = 0;
            for (MqttSession session : sessions) {
                if (!session.subscriptions.contains(topic)) {
                    continue;
                }
                try {
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    writeUtf8(body, topic);
                    if (qos > 0) {
                        writeUnsignedShort(body, nextPacketId());
                    }
                    body.write(payload);
                    session.send(0x30 | qos << 1, body.toByteArray());
                    delivered++;
                } catch (IOException ignored) {
                }
            }
            return delivered;
        }

        int connectedCount() {
            return sessions.size();
        }

        int responseSubscriberCount() {
            return (int) sessions.stream().filter(session -> session.subscriptions.contains(responseTopic)).count();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closeQuietly(serverSocket);
            sessions.forEach(MqttSession::close);
            executor.shutdownNow();
        }

        private void acceptLoop() {
            while (!closed.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setTcpNoDelay(true);
                    MqttSession session = new MqttSession(socket);
                    sessions.add(session);
                    executor.submit(() -> handle(session));
                } catch (IOException e) {
                    if (!closed.get()) {
                        structuredLog("mqtt_accept_failed", "reason", e.getClass().getSimpleName());
                    }
                }
            }
        }

        private void handle(MqttSession session) {
            structuredLog("mqtt_client_connected", "remote", session.remoteAddress());
            try (DataInputStream input = new DataInputStream(session.socket.getInputStream())) {
                while (!closed.get()) {
                    int first = input.readUnsignedByte();
                    int remainingLength = readRemainingLength(input);
                    byte[] payload = input.readNBytes(remainingLength);
                    if (payload.length != remainingLength) {
                        throw new IOException("truncated MQTT packet");
                    }
                    switch (first >> 4) {
                        case 1 -> session.send(0x20, new byte[]{0, 0});
                        case 3 -> handlePublish(session, first, payload);
                        case 8 -> handleSubscribe(session, payload);
                        case 12 -> session.send(0xD0, new byte[0]);
                        case 14 -> {
                            return;
                        }
                        default -> {
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            } finally {
                sessions.remove(session);
                session.close();
                structuredLog("mqtt_client_disconnected", "remote", session.remoteAddress());
            }
        }

        private void handleSubscribe(MqttSession session, byte[] payload) throws IOException {
            Cursor cursor = new Cursor(payload);
            int packetId = cursor.readUnsignedShort();
            ByteArrayOutputStream granted = new ByteArrayOutputStream();
            while (cursor.hasRemaining()) {
                session.subscriptions.add(cursor.readUtf8());
                granted.write(Math.min(cursor.readUnsignedByte(), 1));
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeUnsignedShort(body, packetId);
            body.write(granted.toByteArray());
            session.send(0x90, body.toByteArray());
        }

        private void handlePublish(MqttSession session, int first, byte[] payload) throws IOException {
            Cursor cursor = new Cursor(payload);
            String topic = cursor.readUtf8();
            int qos = first >> 1 & 0x03;
            if (qos == 1) {
                int packetId = cursor.readUnsignedShort();
                ByteArrayOutputStream ack = new ByteArrayOutputStream();
                writeUnsignedShort(ack, packetId);
                session.send(0x40, ack.toByteArray());
            } else if (qos > 1) {
                cursor.readUnsignedShort();
            }
            JsonNode message;
            try {
                message = JacksonUtil.fromBytes(cursor.remainingBytes());
            } catch (RuntimeException e) {
                structuredLog("mqtt_message_rejected", "topic", topic, "reason", "invalid_json");
                return;
            }
            if (requestTopic.equals(topic)) {
                ObjectNode response = state.process(message);
                if (response != null) {
                    publish(responseTopic, response, 1);
                }
            }
        }

        private int nextPacketId() {
            return packetIds.updateAndGet(current -> current >= 65_535 ? 1 : current + 1);
        }
    }

    static final class MqttSession implements Closeable {

        private final Socket socket;
        private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();

        MqttSession(Socket socket) {
            this.socket = socket;
        }

        synchronized void send(int first, byte[] payload) throws IOException {
            OutputStream output = socket.getOutputStream();
            output.write(first);
            writeRemainingLength(output, payload.length);
            output.write(payload);
            output.flush();
        }

        String remoteAddress() {
            return String.valueOf(socket.getRemoteSocketAddress());
        }

        @Override
        public void close() {
            closeQuietly(socket);
        }
    }

    static final class MockHttpApi implements Closeable {

        private final int port;
        private final String responseTopic;
        private final MockNsState state;
        private final MockMqttBroker broker;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private HttpServer server;

        MockHttpApi(int port, String responseTopic, MockNsState state, MockMqttBroker broker) {
            this.port = port;
            this.responseTopic = responseTopic;
            this.state = state;
            this.broker = broker;
        }

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        @Override
        public void close() {
            if (server != null) {
                server.stop(0);
            }
            executor.shutdownNow();
        }

        private void handle(HttpExchange exchange) throws IOException {
            int status = 404;
            ObjectNode response = JacksonUtil.newObjectNode().put("error", "not found");
            try {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                long contentLength = Long.parseLong(exchange.getRequestHeaders()
                        .getFirst("Content-Length") == null ? "0" : exchange.getRequestHeaders().getFirst("Content-Length"));
                if (contentLength > 1_048_576L) {
                    throw new IllegalArgumentException("request body exceeds local mock limit");
                }
                byte[] requestBytes = exchange.getRequestBody().readAllBytes();
                JsonNode body = requestBytes.length == 0
                        ? JacksonUtil.newObjectNode() : JacksonUtil.fromBytes(requestBytes);
                if ("GET".equals(method) && "/health".equals(path)) {
                    status = 200;
                    response = JacksonUtil.newObjectNode().put("status", "UP");
                } else if ("GET".equals(method) && "/state".equals(path)) {
                    status = 200;
                    response = state.snapshot(broker.connectedCount(), broker.responseSubscriberCount());
                } else if ("POST".equals(method) && "/reset".equals(path)) {
                    state.reset();
                    status = 200;
                    response = JacksonUtil.newObjectNode().put("status", "reset");
                } else if ("POST".equals(method) && "/seed".equals(path) && body.isObject()) {
                    state.seed(body);
                    status = 200;
                    response = JacksonUtil.newObjectNode().put("status", "seeded");
                } else if ("POST".equals(method) && "/uplink".equals(path) && validUplink(body)) {
                    ObjectNode uplink = state.nextUplink(body);
                    int delivered = broker.publish(responseTopic, uplink, 1);
                    status = 202;
                    response = JacksonUtil.newObjectNode().put("status", "published")
                            .put("subscribers", delivered)
                            .put("req_id", uplink.path("req_id").asInt());
                } else if ("POST".equals(method) && "/uplink".equals(path)) {
                    status = 400;
                    response = JacksonUtil.newObjectNode().put("error", "missing uplink field");
                }
            } catch (RuntimeException e) {
                status = 400;
                response = JacksonUtil.newObjectNode().put("error", "invalid request");
            }
            byte[] payload = JacksonUtil.toString(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        }

        private boolean validUplink(JsonNode body) {
            return body.isObject() && body.has("dev_eui") && body.has("rssi") && body.has("snr")
                    && body.has("port") && body.has("data");
        }
    }

    static final class Cursor {

        private final byte[] value;
        private int offset;

        Cursor(byte[] value) {
            this.value = value;
        }

        boolean hasRemaining() {
            return offset < value.length;
        }

        int readUnsignedByte() throws IOException {
            if (!hasRemaining()) {
                throw new IOException("truncated MQTT packet");
            }
            return value[offset++] & 0xFF;
        }

        int readUnsignedShort() throws IOException {
            return readUnsignedByte() << 8 | readUnsignedByte();
        }

        String readUtf8() throws IOException {
            int length = readUnsignedShort();
            if (offset + length > value.length) {
                throw new IOException("truncated MQTT UTF-8 value");
            }
            String result = new String(value, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return result;
        }

        byte[] remainingBytes() {
            return java.util.Arrays.copyOfRange(value, offset, value.length);
        }
    }

    private static int readRemainingLength(DataInputStream input) throws IOException {
        int multiplier = 1;
        int value = 0;
        for (int index = 0; index < 4; index++) {
            int digit = input.readUnsignedByte();
            value += (digit & 0x7F) * multiplier;
            if ((digit & 0x80) == 0) {
                if (value > 1_048_576) {
                    throw new IOException("MQTT packet exceeds local mock limit");
                }
                return value;
            }
            multiplier *= 128;
        }
        throw new IOException("invalid MQTT remaining length");
    }

    private static void writeRemainingLength(OutputStream output, int length) throws IOException {
        do {
            int digit = length % 128;
            length /= 128;
            if (length > 0) {
                digit |= 0x80;
            }
            output.write(digit);
        } while (length > 0);
    }

    private static void writeUtf8(OutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeUnsignedShort(output, bytes.length);
        output.write(bytes);
    }

    private static void writeUnsignedShort(OutputStream output, int value) throws IOException {
        output.write(value >> 8 & 0xFF);
        output.write(value & 0xFF);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
