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

import { Component, OnInit } from '@angular/core';
import { AbstractControl, FormBuilder, FormGroup, ValidationErrors, Validators } from '@angular/forms';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { PageComponent } from '@shared/components/page.component';
import { HasConfirmForm } from '@core/guards/confirm-on-exit.guard';
import { TenantMapSettingsService } from '@core/http/tenant-map-settings.service';
import {
  emptyTenantMapSettings,
  isTenantMapSettingsConfigured,
  TenantMapSettings
} from '@shared/models/tenant-map-settings.models';
import { MatDialog } from '@angular/material/dialog';
import {
  InstallationLocationDialogComponent
} from '@shared/components/installation-location/installation-location-dialog.component';
import {
  InstallationLocationDialogData,
  InstallationLocationDialogResult
} from '@shared/models/installation-location.models';
import { take } from 'rxjs/operators';

@Component({
  selector: 'tb-tenant-map-settings',
  templateUrl: './tenant-map-settings.component.html',
  styleUrls: ['./tenant-map-settings.component.scss', './settings-card.scss'],
  standalone: false
})
export class TenantMapSettingsComponent extends PageComponent implements OnInit, HasConfirmForm {

  mapSettings: FormGroup;

  constructor(protected store: Store<AppState>,
              private fb: FormBuilder,
              private tenantMapSettingsService: TenantMapSettingsService,
              private dialog: MatDialog) {
    super(store);
  }

  ngOnInit(): void {
    this.mapSettings = this.fb.group({
      centerLatitude: [null as number, [Validators.min(-90), Validators.max(90)]],
      centerLongitude: [null as number, [Validators.min(-180), Validators.max(180)]],
      defaultZoom: [null as number, [Validators.min(1), Validators.max(18)]],
      locationName: [null as string, [Validators.maxLength(255)]]
    }, {validators: this.completeViewportValidator});
    this.tenantMapSettingsService.getTenantMapSettings()
      .subscribe(settings => this.setMapSettings(settings));
  }

  save(): void {
    const settings = this.mapSettings.getRawValue() as TenantMapSettings;
    this.tenantMapSettingsService.saveTenantMapSettings(settings)
      .subscribe(saved => this.setMapSettings(saved));
  }

  selectOnMap(): void {
    const settings = this.mapSettings.getRawValue() as TenantMapSettings;
    const configured = isTenantMapSettingsConfigured(settings);
    this.dialog.open<InstallationLocationDialogComponent, InstallationLocationDialogData, InstallationLocationDialogResult>(
      InstallationLocationDialogComponent,
      {
        data: {
          location: configured ? {
            latitude: settings.centerLatitude,
            longitude: settings.centerLongitude
          } : null,
          initialZoom: configured ? settings.defaultZoom : null,
          locationName: settings.locationName,
          titleKey: 'admin.map-settings-select-title',
          hintKey: 'admin.map-settings-select-hint'
        },
        width: '900px',
        maxWidth: '96vw'
      }
    ).afterClosed().pipe(take(1)).subscribe(result => {
      if (result) {
        this.mapSettings.patchValue({
          centerLatitude: result.location.latitude,
          centerLongitude: result.location.longitude,
          defaultZoom: result.zoom,
          locationName: result.locationName
        });
        this.mapSettings.markAsDirty();
      }
    });
  }

  clear(): void {
    this.mapSettings.reset(emptyTenantMapSettings());
    this.mapSettings.markAsDirty();
  }

  confirmForm(): FormGroup {
    return this.mapSettings;
  }

  zoomDescriptionKey(zoom: number | null): string {
    if (!zoom) {
      return 'admin.map-zoom-not-set';
    }
    if (zoom <= 4) {
      return 'admin.map-zoom-country';
    }
    if (zoom <= 7) {
      return 'admin.map-zoom-province';
    }
    if (zoom <= 11) {
      return 'admin.map-zoom-city';
    }
    if (zoom <= 14) {
      return 'admin.map-zoom-district';
    }
    return 'admin.map-zoom-site';
  }

  private setMapSettings(settings: TenantMapSettings): void {
    this.mapSettings.reset(settings || emptyTenantMapSettings());
  }

  private completeViewportValidator(control: AbstractControl): ValidationErrors | null {
    const latitude = control.get('centerLatitude')?.value;
    const longitude = control.get('centerLongitude')?.value;
    const zoom = control.get('defaultZoom')?.value;
    const locationName = control.get('locationName')?.value?.trim();
    const provided = [latitude, longitude, zoom].filter(value => value !== null && value !== undefined && value !== '').length;
    if (provided === 0) {
      return locationName ? {viewportRequiredForName: true} : null;
    }
    return provided === 3 ? null : {completeViewport: true};
  }

}
