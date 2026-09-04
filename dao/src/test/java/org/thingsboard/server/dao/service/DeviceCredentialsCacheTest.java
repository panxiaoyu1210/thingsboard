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
package org.thingsboard.server.dao.service;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.CacheConstants;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.device.credentials.WanDeviceCredentials;
import org.thingsboard.server.common.data.id.DeviceCredentialsId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.common.data.wan.WanDeviceRootKeyCipher;
import org.thingsboard.server.dao.device.DeviceCredentialsDao;
import org.thingsboard.server.dao.device.DeviceCredentialsService;
import org.thingsboard.server.dao.device.DeviceDao;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DaoSqlTest
public class DeviceCredentialsCacheTest extends AbstractServiceTest {

    private final String CREDENTIALS_ID_1 = StringUtils.randomAlphanumeric(20);
    private final String CREDENTIALS_ID_2 = StringUtils.randomAlphanumeric(20);
    private final String WAN_ROOT_KEY = "0102030405060708090A0B0C0D0E0F10";
    private final WanDeviceRootKeyCipher wanRootKeyCipher = new WanDeviceRootKeyCipher("cache-test-key");

    @Autowired
    private DeviceCredentialsService deviceCredentialsService;

    @Autowired
    private DataValidator<DeviceCredentials> credentialsValidator;

    private DeviceCredentialsDao deviceCredentialsDao;
    private DeviceDao deviceDao;

    @Autowired
    private CacheManager cacheManager;

    private UUID deviceId = UUID.randomUUID();

    @Before
    public void setup() throws Exception {
        deviceDao = mock(DeviceDao.class);
        deviceCredentialsDao = mock(DeviceCredentialsDao.class);

        ReflectionTestUtils.setField(credentialsValidator, "deviceDao", deviceDao);
        ReflectionTestUtils.setField(credentialsValidator, "deviceCredentialsDao", deviceCredentialsDao);

        ReflectionTestUtils.setField(unwrapDeviceCredentialsService(), "deviceCredentialsDao", deviceCredentialsDao);
        ReflectionTestUtils.setField(unwrapDeviceCredentialsService(), "credentialsValidator", credentialsValidator);
        ReflectionTestUtils.setField(unwrapDeviceCredentialsService(), "wanRootKeyCipher", wanRootKeyCipher);
    }

    @After
    public void cleanup() {
        cacheManager.getCache(CacheConstants.DEVICE_CREDENTIALS_CACHE).clear();
    }

    @Test
    public void testFindDeviceCredentialsByCredentialsId_Cached() {
        when(deviceCredentialsDao.findByCredentialsId(SYSTEM_TENANT_ID, CREDENTIALS_ID_1)).thenReturn(createDummyDeviceCredentialsEntity(CREDENTIALS_ID_1));

        deviceCredentialsService.findDeviceCredentialsByCredentialsId(CREDENTIALS_ID_1);
        deviceCredentialsService.findDeviceCredentialsByCredentialsId(CREDENTIALS_ID_1);

        verify(deviceCredentialsDao, times(1)).findByCredentialsId(SYSTEM_TENANT_ID, CREDENTIALS_ID_1);
    }

    @Test
    public void testFindWanDeviceCredentialsByCredentialsId_CachesProtectedRootKey() {
        DeviceCredentials protectedCredentials = createDummyDeviceCredentialsEntity(CREDENTIALS_ID_1);
        protectedCredentials.setCredentialsType(DeviceCredentialsType.WAN_CREDENTIALS);
        WanDeviceCredentials protectedValue = new WanDeviceCredentials();
        protectedValue.setRootKey(wanRootKeyCipher.encrypt(WAN_ROOT_KEY));
        protectedCredentials.setCredentialsValue(JacksonUtil.toString(protectedValue));
        when(deviceCredentialsDao.findByCredentialsId(SYSTEM_TENANT_ID, CREDENTIALS_ID_1))
                .thenReturn(protectedCredentials);

        DeviceCredentials first = deviceCredentialsService.findDeviceCredentialsByCredentialsId(CREDENTIALS_ID_1);
        DeviceCredentials second = deviceCredentialsService.findDeviceCredentialsByCredentialsId(CREDENTIALS_ID_1);

        assertThat(rootKey(first)).isEqualTo(WAN_ROOT_KEY);
        assertThat(rootKey(second)).isEqualTo(WAN_ROOT_KEY);
        DeviceCredentials cached = cacheManager.getCache(CacheConstants.DEVICE_CREDENTIALS_CACHE)
                .get(CREDENTIALS_ID_1, DeviceCredentials.class);
        assertThat(rootKey(cached)).startsWith("v1:").doesNotContain(WAN_ROOT_KEY);
        verify(deviceCredentialsDao, times(1)).findByCredentialsId(SYSTEM_TENANT_ID, CREDENTIALS_ID_1);
    }

    @Test
    public void testDeleteDeviceCredentials_EvictsCache() {
        when(deviceCredentialsDao.findByCredentialsId(SYSTEM_TENANT_ID, CREDENTIALS_ID_1)).thenReturn(createDummyDeviceCredentialsEntity(CREDENTIALS_ID_1));

        deviceCredentialsService.findDeviceCredentialsByCredentialsId(CREDENTIALS_ID_1);
        deviceCredentialsService.findDeviceCredentialsByCredentialsId(CREDENTIALS_ID_1);

        verify(deviceCredentialsDao, times(1)).findByCredentialsId(SYSTEM_TENANT_ID, CREDENTIALS_ID_1);

        deviceCredentialsService.deleteDeviceCredentials(SYSTEM_TENANT_ID, createDummyDeviceCredentials(CREDENTIALS_ID_1, deviceId));

        deviceCredentialsService.findDeviceCredentialsByCredentialsId(CREDENTIALS_ID_1);
        deviceCredentialsService.findDeviceCredentialsByCredentialsId(CREDENTIALS_ID_1);

        verify(deviceCredentialsDao, times(2)).findByCredentialsId(SYSTEM_TENANT_ID, CREDENTIALS_ID_1);
    }

    @Test
    public void testSaveDeviceCredentials_EvictsPreviousCache() throws Exception {
        when(deviceCredentialsDao.findByCredentialsId(SYSTEM_TENANT_ID, CREDENTIALS_ID_1)).thenReturn(createDummyDeviceCredentialsEntity(CREDENTIALS_ID_1));

        deviceCredentialsService.findDeviceCredentialsByCredentialsId(CREDENTIALS_ID_1);
        deviceCredentialsService.findDeviceCredentialsByCredentialsId(CREDENTIALS_ID_1);

        verify(deviceCredentialsDao, times(1)).findByCredentialsId(SYSTEM_TENANT_ID, CREDENTIALS_ID_1);

        when(deviceCredentialsDao.findByDeviceId(SYSTEM_TENANT_ID, deviceId)).thenReturn(createDummyDeviceCredentialsEntity(CREDENTIALS_ID_1));

        UUID deviceCredentialsId = UUID.randomUUID();
        when(deviceCredentialsDao.findById(SYSTEM_TENANT_ID, deviceCredentialsId)).thenReturn(createDummyDeviceCredentialsEntity(CREDENTIALS_ID_1));
        when(deviceDao.findById(SYSTEM_TENANT_ID, deviceId)).thenReturn(new Device());

        var dummy = createDummyDeviceCredentials(deviceCredentialsId, CREDENTIALS_ID_2, deviceId);
        when(deviceCredentialsDao.saveAndFlush(SYSTEM_TENANT_ID, dummy)).thenReturn(dummy);

        deviceCredentialsService.updateDeviceCredentials(SYSTEM_TENANT_ID, dummy);

        when(deviceCredentialsDao.findByCredentialsId(SYSTEM_TENANT_ID, CREDENTIALS_ID_1)).thenReturn(null);

        deviceCredentialsService.findDeviceCredentialsByCredentialsId(CREDENTIALS_ID_1); // cache miss, DB read (not found), Cache put null value
        deviceCredentialsService.findDeviceCredentialsByCredentialsId(CREDENTIALS_ID_1); // cache hit (null value, credentials not found)

        verify(deviceCredentialsDao, times(2)).findByCredentialsId(SYSTEM_TENANT_ID, CREDENTIALS_ID_1);
    }

    private DeviceCredentialsService unwrapDeviceCredentialsService() throws Exception {
        if (AopUtils.isAopProxy(deviceCredentialsService) && deviceCredentialsService instanceof Advised) {
            Object target = ((Advised) deviceCredentialsService).getTargetSource().getTarget();
            return (DeviceCredentialsService) target;
        }
        return deviceCredentialsService;
    }

    private DeviceCredentials createDummyDeviceCredentialsEntity(String deviceCredentialsId) {
        DeviceCredentials result = new DeviceCredentials(new DeviceCredentialsId(UUID.randomUUID()));
        result.setCredentialsId(deviceCredentialsId);
        return result;
    }

    private String rootKey(DeviceCredentials credentials) {
        return JacksonUtil.fromString(credentials.getCredentialsValue(), WanDeviceCredentials.class).getRootKey();
    }

    private DeviceCredentials createDummyDeviceCredentials(String deviceCredentialsId, UUID deviceId) {
        return createDummyDeviceCredentials(null, deviceCredentialsId, deviceId);
    }

    private DeviceCredentials createDummyDeviceCredentials(UUID id, String deviceCredentialsId, UUID deviceId) {
        DeviceCredentials result = new DeviceCredentials();
        result.setId(new DeviceCredentialsId(id));
        result.setDeviceId(new DeviceId(deviceId));
        result.setCredentialsId(deviceCredentialsId);
        result.setCredentialsType(DeviceCredentialsType.ACCESS_TOKEN);
        return result;
    }
}
