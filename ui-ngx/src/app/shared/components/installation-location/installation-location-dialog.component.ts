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

import {
  AfterViewInit,
  ChangeDetectorRef,
  Component,
  DestroyRef,
  ElementRef,
  Inject,
  OnDestroy,
  ViewChild
} from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import {
  InstallationLocation,
  InstallationLocationDialogData,
  InstallationLocationDialogResult,
  isInstallationLocationValid
} from '@shared/models/installation-location.models';
import { TiandituMapService } from '@core/services/tianditu-map.service';
import L from 'leaflet';
import { combineLatest, of } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl } from '@angular/forms';
import { catchError, debounceTime, distinctUntilChanged, map, switchMap, tap } from 'rxjs/operators';
import { TiandituSearchBounds, TiandituSearchResult } from '@shared/models/tenant-map-settings.models';

@Component({
  selector: 'tb-installation-location-dialog',
  templateUrl: './installation-location-dialog.component.html',
  styleUrls: ['./installation-location-dialog.component.scss'],
  standalone: false
})
export class InstallationLocationDialogComponent implements AfterViewInit, OnDestroy {

  @ViewChild('mapContainer', {static: true}) mapContainer: ElementRef<HTMLElement>;

  location: InstallationLocation;
  locationName: string | null;
  errorKey: string;
  searchErrorKey: string;
  searchResults: TiandituSearchResult[] = [];
  searchLoading = false;
  searchAttempted = false;
  readonly searchControl = new FormControl('', {nonNullable: true});
  readonly titleKey: string;
  readonly hintKey: string;

  private map: L.Map;
  private marker: L.Marker;
  private readonly initialZoom: number | null;

  constructor(public dialogRef: MatDialogRef<InstallationLocationDialogComponent, InstallationLocationDialogResult>,
              @Inject(MAT_DIALOG_DATA) data: InstallationLocationDialogData,
              private tiandituMapService: TiandituMapService,
              private cd: ChangeDetectorRef,
              private destroyRef: DestroyRef) {
    this.location = data.location ? {...data.location} : {latitude: null, longitude: null};
    this.locationName = data.locationName || null;
    this.initialZoom = data.initialZoom || null;
    this.titleKey = data.titleKey || 'device.wan.location-map-title';
    this.hintKey = data.hintKey || 'device.wan.location-map-hint';
  }

  ngAfterViewInit(): void {
    combineLatest([
      this.tiandituMapService.getEffectiveDefaultViewport(),
      this.tiandituMapService.createLayer()
    ]).pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: ([defaultViewport, layer]) => {
        const hasLocation = isInstallationLocationValid(this.location) && this.location.latitude !== null;
        const defaultZoom = Math.min(18, Math.max(1, defaultViewport.zoom || 4));
        const center: L.LatLngExpression = hasLocation
          ? [this.location.latitude, this.location.longitude]
          : [defaultViewport.centerLatitude, defaultViewport.centerLongitude];
        const zoom = this.initialZoom
          ? Math.min(18, Math.max(1, this.initialZoom))
          : hasLocation ? Math.max(defaultZoom, 14) : defaultZoom;
        this.map = L.map(this.mapContainer.nativeElement, {
          center,
          zoom,
          layers: [layer]
        });
        layer.once('tileerror', () => {
          this.errorKey = 'device.wan.location-map-load-failed';
          this.cd.markForCheck();
        });
        this.map.on('click', event => this.setLocation(event.latlng, null));
        if (hasLocation) {
          this.updateMarker();
        }
        this.initializeSearch();
        setTimeout(() => this.map?.invalidateSize());
      },
      error: error => {
        this.errorKey = error?.message?.includes('not configured')
          ? 'device.wan.location-map-key-missing'
          : 'device.wan.location-map-load-failed';
        this.cd.markForCheck();
      }
    });
  }

  ngOnDestroy(): void {
    this.map?.remove();
  }

  cancel(): void {
    this.dialogRef.close();
  }

  apply(): void {
    if (this.location.latitude !== null && this.location.longitude !== null) {
      this.dialogRef.close({
        location: {...this.location},
        zoom: this.map.getZoom(),
        locationName: this.locationName
      });
    }
  }

  selectSearchResult(result: TiandituSearchResult): void {
    const targetZoom = result.type === 'AREA'
      ? 10
      : Math.max(this.map.getZoom(), 16);
    this.map.setView([result.latitude, result.longitude], Math.min(18, targetZoom));
    this.setLocation(L.latLng(result.latitude, result.longitude), result.name);
    this.searchControl.setValue(result.name, {emitEvent: false});
    this.searchResults = [];
    this.searchAttempted = false;
  }

  searchResultDescription(result: TiandituSearchResult): string {
    return result.address || [result.province, result.city, result.county]
      .filter((value, index, values) => !!value && values.indexOf(value) === index)
      .join(' · ');
  }

  private initializeSearch(): void {
    this.searchControl.valueChanges.pipe(
      debounceTime(400),
      map(query => query.trim()),
      distinctUntilChanged(),
      tap(query => {
        this.searchResults = [];
        this.searchErrorKey = null;
        this.searchAttempted = query.length >= 2;
        this.searchLoading = query.length >= 2;
      }),
      switchMap(query => {
        if (query.length < 2) {
          return of({results: [] as TiandituSearchResult[], errorKey: null as string | null});
        }
        return this.tiandituMapService.searchPlaces(query, this.currentSearchBounds(), this.map.getZoom()).pipe(
          map(results => ({results, errorKey: null as string | null})),
          catchError(() => of({
            results: [] as TiandituSearchResult[],
            errorKey: 'device.wan.location-search-failed'
          }))
        );
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(state => {
      this.searchLoading = false;
      this.searchResults = state.results;
      this.searchErrorKey = state.errorKey;
      this.cd.markForCheck();
    });
  }

  private currentSearchBounds(): TiandituSearchBounds {
    const bounds = this.map.getBounds();
    let west = Math.max(-180, Math.min(180, bounds.getWest()));
    let east = Math.max(-180, Math.min(180, bounds.getEast()));
    if (west >= east) {
      west = -180;
      east = 180;
    }
    return {
      west,
      south: Math.max(-90, Math.min(90, bounds.getSouth())),
      east,
      north: Math.max(-90, Math.min(90, bounds.getNorth()))
    };
  }

  private setLocation(latLng: L.LatLng, locationName: string | null): void {
    this.location = {
      latitude: Number(latLng.lat.toFixed(7)),
      longitude: Number(latLng.lng.toFixed(7))
    };
    this.locationName = locationName;
    this.updateMarker();
  }

  private updateMarker(): void {
    const latLng: L.LatLngExpression = [this.location.latitude, this.location.longitude];
    if (this.marker) {
      this.marker.setLatLng(latLng);
      return;
    }
    this.marker = L.marker(latLng, {draggable: true}).addTo(this.map);
    this.marker.on('dragend', () => this.setLocation(this.marker.getLatLng(), null));
  }
}
