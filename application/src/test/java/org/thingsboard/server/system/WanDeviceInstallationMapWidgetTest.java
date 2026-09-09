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
package org.thingsboard.server.system;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.thingsboard.common.util.JacksonUtil;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WanDeviceInstallationMapWidgetTest {

    @Test
    void shouldConfigureAlarmAwareMarkersAndClusters() throws Exception {
        JsonNode widget = JacksonUtil.toJsonNode(Files.readString(Path.of(
                "src/main/data/json/system/widget_types/wan_device_installation_map.json")));
        JsonNode config = JacksonUtil.toJsonNode(widget.path("descriptor").path("defaultConfig").asText());
        JsonNode markers = config.path("settings").path("markers");

        assertThat(markers).hasSize(2);
        for (JsonNode marker : markers) {
            assertThat(marker.path("additionalDataKeys").findValuesAsText("name"))
                    .containsExactly("highestActiveAlarmSeverity");
            assertThat(marker.path("markerIcon").path("color").path("type").asText()).isEqualTo("range");
            assertThat(marker.path("markerIcon").path("color").path("rangeKey").path("name").asText())
                    .isEqualTo("activeAlarmCount");
            assertThat(marker.path("markerIcon").path("color").path("range").path(0).path("from").asInt())
                    .isEqualTo(1);
            assertThat(marker.path("markerIcon").path("color").path("range").path(0).path("color").asText())
                    .isEqualTo("#D32F2F");
            assertThat(marker.path("markerClustering").path("useClusterMarkerColorFunction").asBoolean()).isTrue();
            assertThat(marker.path("markerClustering").path("maxZoom").asInt()).isEqualTo(16);
            assertThat(marker.path("markerClustering").path("maxClusterRadius").asInt()).isEqualTo(40);
            assertThat(marker.path("markerClustering").path("clusterMarkerColorFunction").path("body").asText())
                    .contains("activeAlarmCount", "#D32F2F");
            JsonNode modules = marker.path("markerClustering").path("clusterMarkerColorFunction").path("modules");
            assertThat(modules.isObject()).isTrue();
            assertThat(modules).isEmpty();
            assertThat(marker.path("tooltip").path("trigger").asText()).isEqualTo("hover");
            assertThat(marker.path("tooltip").path("pattern").asText())
                    .contains("${activeAlarmCount}", "${highestActiveAlarmSeverity}");
        }
    }

}
