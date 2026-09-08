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

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.queue.util.TbCoreComponent;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@TbCoreComponent
public class TiandituSearchService {

    public static final int MAX_KEYWORD_LENGTH = 80;
    public static final int MAX_RESULTS = 10;

    private final RestTemplateBuilder restTemplateBuilder;

    @Value("${ui.map.tianditu.api-key:}")
    private String apiKey;

    @Value("${ui.map.tianditu.search-url:https://api.tianditu.gov.cn/v2/search}")
    private String searchUrl;

    @Value("${ui.map.tianditu.search-connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${ui.map.tianditu.search-read-timeout-ms:5000}")
    private int readTimeoutMs;

    private RestTemplate restTemplate;

    public TiandituSearchService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplateBuilder = restTemplateBuilder;
    }

    @PostConstruct
    public void init() {
        restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }

    public List<TiandituPlace> search(String keyword, MapBounds bounds, int zoom) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Tianditu API key is not configured");
        }
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.length() < 2 || normalizedKeyword.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("Search keyword length must be between 2 and " + MAX_KEYWORD_LENGTH);
        }
        if (!bounds.isValid()) {
            throw new IllegalArgumentException("Invalid map bounds");
        }
        if (zoom < 1 || zoom > 18) {
            throw new IllegalArgumentException("Zoom must be between 1 and 18");
        }

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("keyWord", normalizedKeyword);
        query.put("level", zoom);
        query.put("mapBound", bounds.asParameter());
        query.put("queryType", 1);
        query.put("start", 0);
        query.put("count", MAX_RESULTS);

        URI uri = UriComponentsBuilder.fromUriString(searchUrl)
                .queryParam("postStr", JacksonUtil.toString(query))
                .queryParam("type", "query")
                .queryParam("tk", apiKey)
                .build()
                .encode()
                .toUri();
        HttpHeaders headers = new HttpHeaders();
        // Tianditu rejects non-browser user agents even for server-side proxy requests.
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (compatible; ThingsBoard Tianditu Search)");
        JsonNode response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
        return parseResponse(response, MAX_RESULTS);
    }

    static List<TiandituPlace> parseResponse(JsonNode response, int maxResults) {
        if (response == null || maxResults <= 0) {
            return List.of();
        }
        JsonNode status = response.path("status");
        if (!status.isMissingNode() && status.has("infocode") && status.path("infocode").asInt() != 1000) {
            throw new IllegalStateException("Tianditu search service returned an error");
        }
        List<TiandituPlace> results = new ArrayList<>();
        JsonNode area = response.path("area");
        if (area.isObject()) {
            toPlace(area, "AREA").ifPresent(results::add);
        }
        JsonNode pois = response.path("pois");
        if (pois.isArray()) {
            for (JsonNode poi : pois) {
                if (results.size() >= maxResults) {
                    break;
                }
                toPlace(poi, "POI").ifPresent(results::add);
            }
        }
        return List.copyOf(results);
    }

    private static java.util.Optional<TiandituPlace> toPlace(JsonNode node, String type) {
        String name = text(node, "name", 255);
        String lonlat = text(node, "lonlat", 80);
        if (name == null || lonlat == null) {
            return java.util.Optional.empty();
        }
        String[] coordinates = lonlat.split(",", 2);
        if (coordinates.length != 2) {
            return java.util.Optional.empty();
        }
        try {
            double longitude = Double.parseDouble(coordinates[0].trim());
            double latitude = Double.parseDouble(coordinates[1].trim());
            if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90 ||
                    !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new TiandituPlace(
                    name,
                    text(node, "address", 512),
                    latitude,
                    longitude,
                    type,
                    text(node, "province", 100),
                    text(node, "city", 100),
                    text(node, "county", 100)
            ));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static String text(JsonNode node, String field, int maxLength) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    public record MapBounds(double west, double south, double east, double north) {

        public boolean isValid() {
            return Double.isFinite(west) && west >= -180 && west <= 180 &&
                    Double.isFinite(east) && east >= -180 && east <= 180 &&
                    Double.isFinite(south) && south >= -90 && south <= 90 &&
                    Double.isFinite(north) && north >= -90 && north <= 90 &&
                    west < east && south < north;
        }

        String asParameter() {
            return west + "," + south + "," + east + "," + north;
        }
    }

    public record TiandituPlace(String name, String address, double latitude, double longitude, String type,
                                String province, String city, String county) {
    }

}
