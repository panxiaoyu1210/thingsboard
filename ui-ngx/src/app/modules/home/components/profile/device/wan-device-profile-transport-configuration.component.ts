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

import { Component, DestroyRef, forwardRef, Input, OnInit } from '@angular/core';
import {
  AbstractControl,
  ControlValueAccessor,
  FormBuilder,
  FormGroup,
  NG_VALIDATORS,
  NG_VALUE_ACCESSOR,
  ValidationErrors,
  Validator,
  Validators
} from '@angular/forms';
import { WanDeviceProfileTransportConfiguration } from '@shared/models/device.models';
import { WanConnectionService } from '@core/http/wan-connection.service';
import { WanConnection } from '@shared/models/wan-connection.models';
import { PageLink } from '@shared/models/page/page-link';
import { Direction } from '@shared/models/page/sort-order';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

@Component({
  selector: 'tb-wan-device-profile-transport-configuration',
  templateUrl: './wan-device-profile-transport-configuration.component.html',
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => WanDeviceProfileTransportConfigurationComponent),
      multi: true
    },
    {
      provide: NG_VALIDATORS,
      useExisting: forwardRef(() => WanDeviceProfileTransportConfigurationComponent),
      multi: true
    }
  ],
  standalone: false
})
export class WanDeviceProfileTransportConfigurationComponent implements ControlValueAccessor, Validator, OnInit {

  readonly configurationForm: FormGroup;

  connections: WanConnection[] = [];
  loading = false;

  @Input()
  disabled: boolean;

  private propagateChange = (_value: WanDeviceProfileTransportConfiguration) => {};
  private propagateTouched = () => {};
  private validatorChange = () => {};

  constructor(private fb: FormBuilder,
              private wanConnectionService: WanConnectionService,
              private destroyRef: DestroyRef) {
    this.configurationForm = this.fb.group({
      connectionId: [null, Validators.required]
    });
  }

  ngOnInit(): void {
    this.configurationForm.valueChanges.pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(value => {
      this.propagateChange(value as WanDeviceProfileTransportConfiguration);
      this.validatorChange();
    });
    this.loadConnections();
  }

  registerOnChange(fn: (value: WanDeviceProfileTransportConfiguration) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.propagateTouched = fn;
  }

  registerOnValidatorChange(fn: () => void): void {
    this.validatorChange = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (isDisabled) {
      this.configurationForm.disable({emitEvent: false});
    } else {
      this.configurationForm.enable({emitEvent: false});
    }
  }

  writeValue(value: WanDeviceProfileTransportConfiguration | null): void {
    this.configurationForm.patchValue({connectionId: value?.connectionId ?? null}, {emitEvent: false});
  }

  validate(_control: AbstractControl): ValidationErrors | null {
    return this.configurationForm.valid ? null : {wanConnection: true};
  }

  touched(): void {
    this.propagateTouched();
  }

  private loadConnections(): void {
    this.loading = true;
    const pageLink = new PageLink(1000, 0, null, {property: 'name', direction: Direction.ASC});
    this.wanConnectionService.getConnections(pageLink).pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: page => {
        this.connections = page.data;
        this.loading = false;
      },
      error: () => this.loading = false
    });
  }

}
