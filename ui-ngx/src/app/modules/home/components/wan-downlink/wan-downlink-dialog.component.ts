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

import { Component, Inject, OnInit } from '@angular/core';
import { AbstractControl, FormBuilder, FormGroup, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Store } from '@ngrx/store';
import { TranslateService } from '@ngx-translate/core';
import { filter, finalize, switchMap } from 'rxjs/operators';
import { AppState } from '@core/core.state';
import { WanDownlinkService } from '@core/http/wan-downlink.service';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { DialogService } from '@core/services/dialog.service';
import { Device, DeviceTransportType, WanDeviceType } from '@shared/models/device.models';
import {
  normalizeWanHexData,
  WanDownlinkDialogData,
  WanDownlinkMode,
  WanDownlinkSendResult,
  WanRawDownlinkCommand,
  WAN_BROADCAST_MAX_BYTES
} from '@shared/models/wan-downlink.models';

@Component({
  selector: 'tb-wan-downlink-dialog',
  templateUrl: './wan-downlink-dialog.component.html',
  styleUrls: ['./wan-downlink-dialog.component.scss'],
  standalone: false
})
export class WanDownlinkDialogComponent implements OnInit {

  readonly modes = WanDownlinkMode;
  readonly commandForm: FormGroup;

  device: Device;
  deviceType: WanDeviceType;
  loading = true;
  sending = false;
  errorMessage: string;

  constructor(private fb: FormBuilder,
              private wanDownlinkService: WanDownlinkService,
              private dialogs: DialogService,
              private translate: TranslateService,
              private store: Store<AppState>,
              private dialogRef: MatDialogRef<WanDownlinkDialogComponent, WanDownlinkSendResult>,
              @Inject(MAT_DIALOG_DATA) public data: WanDownlinkDialogData) {
    this.commandForm = this.fb.group({
      mode: [WanDownlinkMode.UNICAST, Validators.required],
      port: [1, [Validators.required, Validators.pattern(/^\d+$/), Validators.min(0), Validators.max(255)]],
      data: ['', [this.hexDataValidator()]],
      reason: ['', [Validators.maxLength(500)]]
    });
  }

  ngOnInit(): void {
    this.wanDownlinkService.getDevice(this.data.deviceId).pipe(
      finalize(() => this.loading = false)
    ).subscribe({
      next: device => {
        const transport = device.deviceData?.transportConfiguration;
        if (transport?.type !== DeviceTransportType.WAN || !transport.deviceType) {
          this.errorMessage = this.translate.instant('device.wan.downlink-not-wan');
          return;
        }
        this.device = device;
        this.deviceType = transport.deviceType;
        if (this.isGateway) {
          this.commandForm.patchValue({mode: WanDownlinkMode.GATEWAY_BROADCAST});
          this.commandForm.get('port').disable();
        }
        this.commandForm.get('data').updateValueAndValidity();
      },
      error: () => this.errorMessage = this.translate.instant('device.wan.downlink-device-load-failed')
    });
  }

  get isGateway(): boolean {
    return this.deviceType === WanDeviceType.GATEWAY;
  }

  get isNetworkBroadcast(): boolean {
    return this.commandForm.get('mode').value === WanDownlinkMode.NETWORK_BROADCAST;
  }

  get byteCount(): number {
    return normalizeWanHexData(this.commandForm.get('data').value).length / 2;
  }

  cancel(): void {
    if (!this.sending) {
      this.dialogRef.close();
    }
  }

  send(): void {
    if (!this.device || this.commandForm.invalid || this.sending) {
      this.commandForm.markAllAsTouched();
      return;
    }
    const command = this.buildCommand();
    this.dialogs.confirm(
      this.translate.instant(this.isNetworkBroadcast
        ? 'device.wan.downlink-network-confirm-title'
        : 'device.wan.downlink-confirm-title'),
      this.translate.instant(this.isNetworkBroadcast
        ? 'device.wan.downlink-network-confirm-message'
        : 'device.wan.downlink-confirm-message', {
          device: this.device.name,
          bytes: this.byteCount,
          port: command.port
        }),
      this.translate.instant('action.cancel'),
      this.translate.instant('device.wan.send-message')
    ).pipe(
      filter(Boolean),
      switchMap(() => {
        this.sending = true;
        this.errorMessage = null;
        return this.wanDownlinkService.sendRawCommand(this.device, command, this.data.origin).pipe(
          finalize(() => this.sending = false)
        );
      })
    ).subscribe({
      next: result => {
        this.store.dispatch(new ActionNotificationShow({
          message: this.translate.instant('device.wan.downlink-sent'),
          type: 'success',
          duration: 3000
        }));
        this.dialogRef.close(result);
      },
      error: error => {
        this.errorMessage = error?.error?.message || error?.message ||
          this.translate.instant('device.wan.downlink-send-failed');
      }
    });
  }

  private buildCommand(): WanRawDownlinkCommand {
    const value = this.commandForm.getRawValue();
    return {
      data: normalizeWanHexData(value.data),
      port: this.isGateway ? undefined : Number(value.port),
      mode: this.isGateway ? value.mode : WanDownlinkMode.UNICAST,
      reason: value.reason
    };
  }

  private hexDataValidator(): ValidatorFn {
    return (control: AbstractControl): ValidationErrors | null => {
      const value = normalizeWanHexData(control.value);
      if (!value) {
        return {required: true};
      }
      if (!/^[0-9A-F]+$/.test(value)) {
        return {hex: true};
      }
      if ((value.length & 1) !== 0) {
        return {evenLength: true};
      }
      if (this.isGateway && value.length / 2 > WAN_BROADCAST_MAX_BYTES) {
        return {broadcastMaxLength: true};
      }
      return null;
    };
  }
}
