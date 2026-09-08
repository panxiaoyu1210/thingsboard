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

import { Component, DestroyRef, forwardRef } from '@angular/core';
import {
  AbstractControl,
  ControlValueAccessor,
  FormBuilder,
  NG_VALIDATORS,
  NG_VALUE_ACCESSOR,
  ValidationErrors,
  Validator,
  Validators
} from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import {
  emptyInstallationLocation,
  InstallationLocation,
  isInstallationLocationEmpty
} from '@shared/models/installation-location.models';
import {
  InstallationLocationDialogComponent
} from '@shared/components/installation-location/installation-location-dialog.component';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { take } from 'rxjs/operators';

@Component({
  selector: 'tb-installation-location-picker',
  templateUrl: './installation-location-picker.component.html',
  styleUrls: ['./installation-location-picker.component.scss'],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => InstallationLocationPickerComponent),
      multi: true
    },
    {
      provide: NG_VALIDATORS,
      useExisting: forwardRef(() => InstallationLocationPickerComponent),
      multi: true
    }
  ],
  standalone: false
})
export class InstallationLocationPickerComponent implements ControlValueAccessor, Validator {

  locationForm = this.fb.group({
    latitude: [null as number, [Validators.min(-90), Validators.max(90)]],
    longitude: [null as number, [Validators.min(-180), Validators.max(180)]]
  }, {validators: this.coordinatePairValidator});

  private propagateChange: (value: InstallationLocation | null) => void = () => {};
  private propagateTouched: () => void = () => {};

  constructor(private fb: FormBuilder,
              private dialog: MatDialog,
              private destroyRef: DestroyRef) {
    this.locationForm.valueChanges.pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(value => {
      const location = value as InstallationLocation;
      this.propagateChange(isInstallationLocationEmpty(location) ? null : location);
    });
  }

  registerOnChange(fn: (value: InstallationLocation | null) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.propagateTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    if (disabled) {
      this.locationForm.disable({emitEvent: false});
    } else {
      this.locationForm.enable({emitEvent: false});
    }
  }

  writeValue(value: InstallationLocation | null): void {
    this.locationForm.patchValue(value || emptyInstallationLocation(), {emitEvent: false});
  }

  validate(_control: AbstractControl): ValidationErrors | null {
    return this.locationForm.valid ? null : {installationLocation: true};
  }

  openMap(): void {
    const location = this.locationForm.getRawValue() as InstallationLocation;
    this.dialog.open<InstallationLocationDialogComponent, InstallationLocation, InstallationLocation>(
      InstallationLocationDialogComponent,
      {
        data: isInstallationLocationEmpty(location) ? null : location,
        width: '900px',
        maxWidth: '96vw'
      }
    ).afterClosed().pipe(take(1)).subscribe(result => {
      if (result) {
        this.locationForm.patchValue(result);
        this.locationForm.markAsDirty();
        this.propagateTouched();
      }
    });
  }

  clearLocation(): void {
    this.locationForm.reset(emptyInstallationLocation());
    this.locationForm.markAsDirty();
    this.propagateTouched();
  }

  markTouched(): void {
    this.propagateTouched();
  }

  private coordinatePairValidator(control: AbstractControl): ValidationErrors | null {
    const latitude = control.get('latitude')?.value;
    const longitude = control.get('longitude')?.value;
    const hasLatitude = latitude !== null && latitude !== undefined && latitude !== '';
    const hasLongitude = longitude !== null && longitude !== undefined && longitude !== '';
    return hasLatitude === hasLongitude ? null : {coordinatePair: true};
  }
}
