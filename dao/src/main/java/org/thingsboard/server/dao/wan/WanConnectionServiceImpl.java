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
package org.thingsboard.server.dao.wan;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.wan.WanConnection;
import org.thingsboard.server.dao.service.ConstraintValidator;
import org.thingsboard.server.exception.DataValidationException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WanConnectionServiceImpl implements WanConnectionService {

    private static final int MAX_PASSWORD_LENGTH = 2_048;

    private final WanConnectionDao wanConnectionDao;
    private final WanConnectionSecretService secretService;

    @Override
    @Transactional
    public WanConnection saveWanConnection(TenantId tenantId, WanConnection connection) {
        if (connection == null) {
            throw new DataValidationException("WAN connection must be specified!");
        }
        WanConnection toSave = new WanConnection(connection);
        normalize(toSave);
        validate(toSave);

        WanConnection old = null;
        if (toSave.getId() == null) {
            UUID id = Uuids.timeBased();
            toSave.setId(id);
            toSave.setCreatedTime(Uuids.unixTimestamp(id));
            toSave.setVersion(null);
        } else {
            old = wanConnectionDao.findById(tenantId, toSave.getId());
            if (old == null) {
                throw new DataValidationException("WAN connection does not exist in current tenant!");
            }
            toSave.setCreatedTime(old.getCreatedTime());
            if (toSave.getVersion() == null) {
                throw new DataValidationException("WAN connection version must be specified when updating!");
            }
        }
        toSave.setTenantId(tenantId);

        if (wanConnectionDao.existsByName(tenantId, toSave.getName(), toSave.getId())) {
            throw new DataValidationException("WAN connection with such name already exists!");
        }
        applyPassword(toSave, old);
        return sanitize(wanConnectionDao.save(tenantId, toSave));
    }

    @Override
    public WanConnection findWanConnectionById(TenantId tenantId, UUID connectionId) {
        return sanitize(wanConnectionDao.findById(tenantId, connectionId));
    }

    @Override
    public WanConnection findWanConnectionWithCredentials(TenantId tenantId, UUID connectionId) {
        WanConnection connection = wanConnectionDao.findById(tenantId, connectionId);
        if (connection != null) {
            connection.setPassword(secretService.decrypt(connection.getEncryptedPassword()));
            connection.setEncryptedPassword(null);
        }
        return connection;
    }

    @Override
    public PageData<WanConnection> findWanConnections(TenantId tenantId, PageLink pageLink) {
        return wanConnectionDao.findByTenantId(tenantId, pageLink).mapData(this::sanitize);
    }

    @Override
    public PageData<WanConnection> findEnabledWanConnections(PageLink pageLink) {
        return wanConnectionDao.findEnabled(pageLink);
    }

    @Override
    @Transactional
    public List<WanConnection> claimEnabledWanConnections(String ownerId, long now, long leaseUntil) {
        validateLease(ownerId, now, leaseUntil);
        return wanConnectionDao.claimEnabled(ownerId, now, leaseUntil);
    }

    @Override
    @Transactional
    public void releaseWanConnections(String ownerId) {
        validateOwnerId(ownerId);
        wanConnectionDao.releaseOwned(ownerId);
    }

    @Override
    public WanConnection prepareConnectionTest(TenantId tenantId, WanConnection connection) {
        if (connection == null) {
            throw new DataValidationException("WAN connection must be specified!");
        }
        WanConnection resolved = new WanConnection(connection);
        normalize(resolved);
        WanConnection old = null;
        if (resolved.getId() != null) {
            old = findWanConnectionWithCredentials(tenantId, resolved.getId());
            if (old == null) {
                throw new DataValidationException("WAN connection does not exist in current tenant!");
            }
        }
        if (old != null && (resolved.getPassword() == null || WanConnection.PASSWORD_MASK.equals(resolved.getPassword()))) {
            resolved.setPassword(old.getPassword());
        } else if (old == null && WanConnection.PASSWORD_MASK.equals(resolved.getPassword())) {
            throw new DataValidationException("A password mask cannot be used for a new WAN connection!");
        }
        validate(resolved);
        resolved.setTenantId(tenantId);
        resolved.setEncryptedPassword(null);
        return resolved;
    }

    @Override
    @Transactional
    public void deleteWanConnection(TenantId tenantId, UUID connectionId) {
        WanConnection connection = wanConnectionDao.findById(tenantId, connectionId);
        if (connection == null) {
            throw new DataValidationException("WAN connection does not exist in current tenant!");
        }
        if (wanConnectionDao.isReferencedByDeviceProfile(tenantId, connectionId)) {
            throw new DataValidationException("WAN connection is still referenced by a device profile and cannot be deleted!");
        }
        if (!wanConnectionDao.removeById(tenantId, connectionId)) {
            throw new DataValidationException("WAN connection does not exist in current tenant!");
        }
    }

    private void applyPassword(WanConnection connection, WanConnection old) {
        String submitted = connection.getPassword();
        if (old != null && (submitted == null || WanConnection.PASSWORD_MASK.equals(submitted))) {
            connection.setEncryptedPassword(old.getEncryptedPassword());
        } else if (StringUtils.isEmpty(submitted)) {
            connection.setEncryptedPassword(null);
        } else {
            if (submitted.length() > MAX_PASSWORD_LENGTH) {
                throw new DataValidationException("WAN connection password must not exceed 2048 characters!");
            }
            connection.setEncryptedPassword(secretService.encrypt(submitted));
        }
        connection.setPassword(null);
        connection.setPasswordSet(connection.getEncryptedPassword() != null);
    }

    private WanConnection sanitize(WanConnection connection) {
        if (connection == null) {
            return null;
        }
        WanConnection sanitized = new WanConnection(connection);
        boolean passwordSet = StringUtils.isNotEmpty(sanitized.getEncryptedPassword());
        sanitized.setPasswordSet(passwordSet);
        sanitized.setPassword(passwordSet ? WanConnection.PASSWORD_MASK : null);
        sanitized.setEncryptedPassword(null);
        return sanitized;
    }

    private void normalize(WanConnection connection) {
        connection.setName(trim(connection.getName()));
        connection.setBrokerHost(trim(connection.getBrokerHost()));
        connection.setClientId(trim(connection.getClientId()));
        connection.setUsername(trimToNull(connection.getUsername()));
        connection.setNsPublishTopic(trim(connection.getNsPublishTopic()));
        connection.setNsSubscribeTopic(trim(connection.getNsSubscribeTopic()));
    }

    private void validate(WanConnection connection) {
        ConstraintValidator.validateFields(connection);
        try {
            connection.validate();
        } catch (IllegalArgumentException e) {
            throw new DataValidationException(e.getMessage());
        }
    }

    private void validateLease(String ownerId, long now, long leaseUntil) {
        validateOwnerId(ownerId);
        if (now < 0 || leaseUntil <= now || leaseUntil - now > 3_600_000L) {
            throw new IllegalArgumentException("WAN connection ownership lease is invalid");
        }
    }

    private void validateOwnerId(String ownerId) {
        if (StringUtils.isBlank(ownerId) || ownerId.length() > 255) {
            throw new IllegalArgumentException("WAN connection ownership owner is invalid");
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return StringUtils.isEmpty(trimmed) ? null : trimmed;
    }

}
