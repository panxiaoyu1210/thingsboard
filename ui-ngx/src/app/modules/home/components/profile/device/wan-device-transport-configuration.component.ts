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
  NG_VALIDATORS,
  NG_VALUE_ACCESSOR,
  UntypedFormArray,
  UntypedFormBuilder,
  UntypedFormGroup,
  ValidationErrors,
  Validator,
  Validators
} from '@angular/forms';
import { coerceBooleanProperty } from '@angular/cdk/coercion';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  DeviceTransportType,
  WanDeviceTransportConfiguration,
  WanDeviceType,
  WanGatewayConfiguration,
  WanRateConfiguration,
  WanTerminalConfiguration
} from '@shared/models/device.models';
import { EntityType } from '@shared/models/entity-type.models';

const maxPacketLengthForRateMode = (rateMode: number): number => {
  if (rateMode <= 3) {
    return 245;
  }
  if (rateMode <= 6) {
    return 401;
  }
  return 585;
};

@Component({
  selector: 'tb-wan-device-transport-configuration',
  templateUrl: './wan-device-transport-configuration.component.html',
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => WanDeviceTransportConfigurationComponent),
      multi: true
    },
    {
      provide: NG_VALIDATORS,
      useExisting: forwardRef(() => WanDeviceTransportConfigurationComponent),
      multi: true
    }
  ],
  standalone: false
})
export class WanDeviceTransportConfigurationComponent implements ControlValueAccessor, OnInit, Validator {

  readonly entityType = EntityType;
  readonly terminalTypes = [0, 1];
  readonly securityModes = [0, 1, 2, 3, 4, 5];
  readonly frequencyMajorOptions = Array.from({length: 10}, (_, index) => index + 1);
  readonly frequencyMinorOptions = Array.from({length: 8}, (_, index) => index + 1);
  readonly rateCountOptions = [1, 2, 3, 4];
  readonly rateModeOptions = [0, 1, 2, 3, 4, 5, 6, 7];

  formGroup: UntypedFormGroup;

  private disabled = false;
  private gatewayDevice = false;
  private propagateChange = (_value: WanDeviceTransportConfiguration): void => {};

  @Input()
  set isGateway(value: boolean) {
    const gateway = coerceBooleanProperty(value);
    if (this.gatewayDevice !== gateway) {
      this.gatewayDevice = gateway;
      if (this.formGroup) {
        this.applyDeviceType(true);
      }
    }
  }

  get isGateway(): boolean {
    return this.gatewayDevice;
  }

  get rateConfigurations(): UntypedFormArray {
    return this.formGroup.get('gateway.rateCfgs') as UntypedFormArray;
  }

  constructor(private fb: UntypedFormBuilder,
              private destroyRef: DestroyRef) {
  }

  ngOnInit(): void {
    this.formGroup = this.fb.group({
      deviceType: [WanDeviceType.TERMINAL, Validators.required],
      gateway: this.createGatewayGroup(),
      terminal: this.createTerminalGroup()
    });
    this.formGroup.get('gateway.rateNum').valueChanges.pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(rateCount => this.resizeRateConfigurations(rateCount));
    this.applyDeviceType(false);
    this.formGroup.valueChanges.pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(() => this.updateModel());
  }

  registerOnChange(fn: (value: WanDeviceTransportConfiguration) => void): void {
    this.propagateChange = fn;
  }

  registerOnTouched(_fn: () => void): void {
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (this.formGroup) {
      this.applyDeviceType(false);
    }
  }

  writeValue(value: WanDeviceTransportConfiguration | null): void {
    if (!value || !this.formGroup) {
      return;
    }
    if (value.gateway) {
      const rateConfigurations = value.gateway.rateCfgs?.length
        ? value.gateway.rateCfgs
        : [{rateMode: 0, uplinkLen: 100, downlinkLen: 100}];
      const rateNum = value.gateway.rateNum ?? rateConfigurations.length;
      this.formGroup.get('gateway').patchValue({...value.gateway, rateNum}, {emitEvent: false});
      this.setRateConfigurations(rateConfigurations);
      this.resizeRateConfigurations(rateNum);
    }
    if (value.terminal) {
      this.formGroup.get('terminal').patchValue(value.terminal, {emitEvent: false});
    }
    this.applyDeviceType(false);
  }

  validate(): ValidationErrors | null {
    if (!this.formGroup) {
      return null;
    }
    const activeGroup = this.formGroup.get(this.gatewayDevice ? 'gateway' : 'terminal');
    return activeGroup.valid ? null : {wanDeviceTransportConfiguration: false};
  }

  maxPacketLength(rateMode: number): number {
    return maxPacketLengthForRateMode(rateMode);
  }

  private createGatewayGroup(): UntypedFormGroup {
    return this.fb.group({
      gwId: ['', [Validators.required, Validators.pattern(/^[0-9A-Fa-f]{16}$/)]],
      freqMajor: [1, [Validators.required, Validators.min(1), Validators.max(10)]],
      freqMinor: [1, [Validators.required, Validators.min(1), Validators.max(8)]],
      nwkNum: [1, [Validators.required, Validators.min(1), Validators.max(32)]],
      tddNum: [1, [Validators.required, Validators.min(1), Validators.max(255)]],
      rateNum: [1, [Validators.required, Validators.min(1), Validators.max(4)]],
      rateCfgs: this.fb.array([this.createRateConfigurationGroup()])
    }, {validators: this.uniqueRateModes});
  }

  private createTerminalGroup(): UntypedFormGroup {
    return this.fb.group({
      devEui: ['', [Validators.required, Validators.pattern(/^[0-9A-Fa-f]{16}$/)]],
      devType: [0, [Validators.required, Validators.min(0), Validators.max(1)]],
      securityMode: [0, [Validators.required, Validators.min(0), Validators.max(5)]],
      relatedGatewayId: [null]
    });
  }

  private createRateConfigurationGroup(value?: WanRateConfiguration): UntypedFormGroup {
    return this.fb.group({
      rateMode: [value?.rateMode ?? 0, [Validators.required, Validators.min(0), Validators.max(7)]],
      uplinkLen: [value?.uplinkLen ?? 100, Validators.required],
      downlinkLen: [value?.downlinkLen ?? 100, Validators.required]
    }, {validators: this.packetLengthRange});
  }

  private setRateConfigurations(values?: WanRateConfiguration[]): void {
    this.rateConfigurations.clear({emitEvent: false});
    const configurations = values?.length ? values : [{rateMode: 0, uplinkLen: 100, downlinkLen: 100}];
    configurations.forEach(value => this.rateConfigurations.push(this.createRateConfigurationGroup(value), {emitEvent: false}));
  }

  private resizeRateConfigurations(rateCount: number): void {
    if (!Number.isInteger(rateCount) || rateCount < 1 || rateCount > 4) {
      return;
    }
    while (this.rateConfigurations.length > rateCount) {
      this.rateConfigurations.removeAt(this.rateConfigurations.length - 1, {emitEvent: false});
    }
    while (this.rateConfigurations.length < rateCount) {
      this.rateConfigurations.push(this.createRateConfigurationGroup({
        rateMode: this.nextAvailableRateMode(),
        uplinkLen: 100,
        downlinkLen: 100
      }), {emitEvent: false});
    }
    this.rateConfigurations.updateValueAndValidity({emitEvent: false});
    this.formGroup.get('gateway').updateValueAndValidity({emitEvent: false});
  }

  private nextAvailableRateMode(): number {
    const usedModes = new Set<number>(this.rateConfigurations.getRawValue()
      .map((configuration: WanRateConfiguration) => configuration.rateMode));
    return this.rateModeOptions.find(rateMode => !usedModes.has(rateMode)) ?? 0;
  }

  private applyDeviceType(resetValue: boolean): void {
    const gateway = this.formGroup.get('gateway');
    const terminal = this.formGroup.get('terminal');
    this.formGroup.get('deviceType').setValue(
      this.gatewayDevice ? WanDeviceType.GATEWAY : WanDeviceType.TERMINAL,
      {emitEvent: false}
    );
    if (this.disabled) {
      this.formGroup.disable({emitEvent: false});
    } else if (this.gatewayDevice) {
      gateway.enable({emitEvent: false});
      terminal.disable({emitEvent: false});
    } else {
      terminal.enable({emitEvent: false});
      gateway.disable({emitEvent: false});
    }
    if (resetValue) {
      this.updateModel();
    }
  }

  private updateModel(): void {
    const value: WanDeviceTransportConfiguration & {type: DeviceTransportType.WAN} = this.gatewayDevice ? {
      type: DeviceTransportType.WAN,
      deviceType: WanDeviceType.GATEWAY,
      gateway: {
        ...(this.formGroup.get('gateway').getRawValue() as WanGatewayConfiguration),
        rateNum: this.rateConfigurations.length
      },
      terminal: null
    } : {
      type: DeviceTransportType.WAN,
      deviceType: WanDeviceType.TERMINAL,
      gateway: null,
      terminal: this.formGroup.get('terminal').getRawValue() as WanTerminalConfiguration
    };
    this.propagateChange(value);
  }

  private packetLengthRange(control: AbstractControl): ValidationErrors | null {
    const mode = control.get('rateMode')?.value;
    const uplinkLen = control.get('uplinkLen')?.value;
    const downlinkLen = control.get('downlinkLen')?.value;
    const maxLength = maxPacketLengthForRateMode(mode);
    const valid = mode >= 0 && mode <= 7
      && uplinkLen >= 1 && uplinkLen <= maxLength
      && downlinkLen >= 1 && downlinkLen <= maxLength;
    return valid ? null : {packetLengthRange: true};
  }

  private uniqueRateModes(control: AbstractControl): ValidationErrors | null {
    const rateCfgs = control.get('rateCfgs')?.value as WanRateConfiguration[];
    const modes = rateCfgs?.map(config => config.rateMode) ?? [];
    return new Set(modes).size === modes.length ? null : {duplicateRateMode: true};
  }

}
