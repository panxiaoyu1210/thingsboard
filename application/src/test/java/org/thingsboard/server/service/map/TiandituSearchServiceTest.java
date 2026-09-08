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
package org.thingsboard.server.service.map;

import org.junit.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.service.map.TiandituSearchService.TiandituPlace;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TiandituSearchServiceTest {

    @Test
    public void shouldParseAreaAndPoiResponses() {
        List<TiandituPlace> areaResults = TiandituSearchService.parseResponse(JacksonUtil.toJsonNode("""
                {
                  "resultType": 3,
                  "area": {"name": "北京市", "lonlat": "116.4074,39.9042"},
                  "status": {"infocode": 1000}
                }
                """), 10);
        List<TiandituPlace> poiResults = TiandituSearchService.parseResponse(JacksonUtil.toJsonNode("""
                {
                  "resultType": 1,
                  "pois": [
                    {"name": "北京大学", "address": "北京市海淀区", "lonlat": "116.3109,39.9929",
                     "province": "北京市", "city": "北京市", "county": "海淀区"},
                    {"name": "非法坐标", "lonlat": "999,999"}
                  ],
                  "status": {"infocode": 1000}
                }
                """), 10);

        assertThat(areaResults).containsExactly(
                new TiandituPlace("北京市", null, 39.9042, 116.4074, "AREA", null, null, null));
        assertThat(poiResults).containsExactly(
                new TiandituPlace("北京大学", "北京市海淀区", 39.9929, 116.3109,
                        "POI", "北京市", "北京市", "海淀区"));
    }

    @Test
    public void shouldLimitResultsAndRejectServiceErrors() {
        List<TiandituPlace> results = TiandituSearchService.parseResponse(JacksonUtil.toJsonNode("""
                {
                  "pois": [
                    {"name": "位置一", "lonlat": "116.1,39.1"},
                    {"name": "位置二", "lonlat": "116.2,39.2"}
                  ],
                  "status": {"infocode": 1000}
                }
                """), 1);

        assertThat(results).hasSize(1);
        assertThatThrownBy(() -> TiandituSearchService.parseResponse(JacksonUtil.toJsonNode("""
                {"status": {"infocode": 30001}}
                """), 10)).isInstanceOf(IllegalStateException.class);
    }

}
