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

import { Component, DestroyRef, Input, OnChanges } from '@angular/core';
import { FormBuilder } from '@angular/forms';
import { EntityId } from '@shared/models/id/entity-id';
import { InstallationLocation } from '@shared/models/installation-location.models';
import { InstallationLocationService } from '@core/services/installation-location.service';
import { finalize } from 'rxjs/operators';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { TranslateService } from '@ngx-translate/core';

@Component({
  selector: 'tb-installation-location-editor',
  templateUrl: './installation-location-editor.component.html',
  standalone: false
})
export class InstallationLocationEditorComponent implements OnChanges {

  @Input() entityId: EntityId;
  @Input() readonly = false;

  locationForm = this.fb.group({
    location: [null as InstallationLocation]
  });
  loading = false;

  private originalLocation: InstallationLocation | null;

  constructor(private fb: FormBuilder,
              private installationLocationService: InstallationLocationService,
              private store: Store<AppState>,
              private translate: TranslateService,
              private destroyRef: DestroyRef) {
  }

  ngOnChanges(): void {
    if (this.entityId?.id) {
      this.loadLocation();
    }
  }

  save(): void {
    if (!this.entityId?.id || this.locationForm.invalid || this.readonly || this.loading) {
      return;
    }
    const location = this.locationForm.get('location').value;
    this.loading = true;
    this.locationForm.disable({emitEvent: false});
    this.installationLocationService.saveLocation(this.entityId, location).pipe(
      finalize(() => {
        this.loading = false;
        if (!this.readonly) {
          this.locationForm.enable({emitEvent: false});
        }
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: () => {
        this.originalLocation = location ? {...location} : null;
        this.locationForm.markAsPristine();
        this.store.dispatch(new ActionNotificationShow({
          message: this.translate.instant('device.wan.location-save-success'),
          type: 'success'
        }));
      },
      error: () => this.store.dispatch(new ActionNotificationShow({
        message: this.translate.instant('device.wan.location-save-failed'),
        type: 'error'
      }))
    });
  }

  cancel(): void {
    this.locationForm.get('location').setValue(this.originalLocation ? {...this.originalLocation} : null);
    this.locationForm.markAsPristine();
  }

  private loadLocation(): void {
    this.loading = true;
    this.locationForm.disable({emitEvent: false});
    this.installationLocationService.getLocation(this.entityId).pipe(
      finalize(() => this.loading = false),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: location => {
        this.originalLocation = location.latitude === null ? null : location;
        this.locationForm.get('location').setValue(this.originalLocation, {emitEvent: false});
        this.locationForm.markAsPristine();
        if (!this.readonly) {
          this.locationForm.enable({emitEvent: false});
        }
      },
      error: () => this.store.dispatch(new ActionNotificationShow({
        message: this.translate.instant('device.wan.location-load-failed'),
        type: 'error'
      }))
    });
  }
}
