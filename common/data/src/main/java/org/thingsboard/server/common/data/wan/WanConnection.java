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
package org.thingsboard.server.common.data.wan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;
import org.thingsboard.server.common.data.HasTenantId;
import org.thingsboard.server.common.data.HasVersion;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.validation.NoXss;

import java.io.Serial;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

@Schema
@Data
@ToString(exclude = {"password", "encryptedPassword"})
public class WanConnection implements HasTenantId, HasVersion, Serializable {

    @Serial
    private static final long serialVersionUID = 5780772371678104377L;

    public static final String PASSWORD_MASK = "********";
    public static final int DEFAULT_BROKER_PORT = 1883;
    public static final int DEFAULT_QOS = 1;
    public static final int DEFAULT_REQUEST_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_SYNC_INTERVAL_HOURS = 24;

    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private UUID id;
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private long createdTime;
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private TenantId tenantId;
    @NoXss
    private String name;
    @NoXss
    private String brokerHost;
    private int brokerPort = DEFAULT_BROKER_PORT;
    private boolean tls;
    @NoXss
    private String clientId;
    @NoXss
    private String username;
    @Schema(description = "New broker password. Responses contain only a fixed mask when a password is configured.")
    private String password;
    @NoXss
    private String nsPublishTopic;
    @NoXss
    private String nsSubscribeTopic;
    private int qos = DEFAULT_QOS;
    private boolean enabled = true;
    private int requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS;
    private boolean syncEnabled = true;
    private int syncIntervalHours = DEFAULT_SYNC_INTERVAL_HOURS;
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private boolean passwordSet;
    private Long version;

    @JsonIgnore
    private String encryptedPassword;
    @JsonIgnore
    private String ownershipOwnerId;
    @JsonIgnore
    private Long ownershipUntil;

    public WanConnection() {
    }

    public WanConnection(WanConnection connection) {
        this.id = connection.id;
        this.createdTime = connection.createdTime;
        this.tenantId = connection.tenantId;
        this.name = connection.name;
        this.brokerHost = connection.brokerHost;
        this.brokerPort = connection.brokerPort;
        this.tls = connection.tls;
        this.clientId = connection.clientId;
        this.username = connection.username;
        this.password = connection.password;
        this.nsPublishTopic = connection.nsPublishTopic;
        this.nsSubscribeTopic = connection.nsSubscribeTopic;
        this.qos = connection.qos;
        this.enabled = connection.enabled;
        this.requestTimeoutMs = connection.requestTimeoutMs;
        this.syncEnabled = connection.syncEnabled;
        this.syncIntervalHours = connection.syncIntervalHours;
        this.passwordSet = connection.passwordSet;
        this.encryptedPassword = connection.encryptedPassword;
        this.ownershipOwnerId = connection.ownershipOwnerId;
        this.ownershipUntil = connection.ownershipUntil;
        this.version = connection.version;
    }

    public void validate() {
        requireText(name, "WAN connection name");
        requireText(brokerHost, "WAN broker host");
        requireText(clientId, "WAN client id");
        requireText(nsPublishTopic, "WAN NS publish topic");
        requireText(nsSubscribeTopic, "WAN NS subscribe topic");
        if (brokerPort < 1 || brokerPort > 65_535) {
            throw new IllegalArgumentException("WAN broker port must be between 1 and 65535");
        }
        validateLength(name, "WAN connection name");
        validateLength(brokerHost, "WAN broker host");
        validateLength(clientId, "WAN client id");
        validateLength(username, "WAN username");
        validateLength(nsPublishTopic, "WAN NS publish topic");
        validateLength(nsSubscribeTopic, "WAN NS subscribe topic");
        validateBrokerHost();
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("WAN QoS must be between 0 and 2");
        }
        if (requestTimeoutMs < 1_000 || requestTimeoutMs > 120_000) {
            throw new IllegalArgumentException("WAN request timeout must be between 1000 and 120000 milliseconds");
        }
        if (syncIntervalHours < 1 || syncIntervalHours > 8_760) {
            throw new IllegalArgumentException("WAN sync interval must be between 1 and 8760 hours");
        }
        if (containsControlCharacter(name) || containsControlCharacter(clientId)
                || containsControlCharacter(username) || containsControlCharacter(nsPublishTopic)
                || containsControlCharacter(nsSubscribeTopic)) {
            throw new IllegalArgumentException("WAN connection text fields must not contain control characters");
        }
        if (nsSubscribeTopic.contains("#") || nsSubscribeTopic.contains("+")) {
            throw new IllegalArgumentException("WAN NS subscribe topic must be a publishable MQTT topic without wildcards");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(fieldName + " must be specified");
        }
    }

    private static void validateLength(String value, String fieldName) {
        if (value != null && value.length() > 255) {
            throw new IllegalArgumentException(fieldName + " must not exceed 255 characters");
        }
    }

    private void validateBrokerHost() {
        try {
            String formattedHost = brokerHost.contains(":") && !brokerHost.startsWith("[")
                    ? "[" + brokerHost + "]"
                    : brokerHost;
            URI uri = new URI("mqtt://" + formattedHost + ":" + brokerPort);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || !uri.getPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("WAN broker host is invalid");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("WAN broker host is invalid", e);
        }
    }

    private static boolean containsControlCharacter(String value) {
        if (value == null) {
            return false;
        }
        return value.chars().anyMatch(Character::isISOControl);
    }

}
