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

import { Component, ViewChild } from '@angular/core';
import { MatDialogRef } from '@angular/material/dialog';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { DialogComponent } from '@shared/components/dialog.component';
import { Router } from '@angular/router';
import {
  createDeviceConfiguration,
  createDeviceTransportConfiguration,
  Device,
  DeviceCredentialsType,
  DeviceProfileInfo,
  DeviceProfileType,
  DeviceTransportType,
  WanDeviceTransportConfiguration,
  WanDeviceType
} from '@shared/models/device.models';
import { MatStepper, StepperOrientation } from '@angular/material/stepper';
import { EntityType } from '@shared/models/entity-type.models';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { DeviceService } from '@core/http/device.service';
import { StepperSelectionEvent } from '@angular/cdk/stepper';
import { BreakpointObserver } from '@angular/cdk/layout';
import { MediaBreakpoints } from '@shared/models/constants';
import { deepTrim } from '@core/utils';
import { CustomerId } from '@shared/models/id/customer-id';
import { HttpErrorResponse } from '@angular/common/http';

@Component({
    selector: 'tb-device-wizard',
    templateUrl: './device-wizard-dialog.component.html',
    styleUrls: ['./device-wizard-dialog.component.scss'],
    standalone: false
})
export class DeviceWizardDialogComponent extends DialogComponent<DeviceWizardDialogComponent, Device> {

  @ViewChild('addDeviceWizardStepper', {static: true}) addDeviceWizardStepper: MatStepper;

  stepperOrientation: Observable<StepperOrientation>;

  stepperLabelPosition: Observable<'bottom' | 'end'>;

  selectedIndex = 0;

  credentialsOptionalStep = true;

  showNext = true;

  entityType = EntityType;

  readonly DeviceTransportType = DeviceTransportType;

  deviceWizardFormGroup: FormGroup;

  credentialsFormGroup: FormGroup;

  private currentDeviceProfileTransportType = DeviceTransportType.DEFAULT;

  constructor(protected store: Store<AppState>,
              protected router: Router,
              public dialogRef: MatDialogRef<DeviceWizardDialogComponent, Device>,
              private deviceService: DeviceService,
              private breakpointObserver: BreakpointObserver,
              private fb: FormBuilder) {
    super(store, router, dialogRef);

    this.stepperOrientation = this.breakpointObserver.observe(MediaBreakpoints['gt-sm'])
      .pipe(map(({matches}) => matches ? 'horizontal' : 'vertical'));

    this.stepperLabelPosition = this.breakpointObserver.observe(MediaBreakpoints['gt-sm'])
      .pipe(map(({matches}) => matches ? 'end' : 'bottom'));

    this.deviceWizardFormGroup = this.fb.group({
        name: ['', [Validators.required, Validators.maxLength(255)]],
        label: ['', Validators.maxLength(255)],
        gateway: [false],
        overwriteActivityTime: [false],
        customerId: [null],
        deviceProfileId: [null, Validators.required],
        wanTransportConfiguration: [null],
        description: ['']
      }
    );

    this.credentialsFormGroup  = this.fb.group({
        credential: []
      }
    );
    this.deviceWizardFormGroup.get('gateway').valueChanges.subscribe(() => this.configureWanDevice());
    this.deviceWizardFormGroup.get('wanTransportConfiguration').valueChanges.subscribe(
      configuration => this.synchronizeWanCredentials(configuration)
    );
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  previousStep(): void {
    this.addDeviceWizardStepper.previous();
  }

  nextStep(): void {
    this.addDeviceWizardStepper.next();
  }

  getFormLabel(index: number): string {
    switch (index) {
      case 0:
        return 'device.wizard.device-details';
      case 1:
        return 'device.credentials';
    }
  }

  get maxStepperIndex(): number {
    return this.addDeviceWizardStepper?._steps?.length - 1;
  }

  add(): void {
    if (this.allValid()) {
      this.createDevice().subscribe(
        (device) => this.dialogRef.close(device)
      );
    }
  }

  get deviceTransportType(): DeviceTransportType {
    return this.currentDeviceProfileTransportType;
  }

  get wanRootKeyRequired(): boolean {
    const configuration = this.deviceWizardFormGroup.get('wanTransportConfiguration').value as WanDeviceTransportConfiguration;
    return configuration?.deviceType === WanDeviceType.TERMINAL && configuration.terminal?.securityMode !== 0;
  }

  get wanRootKeyVisible(): boolean {
    const configuration = this.deviceWizardFormGroup.get('wanTransportConfiguration').value as WanDeviceTransportConfiguration;
    return configuration?.deviceType === WanDeviceType.TERMINAL;
  }

  deviceProfileChanged(deviceProfile: DeviceProfileInfo) {
    if (deviceProfile) {
      this.currentDeviceProfileTransportType = deviceProfile.transportType;
      this.credentialsOptionalStep = this.currentDeviceProfileTransportType !== DeviceTransportType.LWM2M
        && this.currentDeviceProfileTransportType !== DeviceTransportType.WAN;
      this.configureWanDevice();
      if (this.currentDeviceProfileTransportType === DeviceTransportType.WAN) {
        this.selectedIndex = 0;
        this.addDeviceWizardStepper.selectedIndex = 0;
        this.showNext = false;
      } else {
        this.showNext = true;
      }
    }
  }

  private createDevice(): Observable<Device> {
    const device: Device = {
      name: this.deviceWizardFormGroup.get('name').value,
      label: this.deviceWizardFormGroup.get('label').value,
      deviceProfileId: this.deviceWizardFormGroup.get('deviceProfileId').value,
      additionalInfo: {
        gateway: this.deviceWizardFormGroup.get('gateway').value,
        overwriteActivityTime: this.deviceWizardFormGroup.get('overwriteActivityTime').value,
        description: this.deviceWizardFormGroup.get('description').value
      },
      customerId: this.deviceWizardFormGroup.get('customerId').value
    };
    if (this.currentDeviceProfileTransportType === DeviceTransportType.WAN) {
      device.deviceData = {
        configuration: createDeviceConfiguration(DeviceProfileType.DEFAULT),
        transportConfiguration: this.deviceWizardFormGroup.get('wanTransportConfiguration').value
      };
    }
    if (this.currentDeviceProfileTransportType === DeviceTransportType.WAN
        || this.addDeviceWizardStepper.steps.last.completed || this.addDeviceWizardStepper.selectedIndex > 0) {
      return this.deviceService.saveDeviceWithCredentials(deepTrim(device), deepTrim(this.credentialsFormGroup.value.credential)).pipe(
        catchError((e: HttpErrorResponse) => {
          if (e.error.message.includes('Device credentials')
              && this.currentDeviceProfileTransportType !== DeviceTransportType.WAN) {
            this.addDeviceWizardStepper.selectedIndex = 1;
          } else {
            this.addDeviceWizardStepper.selectedIndex = 0;
          }
          return throwError(() => e);
        })
      );
    }
    return this.deviceService.saveDevice(deepTrim(device)).pipe(
      catchError(e => {
        this.addDeviceWizardStepper.selectedIndex = 0;
        return throwError(e);
      })
    );
  }

  allValid(): boolean {
    const invalidStep = this.addDeviceWizardStepper.steps.find((item, index) => {
      if (item.stepControl.invalid) {
        item.interacted = true;
        this.addDeviceWizardStepper.selectedIndex = index;
        return true;
      } else {
        return false;
      }
    });
    if (invalidStep) {
      return false;
    }
    if (this.currentDeviceProfileTransportType === DeviceTransportType.WAN
        && this.wanRootKeyVisible && this.credentialsFormGroup.invalid) {
      this.credentialsFormGroup.markAllAsTouched();
      this.addDeviceWizardStepper.selectedIndex = 0;
      return false;
    }
    return true;
  }

  changeStep($event: StepperSelectionEvent): void {
    this.selectedIndex = $event.selectedIndex;
    this.showNext = this.selectedIndex !== this.maxStepperIndex;
  }

  private configureWanDevice(): void {
    const control = this.deviceWizardFormGroup.get('wanTransportConfiguration');
    if (this.currentDeviceProfileTransportType === DeviceTransportType.WAN) {
      control.setValidators(Validators.required);
      control.setValue(createDeviceTransportConfiguration(
        DeviceTransportType.WAN,
        this.deviceWizardFormGroup.get('gateway').value
      ));
    } else {
      control.clearValidators();
      control.setValue(null);
    }
    control.updateValueAndValidity({emitEvent: false});
  }

  private synchronizeWanCredentials(configuration: WanDeviceTransportConfiguration | null): void {
    if (!configuration || this.currentDeviceProfileTransportType !== DeviceTransportType.WAN) {
      return;
    }
    const currentCredentials = this.credentialsFormGroup.get('credential').value;
    const credentialsId = configuration.deviceType === WanDeviceType.GATEWAY
      ? configuration.gateway?.gwId
      : configuration.terminal?.devEui;
    const credentialsValue = configuration.deviceType === WanDeviceType.GATEWAY
      ? JSON.stringify({rootKey: ''})
      : currentCredentials?.credentialsValue ?? JSON.stringify({rootKey: ''});
    this.credentialsFormGroup.get('credential').setValue({
      ...currentCredentials,
      credentialsType: DeviceCredentialsType.WAN_CREDENTIALS,
      credentialsId,
      credentialsValue
    }, {emitEvent: false});
  }
}
