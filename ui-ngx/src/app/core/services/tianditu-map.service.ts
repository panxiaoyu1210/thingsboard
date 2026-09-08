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

import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { UiSettingsService } from '@core/http/ui-settings.service';
import { TenantMapSettingsService } from '@core/http/tenant-map-settings.service';
import { TiandituLayerType } from '@shared/models/widget/maps/map.models';
import L from 'leaflet';
import { catchError, map } from 'rxjs/operators';
import { combineLatest, Observable, of } from 'rxjs';
import {
  emptyTenantMapSettings,
  isTenantMapSettingsConfigured,
  MapViewport,
  TiandituSearchBounds,
  TiandituSearchResult
} from '@shared/models/tenant-map-settings.models';
import { defaultHttpOptions } from '@core/http/http-utils';

@Injectable({
  providedIn: 'root'
})
export class TiandituMapService {

  private readonly layerCodes = new Map<TiandituLayerType, [string, string]>([
    [TiandituLayerType.vector, ['vec_w', 'cva_w']],
    [TiandituLayerType.satellite, ['img_w', 'cia_w']],
    [TiandituLayerType.terrain, ['ter_w', 'cta_w']]
  ]);

  constructor(private uiSettingsService: UiSettingsService,
              private tenantMapSettingsService: TenantMapSettingsService,
              private http: HttpClient) {
  }

  createLayer(layerType?: TiandituLayerType): Observable<L.Layer> {
    return this.uiSettingsService.getTiandituMapSettings().pipe(
      map(settings => {
        const apiKey = settings.apiKey?.trim();
        if (!apiKey) {
          throw new Error('Tianditu API key is not configured.');
        }
        const resolvedLayerType = this.resolveLayerType(layerType || settings.defaultLayer);
        const [baseLayerCode, annotationLayerCode] = this.layerCodes.get(resolvedLayerType);
        return this.createLayerGroup(baseLayerCode, annotationLayerCode, apiKey);
      })
    );
  }

  resolveLayerType(layerType: string): TiandituLayerType {
    return Object.values(TiandituLayerType).includes(layerType as TiandituLayerType)
      ? layerType as TiandituLayerType
      : TiandituLayerType.vector;
  }

  getEffectiveDefaultViewport(): Observable<MapViewport> {
    return combineLatest([
      this.uiSettingsService.getTiandituMapSettings(),
      this.tenantMapSettingsService.getTenantMapSettings({ignoreErrors: true}).pipe(
        catchError(() => of(emptyTenantMapSettings()))
      )
    ]).pipe(
      map(([uiSettings, tenantSettings]) => {
        if (isTenantMapSettingsConfigured(tenantSettings)) {
          return {
            centerLatitude: tenantSettings.centerLatitude,
            centerLongitude: tenantSettings.centerLongitude,
            zoom: tenantSettings.defaultZoom
          };
        }
        return {
          centerLatitude: uiSettings.defaultCenterLatitude,
          centerLongitude: uiSettings.defaultCenterLongitude,
          zoom: uiSettings.defaultZoom
        };
      })
    );
  }

  searchPlaces(query: string, bounds: TiandituSearchBounds, zoom: number): Observable<TiandituSearchResult[]> {
    const params = new HttpParams()
      .set('query', query)
      .set('west', bounds.west.toString())
      .set('south', bounds.south.toString())
      .set('east', bounds.east.toString())
      .set('north', bounds.north.toString())
      .set('zoom', zoom.toString());
    return this.http.get<TiandituSearchResult[]>(
      '/api/map/tianditu/search', {...defaultHttpOptions(true), params});
  }

  private createLayerGroup(baseLayerCode: string, annotationLayerCode: string, apiKey: string): L.Layer {
    const attribution = '&copy; 天地图';
    const tileOptions: L.TileLayerOptions = {
      attribution,
      maxZoom: 18,
      subdomains: ['0', '1', '2', '3', '4', '5', '6', '7']
    };
    const tileUrl = (layerCode: string) =>
      `https://t{s}.tianditu.gov.cn/DataServer?T=${layerCode}&x={x}&y={y}&l={z}&tk=${encodeURIComponent(apiKey)}`;
    const layers = [
      L.tileLayer(tileUrl(baseLayerCode), tileOptions),
      L.tileLayer(tileUrl(annotationLayerCode), tileOptions)
    ];
    const layerGroup = L.layerGroup(layers) as L.Layer & { getAttribution: () => string };
    layerGroup.getAttribution = () => attribution;
    let loadedLayers = 0;
    layers.forEach(layer => layer.once('load', () => {
      loadedLayers++;
      if (loadedLayers === layers.length) {
        layerGroup.fire('load');
      }
    }));
    layers.forEach(layer => layer.on('tileerror', event => layerGroup.fire('tileerror', event)));
    return layerGroup;
  }
}
