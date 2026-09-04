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

import { Component, forwardRef, Input } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { WanDeviceProfileTransportConfiguration } from '@shared/models/device.models';

@Component({
  selector: 'tb-wan-device-profile-transport-configuration',
  template: '',
  providers: [{
    provide: NG_VALUE_ACCESSOR,
    useExisting: forwardRef(() => WanDeviceProfileTransportConfigurationComponent),
    multi: true
  }],
  standalone: false
})
export class WanDeviceProfileTransportConfigurationComponent implements ControlValueAccessor {

  @Input()
  disabled: boolean;

  registerOnChange(_fn: (value: WanDeviceProfileTransportConfiguration) => void): void {
  }

  registerOnTouched(_fn: () => void): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
  }

  writeValue(_value: WanDeviceProfileTransportConfiguration | null): void {
  }

}
