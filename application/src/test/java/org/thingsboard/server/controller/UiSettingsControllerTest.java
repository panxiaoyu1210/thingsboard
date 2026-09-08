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

import org.junit.Test;
import org.thingsboard.server.dao.service.DaoSqlTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DaoSqlTest
public class UiSettingsControllerTest extends AbstractControllerTest {

    @Test
    public void shouldRequireAuthenticationForTiandituSettings() throws Exception {
        doGet("/api/uiSettings/tiandituMap")
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void shouldReturnTiandituSettingsToTenantAdministrator() throws Exception {
        loginTenantAdmin();

        UiSettingsController.TiandituMapSettings settings = doGet(
                "/api/uiSettings/tiandituMap", UiSettingsController.TiandituMapSettings.class);

        assertThat(settings.apiKey()).isEmpty();
        assertThat(settings.defaultLayer()).isEqualTo("vector");
        assertThat(settings.defaultCenterLatitude()).isEqualTo(35.8617);
        assertThat(settings.defaultCenterLongitude()).isEqualTo(104.1954);
        assertThat(settings.defaultZoom()).isEqualTo(4);
    }
}
