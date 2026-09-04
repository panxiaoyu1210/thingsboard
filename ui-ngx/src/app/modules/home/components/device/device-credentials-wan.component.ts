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

import { Component, DestroyRef, forwardRef, Input } from '@angular/core';
import {
  ControlValueAccessor,
  NG_VALIDATORS,
  NG_VALUE_ACCESSOR,
  UntypedFormBuilder,
  UntypedFormGroup,
  ValidationErrors,
  Validator,
  Validators
} from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { WanDeviceCredentials } from '@shared/models/device.models';
import { isDefinedAndNotNull, isEmptyStr } from '@core/utils';

@Component({
  selector: 'tb-device-credentials-wan',
  templateUrl: './device-credentials-wan.component.html',
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => DeviceCredentialsWanComponent),
      multi: true
    },
    {
      provide: NG_VALIDATORS,
      useExisting: forwardRef(() => DeviceCredentialsWanComponent),
      multi: true
    }
  ],
  standalone: false
})
export class DeviceCredentialsWanComponent implements ControlValueAccessor, Validator {

  @Input()
  disabled: boolean;

  private rootKeyRequiredValue = false;

  @Input()
  set rootKeyRequired(value: boolean) {
    this.rootKeyRequiredValue = value;
    this.updateValidators();
  }

  get rootKeyRequired(): boolean {
    return this.rootKeyRequiredValue;
  }

  readonly formGroup: UntypedFormGroup;
  private propagateChange = (_value: string): void => {};
  private validatorChange = (): void => {};

  constructor(fb: UntypedFormBuilder,
              destroyRef: DestroyRef) {
    this.formGroup = fb.group({
      rootKey: ['', Validators.pattern(/^$|^[0-9A-Fa-f]{32}$/)]
    });
    this.formGroup.valueChanges.pipe(
      takeUntilDestroyed(destroyRef)
    ).subscribe(value => this.propagateChange(JSON.stringify(value)));
    this.formGroup.statusChanges.pipe(
      takeUntilDestroyed(destroyRef)
    ).subscribe(() => this.validatorChange());
  }

  registerOnChange(fn: (value: string) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(_fn: () => void): void {
  }

  registerOnValidatorChange(fn: () => void): void {
    this.validatorChange = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (isDisabled) {
      this.formGroup.disable({emitEvent: false});
    } else {
      this.formGroup.enable({emitEvent: false});
    }
  }

  writeValue(value: string | null): void {
    if (isDefinedAndNotNull(value) && !isEmptyStr(value)) {
      this.formGroup.patchValue(JSON.parse(value) as WanDeviceCredentials, {emitEvent: false});
    } else {
      this.formGroup.patchValue({rootKey: ''}, {emitEvent: false});
    }
  }

  validate(): ValidationErrors | null {
    return this.formGroup.valid ? null : {wanDeviceCredentials: false};
  }

  private updateValidators(): void {
    const validators = [Validators.pattern(/^[0-9A-Fa-f]{32}$/)];
    if (this.rootKeyRequiredValue) {
      validators.unshift(Validators.required);
    }
    const rootKey = this.formGroup.get('rootKey');
    rootKey.setValidators(validators);
    rootKey.updateValueAndValidity({emitEvent: false});
    this.validatorChange();
  }

}
