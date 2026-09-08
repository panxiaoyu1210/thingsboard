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
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.server.service.map.TiandituSearchService;
import org.thingsboard.server.service.map.TiandituSearchService.MapBounds;
import org.thingsboard.server.service.map.TiandituSearchService.TiandituPlace;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DaoSqlTest
public class TiandituMapControllerTest extends AbstractControllerTest {

    private static final String SEARCH_URL = "/api/map/tianditu/search" +
            "?query=北京大学&west=116.0&south=39.8&east=116.7&north=40.0&zoom=12";

    @MockitoBean
    private TiandituSearchService searchService;

    @Before
    public void login() throws Exception {
        loginTenantAdmin();
        Mockito.reset(searchService);
    }

    @Test
    public void shouldReturnNormalizedSearchResults() throws Exception {
        Mockito.when(searchService.search(
                        eq("北京大学"), eq(new MapBounds(116.0, 39.8, 116.7, 40.0)), eq(12)))
                .thenReturn(List.of(new TiandituPlace(
                        "北京大学", "北京市海淀区", 39.9929, 116.3109, "POI", "北京市", "北京市", "海淀区")));

        doGet(SEARCH_URL)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("北京大学")))
                .andExpect(content().string(containsString("39.9929")));
    }

    @Test
    public void shouldRejectInvalidSearchInput() throws Exception {
        Mockito.when(searchService.search(
                        eq("北"), eq(new MapBounds(116.0, 39.8, 116.7, 40.0)), eq(12)))
                .thenThrow(new IllegalArgumentException("Search keyword length must be between 2 and 80"));

        doGet(SEARCH_URL.replace("北京大学", "北"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void shouldRequireAuthentication() throws Exception {
        token = null;

        doGet(SEARCH_URL).andExpect(status().isUnauthorized());
    }

}
