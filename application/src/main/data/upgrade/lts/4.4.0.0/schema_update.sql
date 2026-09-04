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
    sync_interval_hours INTEGER       NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 1,
    CONSTRAINT wan_connection_name_unq_key UNIQUE (tenant_id, name),
    CONSTRAINT fk_wan_connection_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_wan_connection_tenant_id ON wan_connection(tenant_id);

-- WAN CONNECTION MIGRATION END
