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

import { Component, DestroyRef, Input, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { UntypedFormControl, Validators } from '@angular/forms';
import { WanDownlinkDialogService } from '@home/components/wan-downlink/wan-downlink-dialog.service';
import { WidgetContext } from '@home/models/widget-component.models';
import {
  WanWidgetDeviceOption,
  WanWidgetDeviceService
} from '@home/components/widget/lib/wan/wan-widget-device.service';
import { EntityType } from '@shared/models/entity-type.models';
import { WanDeviceType } from '@shared/models/device.models';
import { WanDownlinkOrigin } from '@shared/models/wan-downlink.models';

@Component({
  selector: 'tb-wan-downlink-control-widget',
  templateUrl: './wan-downlink-control-widget.component.html',
  styleUrls: ['./wan-downlink-control-widget.component.scss'],
  standalone: false
})
export class WanDownlinkControlWidgetComponent implements OnInit {

  @Input()
  ctx: WidgetContext;

  readonly deviceControl = new UntypedFormControl(null, Validators.required);
  readonly deviceType = WanDeviceType;

  devices: WanWidgetDeviceOption[] = [];
  loadingDevices = false;
  deviceLoadFailed = false;

  private configuredTargetDeviceId: string;

  constructor(private wanWidgetDeviceService: WanWidgetDeviceService,
              private wanDownlinkDialog: WanDownlinkDialogService,
              private destroyRef: DestroyRef) {
  }

  ngOnInit(): void {
    this.ctx.$scope.wanDownlinkControlWidget = this;
    const configuredTarget = this.ctx.defaultSubscription?.targetEntityId;
    this.configuredTargetDeviceId = configuredTarget?.entityType === EntityType.DEVICE ? configuredTarget.id : null;
    this.loadDevices();
  }

  loadDevices(): void {
    if (this.loadingDevices) {
      return;
    }
    const preferredDeviceId = this.deviceControl.value || this.configuredTargetDeviceId;
    this.loadingDevices = true;
    this.deviceLoadFailed = false;
    this.devices = [];
    this.deviceControl.disable({emitEvent: false});
    this.wanWidgetDeviceService.getAvailableDevices(this.ctx.currentUser).pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: devices => {
        this.devices = devices;
        const selectedDeviceId = devices.some(device => device.id === preferredDeviceId) ? preferredDeviceId : null;
        this.deviceControl.setValue(selectedDeviceId, {emitEvent: false});
        this.deviceControl.enable({emitEvent: false});
        this.loadingDevices = false;
        this.ctx.detectChanges();
      },
      error: () => {
        this.deviceLoadFailed = true;
        this.deviceControl.enable({emitEvent: false});
        this.loadingDevices = false;
        this.ctx.detectChanges();
      }
    });
  }

  openDownlink(): void {
    const selectedDevice = this.selectedDevice;
    if (!selectedDevice) {
      this.deviceControl.markAsTouched();
      return;
    }
    this.wanDownlinkDialog.open({
      deviceId: selectedDevice.id,
      deviceName: selectedDevice.name,
      origin: WanDownlinkOrigin.DASHBOARD
    }).pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe();
  }

  get selectedDevice(): WanWidgetDeviceOption | null {
    return this.devices.find(device => device.id === this.deviceControl.value) || null;
  }
}
