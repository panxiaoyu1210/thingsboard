///
/// Copyright © 2016-2026 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

export interface TenantMapSettings {
  centerLatitude: number | null;
  centerLongitude: number | null;
  defaultZoom: number | null;
  locationName: string | null;
}

export interface MapViewport {
  centerLatitude: number;
  centerLongitude: number;
  zoom: number;
}

export interface ConfiguredTenantMapSettings extends TenantMapSettings {
  centerLatitude: number;
  centerLongitude: number;
  defaultZoom: number;
}

export interface TiandituSearchResult {
  name: string;
  address: string | null;
  latitude: number;
  longitude: number;
  type: 'POI' | 'AREA';
  province: string | null;
  city: string | null;
  county: string | null;
}

export interface TiandituSearchBounds {
  west: number;
  south: number;
  east: number;
  north: number;
}

export const emptyTenantMapSettings = (): TenantMapSettings => ({
  centerLatitude: null,
  centerLongitude: null,
  defaultZoom: null,
  locationName: null
});

export const isTenantMapSettingsConfigured = (
  settings: TenantMapSettings | null | undefined
): settings is ConfiguredTenantMapSettings =>
  Number.isFinite(settings?.centerLatitude) && settings.centerLatitude >= -90 && settings.centerLatitude <= 90 &&
  Number.isFinite(settings?.centerLongitude) && settings.centerLongitude >= -180 && settings.centerLongitude <= 180 &&
  Number.isInteger(settings?.defaultZoom) && settings.defaultZoom >= 1 && settings.defaultZoom <= 18;
