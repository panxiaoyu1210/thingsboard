--
-- Copyright © 2016-2026 The Thingsboard Authors
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

-- 4.4 baseline flat DDL, applied by V4_4_0_0Migration during a 4.3.x -> 4.4 offline upgrade. Keep these
-- idempotent (ALTER ... IF NOT EXISTS): the offline path records the package version once at the end, so a
-- resumed upgrade re-runs this whole file.

-- RULE CHAIN NOTES MIGRATION START

ALTER TABLE rule_chain ADD COLUMN IF NOT EXISTS notes varchar(1000000);

-- RULE CHAIN NOTES MIGRATION END

-- WAN CONNECTION MIGRATION START

CREATE TABLE IF NOT EXISTS wan_connection (
    id                  UUID          NOT NULL PRIMARY KEY,
    created_time        BIGINT        NOT NULL,
    tenant_id           UUID          NOT NULL,
    name                VARCHAR(255)  NOT NULL,
    broker_host         VARCHAR(255)  NOT NULL,
    broker_port         INTEGER       NOT NULL,
    tls                 BOOLEAN       NOT NULL,
    client_id           VARCHAR(255)  NOT NULL,
    username            VARCHAR(255),
    encrypted_password  VARCHAR(4096),
    ns_publish_topic    VARCHAR(255)  NOT NULL,
    ns_subscribe_topic  VARCHAR(255)  NOT NULL,
    qos                 INTEGER       NOT NULL,
    enabled             BOOLEAN       NOT NULL,
    request_timeout_ms  INTEGER       NOT NULL,
    sync_enabled        BOOLEAN       NOT NULL DEFAULT TRUE,
    sync_interval_hours INTEGER       NOT NULL,
    ownership_owner_id  VARCHAR(255),
    ownership_until     BIGINT,
    version             BIGINT        NOT NULL DEFAULT 1,
    CONSTRAINT wan_connection_name_unq_key UNIQUE (tenant_id, name),
    CONSTRAINT fk_wan_connection_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_wan_connection_tenant_id ON wan_connection(tenant_id);
ALTER TABLE wan_connection ADD COLUMN IF NOT EXISTS sync_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE wan_connection ADD COLUMN IF NOT EXISTS ownership_owner_id VARCHAR(255);
ALTER TABLE wan_connection ADD COLUMN IF NOT EXISTS ownership_until BIGINT;
CREATE INDEX IF NOT EXISTS idx_wan_connection_ownership
    ON wan_connection(enabled, ownership_owner_id, ownership_until);

-- WAN CONNECTION MIGRATION END

-- WAN DEVICE REGISTRY MIGRATION START

CREATE TABLE IF NOT EXISTS wan_device_registry (
    id              UUID             NOT NULL PRIMARY KEY,
    created_time    BIGINT           NOT NULL,
    tenant_id       UUID             NOT NULL,
    device_id       UUID             NOT NULL,
    connection_id   UUID             NOT NULL,
    deletion_connection_id UUID,
    device_type     VARCHAR(32)      NOT NULL,
    external_id     VARCHAR(255)     NOT NULL,
    deletion_external_id VARCHAR(255),
    related_external_id VARCHAR(255),
    device_name     VARCHAR(255)     NOT NULL,
    configuration   VARCHAR(1000000) NOT NULL,
    sync_status     VARCHAR(32)      NOT NULL,
    last_sync_time  BIGINT,
    last_successful_sync_time BIGINT,
    next_sync_time  BIGINT,
    error           VARCHAR(4096),
    retry_count     INT              NOT NULL DEFAULT 0,
    lock_owner_id   VARCHAR(255),
    lock_until      BIGINT,
    version         BIGINT           NOT NULL DEFAULT 1,
    CONSTRAINT wan_device_registry_device_id_unq_key UNIQUE (device_id),
    CONSTRAINT fk_wan_device_registry_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_wan_device_registry_tenant_id ON wan_device_registry(tenant_id);
CREATE INDEX IF NOT EXISTS idx_wan_device_registry_sync_status ON wan_device_registry(sync_status);
CREATE INDEX IF NOT EXISTS idx_wan_device_registry_external_id ON wan_device_registry(tenant_id, connection_id, device_type, external_id);

ALTER TABLE wan_device_registry ADD COLUMN IF NOT EXISTS related_external_id VARCHAR(255);
ALTER TABLE wan_device_registry ADD COLUMN IF NOT EXISTS last_successful_sync_time BIGINT;
ALTER TABLE wan_device_registry ADD COLUMN IF NOT EXISTS deletion_connection_id UUID;
ALTER TABLE wan_device_registry ADD COLUMN IF NOT EXISTS deletion_external_id VARCHAR(255);
ALTER TABLE wan_device_registry ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE wan_device_registry ADD COLUMN IF NOT EXISTS lock_owner_id VARCHAR(255);
ALTER TABLE wan_device_registry ADD COLUMN IF NOT EXISTS lock_until BIGINT;
CREATE INDEX IF NOT EXISTS idx_wan_device_registry_claim
    ON wan_device_registry(connection_id, sync_status, lock_until, next_sync_time);
UPDATE wan_device_registry
SET last_successful_sync_time = last_sync_time
WHERE sync_status = 'ACTIVE' AND last_successful_sync_time IS NULL;

-- WAN DEVICE REGISTRY MIGRATION END
