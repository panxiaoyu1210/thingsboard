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
import { InstallationLocation, isInstallationLocationValid } from '@shared/models/installation-location.models';
import { TiandituMapService } from '@core/services/tianditu-map.service';
import { UiSettingsService } from '@core/http/ui-settings.service';
import L from 'leaflet';
import { combineLatest } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { isValidLatitude, isValidLongitude } from '@shared/models/widget/maps/map.models';

@Component({
  selector: 'tb-installation-location-dialog',
  templateUrl: './installation-location-dialog.component.html',
  styleUrls: ['./installation-location-dialog.component.scss'],
  standalone: false
})
export class InstallationLocationDialogComponent implements AfterViewInit, OnDestroy {

  @ViewChild('mapContainer', {static: true}) mapContainer: ElementRef<HTMLElement>;

  location: InstallationLocation;
  errorKey: string;

  private map: L.Map;
  private marker: L.Marker;

  constructor(public dialogRef: MatDialogRef<InstallationLocationDialogComponent, InstallationLocation>,
              @Inject(MAT_DIALOG_DATA) location: InstallationLocation | null,
              private tiandituMapService: TiandituMapService,
              private uiSettingsService: UiSettingsService,
              private cd: ChangeDetectorRef,
              private destroyRef: DestroyRef) {
    this.location = location ? {...location} : {latitude: null, longitude: null};
  }

  ngAfterViewInit(): void {
    combineLatest([
      this.uiSettingsService.getTiandituMapSettings(),
      this.tiandituMapService.createLayer()
    ]).pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: ([settings, layer]) => {
        const hasLocation = isInstallationLocationValid(this.location) && this.location.latitude !== null;
        const defaultLatitude = isValidLatitude(settings.defaultCenterLatitude) ? settings.defaultCenterLatitude : 35.8617;
        const defaultLongitude = isValidLongitude(settings.defaultCenterLongitude) ? settings.defaultCenterLongitude : 104.1954;
        const defaultZoom = Math.min(18, Math.max(1, settings.defaultZoom || 4));
        const center: L.LatLngExpression = hasLocation
          ? [this.location.latitude, this.location.longitude]
          : [defaultLatitude, defaultLongitude];
        this.map = L.map(this.mapContainer.nativeElement, {
          center,
          zoom: hasLocation ? Math.max(defaultZoom, 14) : defaultZoom,
          layers: [layer]
        });
        layer.once('tileerror', () => {
          this.errorKey = 'device.wan.location-map-load-failed';
          this.cd.markForCheck();
        });
        this.map.on('click', event => this.setLocation(event.latlng));
        if (hasLocation) {
          this.updateMarker();
        }
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
      this.dialogRef.close({...this.location});
    }
  }

  private setLocation(latLng: L.LatLng): void {
    this.location = {
      latitude: Number(latLng.lat.toFixed(7)),
      longitude: Number(latLng.lng.toFixed(7))
    };
    this.updateMarker();
  }

  private updateMarker(): void {
    const latLng: L.LatLngExpression = [this.location.latitude, this.location.longitude];
    if (this.marker) {
      this.marker.setLatLng(latLng);
      return;
    }
    this.marker = L.marker(latLng, {draggable: true}).addTo(this.map);
    this.marker.on('dragend', () => this.setLocation(this.marker.getLatLng()));
  }
}
