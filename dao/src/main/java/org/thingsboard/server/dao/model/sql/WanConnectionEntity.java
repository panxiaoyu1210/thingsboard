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
package org.thingsboard.server.dao.model.sql;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.wan.WanConnection;
import org.thingsboard.server.dao.model.ToData;

import java.util.UUID;

import static org.thingsboard.server.dao.model.ModelConstants.CREATED_TIME_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.ID_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.NAME_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.TENANT_ID_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.VERSION_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_BROKER_HOST_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_BROKER_PORT_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_CLIENT_ID_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_ENABLED_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_NS_PUBLISH_TOPIC_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_NS_SUBSCRIBE_TOPIC_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_OWNERSHIP_OWNER_ID_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_OWNERSHIP_UNTIL_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_PASSWORD_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_QOS_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_REQUEST_TIMEOUT_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_SYNC_ENABLED_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_SYNC_INTERVAL_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_TABLE_NAME;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_TLS_COLUMN;
import static org.thingsboard.server.dao.model.ModelConstants.WAN_CONNECTION_USERNAME_COLUMN;

@Data
@NoArgsConstructor
@ToString(exclude = "encryptedPassword")
@Entity
@Table(name = WAN_CONNECTION_TABLE_NAME)
public class WanConnectionEntity implements ToData<WanConnection> {

    @Id
    @Column(name = ID_PROPERTY, columnDefinition = "uuid")
    private UUID id;

    @Column(name = CREATED_TIME_PROPERTY, updatable = false)
    private long createdTime;

    @Column(name = TENANT_ID_COLUMN, nullable = false, columnDefinition = "uuid")
    private UUID tenantId;

    @Column(name = NAME_PROPERTY, nullable = false)
    private String name;

    @Column(name = WAN_CONNECTION_BROKER_HOST_COLUMN, nullable = false)
    private String brokerHost;

    @Column(name = WAN_CONNECTION_BROKER_PORT_COLUMN, nullable = false)
    private int brokerPort;

    @Column(name = WAN_CONNECTION_TLS_COLUMN, nullable = false)
    private boolean tls;

    @Column(name = WAN_CONNECTION_CLIENT_ID_COLUMN, nullable = false)
    private String clientId;

    @Column(name = WAN_CONNECTION_USERNAME_COLUMN)
    private String username;

    @Column(name = WAN_CONNECTION_PASSWORD_COLUMN, length = 4096)
    private String encryptedPassword;

    @Column(name = WAN_CONNECTION_NS_PUBLISH_TOPIC_COLUMN, nullable = false)
    private String nsPublishTopic;

    @Column(name = WAN_CONNECTION_NS_SUBSCRIBE_TOPIC_COLUMN, nullable = false)
    private String nsSubscribeTopic;

    @Column(name = WAN_CONNECTION_QOS_COLUMN, nullable = false)
    private int qos;

    @Column(name = WAN_CONNECTION_ENABLED_COLUMN, nullable = false)
    private boolean enabled;

    @Column(name = WAN_CONNECTION_REQUEST_TIMEOUT_COLUMN, nullable = false)
    private int requestTimeoutMs;

    @Column(name = WAN_CONNECTION_SYNC_ENABLED_COLUMN, nullable = false)
    private boolean syncEnabled;

    @Column(name = WAN_CONNECTION_SYNC_INTERVAL_COLUMN, nullable = false)
    private int syncIntervalHours;

    @Column(name = WAN_CONNECTION_OWNERSHIP_OWNER_ID_COLUMN, insertable = false, updatable = false)
    private String ownershipOwnerId;

    @Column(name = WAN_CONNECTION_OWNERSHIP_UNTIL_COLUMN, insertable = false, updatable = false)
    private Long ownershipUntil;

    @Version
    @Column(name = VERSION_PROPERTY)
    private Long version;

    public WanConnectionEntity(WanConnection connection) {
        this.id = connection.getId();
        this.createdTime = connection.getCreatedTime();
        this.tenantId = connection.getTenantId().getId();
        this.name = connection.getName();
        this.brokerHost = connection.getBrokerHost();
        this.brokerPort = connection.getBrokerPort();
        this.tls = connection.isTls();
        this.clientId = connection.getClientId();
        this.username = connection.getUsername();
        this.encryptedPassword = connection.getEncryptedPassword();
        this.nsPublishTopic = connection.getNsPublishTopic();
        this.nsSubscribeTopic = connection.getNsSubscribeTopic();
        this.qos = connection.getQos();
        this.enabled = connection.isEnabled();
        this.requestTimeoutMs = connection.getRequestTimeoutMs();
        this.syncEnabled = connection.isSyncEnabled();
        this.syncIntervalHours = connection.getSyncIntervalHours();
        this.ownershipOwnerId = connection.getOwnershipOwnerId();
        this.ownershipUntil = connection.getOwnershipUntil();
        this.version = connection.getVersion();
    }

    @Override
    public WanConnection toData() {
        WanConnection connection = new WanConnection();
        connection.setId(id);
        connection.setCreatedTime(createdTime);
        connection.setTenantId(TenantId.fromUUID(tenantId));
        connection.setName(name);
        connection.setBrokerHost(brokerHost);
        connection.setBrokerPort(brokerPort);
        connection.setTls(tls);
        connection.setClientId(clientId);
        connection.setUsername(username);
        connection.setEncryptedPassword(encryptedPassword);
        connection.setPasswordSet(encryptedPassword != null);
        connection.setNsPublishTopic(nsPublishTopic);
        connection.setNsSubscribeTopic(nsSubscribeTopic);
        connection.setQos(qos);
        connection.setEnabled(enabled);
        connection.setRequestTimeoutMs(requestTimeoutMs);
        connection.setSyncEnabled(syncEnabled);
        connection.setSyncIntervalHours(syncIntervalHours);
        connection.setOwnershipOwnerId(ownershipOwnerId);
        connection.setOwnershipUntil(ownershipUntil);
        connection.setVersion(version);
        return connection;
    }

}
