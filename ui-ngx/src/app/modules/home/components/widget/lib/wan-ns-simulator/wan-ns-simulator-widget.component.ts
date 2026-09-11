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

import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, Input, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { WidgetContext } from '@home/models/widget-component.models';
import { WanDeviceType } from '@shared/models/device.models';
import { TranslateService } from '@ngx-translate/core';
import { finalize, timeout } from 'rxjs/operators';
import {
  buildWanNsSimulatorUrl,
  evenLengthHexValidator,
  wanNsSimulatorAlarmData,
  wanNsSimulatorDefaultSettings,
  WanNsSimulatorTemplate,
  WanNsSimulatorWidgetSettings
} from './wan-ns-simulator-widget.models';
import {
  WanWidgetDeviceOption,
  WanWidgetDeviceService
} from '@home/components/widget/lib/wan/wan-widget-device.service';

interface WanNsSimulatorResult {
  success: boolean;
  message: string;
  response?: string;
  time: number;
}

@Component({
  selector: 'tb-wan-ns-simulator-widget',
  templateUrl: './wan-ns-simulator-widget.component.html',
  styleUrls: ['./wan-ns-simulator-widget.component.scss'],
  standalone: false
})
export class WanNsSimulatorWidgetComponent implements OnInit {

  @Input()
  ctx: WidgetContext;

  readonly template = WanNsSimulatorTemplate;

  simulatorForm: UntypedFormGroup;
  devices: WanWidgetDeviceOption[] = [];
  loadingDevices = false;
  sending = false;
  deviceLoadFailed = false;
  result: WanNsSimulatorResult;

  private settings: WanNsSimulatorWidgetSettings;

  constructor(private fb: UntypedFormBuilder,
              private http: HttpClient,
              private wanWidgetDeviceService: WanWidgetDeviceService,
              private translate: TranslateService,
              private destroyRef: DestroyRef) {
  }

  ngOnInit(): void {
    this.ctx.$scope.wanNsSimulatorWidget = this;
    this.settings = {...wanNsSimulatorDefaultSettings, ...(this.ctx.settings || {})};
    this.simulatorForm = this.fb.group({
      deviceId: [null, Validators.required],
      dataTemplate: [WanNsSimulatorTemplate.CUSTOM, Validators.required],
      dataHex: ['', [Validators.required, evenLengthHexValidator]],
      fPort: [10, [Validators.required, Validators.min(1), Validators.max(255), Validators.pattern(/^\d+$/)]]
    });
    this.observeTemplateChanges();
    this.loadDevices();
  }

  loadDevices(): void {
    if (this.loadingDevices) {
      return;
    }
    this.loadingDevices = true;
    this.deviceLoadFailed = false;
    this.devices = [];
    this.simulatorForm.get('deviceId').reset();
    this.wanWidgetDeviceService.getAvailableDevices(this.ctx.currentUser, [WanDeviceType.TERMINAL]).pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: devices => {
        this.devices = devices;
        this.loadingDevices = false;
        this.ctx.detectChanges();
      },
      error: () => {
        this.deviceLoadFailed = true;
        this.loadingDevices = false;
        this.ctx.detectChanges();
      }
    });
  }

  send(): void {
    if (this.sending || this.simulatorForm.invalid || !this.apiConfigured) {
      this.simulatorForm.markAllAsTouched();
      return;
    }
    const formValue = this.simulatorForm.getRawValue();
    const device = this.devices.find(item => item.id === formValue.deviceId);
    if (!device) {
      this.simulatorForm.get('deviceId').setErrors({deviceMissing: true});
      return;
    }

    this.sending = true;
    this.result = null;
    const url = buildWanNsSimulatorUrl(this.settings, device.externalId);
    this.http.post(url, {
      dataHex: formValue.dataHex,
      fPort: Number(formValue.fPort)
    }, {
      headers: {
        'Content-Type': 'application/json',
        'X-API-Key': this.settings.apiKey.trim()
      },
      observe: 'response',
      responseType: 'text'
    }).pipe(
      timeout(this.settings.timeoutSeconds * 1000),
      finalize(() => {
        this.sending = false;
        this.ctx.detectChanges();
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: response => {
        this.result = {
          success: true,
          message: this.translate.instant('device.wan.ns-simulator-sent', {status: response.status}),
          response: this.limitResponse(response.body),
          time: Date.now()
        };
      },
      error: error => {
        this.result = {
          success: false,
          message: this.requestErrorMessage(error),
          time: Date.now()
        };
      }
    });
  }

  get apiConfigured(): boolean {
    return !!this.settings.apiKey?.trim();
  }

  private observeTemplateChanges(): void {
    this.simulatorForm.get('dataTemplate').valueChanges.pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(template => {
      if (template === WanNsSimulatorTemplate.ALARM) {
        this.simulatorForm.get('dataHex').setValue(wanNsSimulatorAlarmData);
      }
    });
    this.simulatorForm.get('dataHex').valueChanges.pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(value => {
      if (this.simulatorForm.get('dataTemplate').value === WanNsSimulatorTemplate.ALARM &&
          value !== wanNsSimulatorAlarmData) {
        this.simulatorForm.get('dataTemplate').setValue(WanNsSimulatorTemplate.CUSTOM, {emitEvent: false});
      }
    });
  }

  private requestErrorMessage(error: unknown): string {
    if ((error as Error)?.name === 'TimeoutError') {
      return this.translate.instant('device.wan.ns-simulator-timeout');
    }
    if (error instanceof HttpErrorResponse) {
      if (error.status === 0) {
        return this.translate.instant('device.wan.ns-simulator-network-error');
      }
      const responseMessage = this.responseMessage(error.error);
      return this.translate.instant('device.wan.ns-simulator-failed', {
        status: error.status,
        message: responseMessage || error.statusText
      });
    }
    return this.translate.instant('device.wan.ns-simulator-failed', {
      status: '-',
      message: (error as Error)?.message || ''
    });
  }

  private responseMessage(body: unknown): string {
    if (typeof body !== 'string') {
      return body && typeof body === 'object' && 'message' in body ? String((body as {message: unknown}).message) : '';
    }
    try {
      const parsed = JSON.parse(body);
      return parsed?.message ? String(parsed.message) : body;
    } catch {
      return body;
    }
  }

  private limitResponse(response: string | null): string {
    if (!response) {
      return null;
    }
    return response.length > 500 ? `${response.substring(0, 500)}...` : response;
  }
}
