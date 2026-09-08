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

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.thingsboard.server.controller.TenantMapSettingsController.TenantMapSettings;
import org.thingsboard.server.dao.service.DaoSqlTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DaoSqlTest
public class TenantMapSettingsControllerTest extends AbstractControllerTest {

    @Before
    public void login() throws Exception {
        loginTenantAdmin();
    }

    @Test
    public void shouldSaveReadAndClearCurrentTenantMapSettings() throws Exception {
        TenantMapSettings saved = saveSettings(
                new TenantMapSettings(31.2304, 121.4737, 12, "上海市"));

        assertThat(saved).isEqualTo(new TenantMapSettings(31.2304, 121.4737, 12, "上海市"));
        assertThat(doGetAsync("/api/tenant/mapSettings", TenantMapSettings.class)).isEqualTo(saved);

        TenantMapSettings withoutName = saveSettings(new TenantMapSettings(31.2304, 121.4737, 11, null));
        assertThat(withoutName.locationName()).isNull();
        assertThat(doGetAsync("/api/tenant/mapSettings", TenantMapSettings.class).locationName()).isNull();

        TenantMapSettings cleared = saveSettings(TenantMapSettings.empty());

        assertThat(cleared.isEmpty()).isTrue();
        assertThat(doGetAsync("/api/tenant/mapSettings", TenantMapSettings.class).isEmpty()).isTrue();
    }

    @Test
    public void shouldRejectIncompleteOrOutOfRangeViewport() throws Exception {
        doPut("/api/tenant/mapSettings", new TenantMapSettings(31.2304, null, 12, null))
                .andExpect(status().isBadRequest());
        doPut("/api/tenant/mapSettings", new TenantMapSettings(91.0, 121.4737, 12, null))
                .andExpect(status().isBadRequest());
        doPut("/api/tenant/mapSettings", new TenantMapSettings(31.2304, 121.4737, 19, null))
                .andExpect(status().isBadRequest());
        doPut("/api/tenant/mapSettings", new TenantMapSettings(null, null, null, "无坐标名称"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void shouldAllowCustomerToReadButNotWriteTenantMapSettings() throws Exception {
        saveSettings(new TenantMapSettings(31.2304, 121.4737, 12, "上海市"));

        loginCustomerUser();

        TenantMapSettings settings = doGetAsync("/api/tenant/mapSettings", TenantMapSettings.class);
        assertThat(settings.centerLatitude()).isEqualTo(31.2304);
        doPut("/api/tenant/mapSettings", new TenantMapSettings(39.9042, 116.4074, 12, "北京市"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void shouldRequireAuthentication() throws Exception {
        token = null;

        doGet("/api/tenant/mapSettings").andExpect(status().isUnauthorized());
        doPut("/api/tenant/mapSettings", TenantMapSettings.empty()).andExpect(status().isUnauthorized());
    }

    private TenantMapSettings saveSettings(TenantMapSettings settings) throws Exception {
        return readResponse(doPutAsync(settings).andExpect(status().isOk()), TenantMapSettings.class);
    }

    private ResultActions doPutAsync(TenantMapSettings settings) throws Exception {
        var request = put("/api/tenant/mapSettings");
        setJwtToken(request);
        request.contentType(contentType).content(json(settings));
        MvcResult result = mockMvc.perform(request)
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(30_000L);
        return mockMvc.perform(asyncDispatch(result));
    }

}
