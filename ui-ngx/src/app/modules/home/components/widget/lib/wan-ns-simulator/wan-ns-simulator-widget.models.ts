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

import { AbstractControl, ValidationErrors } from '@angular/forms';

export enum WanNsSimulatorTemplate {
  CUSTOM = 'CUSTOM',
  ALARM = 'ALARM'
}

export interface WanNsSimulatorWidgetSettings {
  scheme: 'http' | 'https';
  host: string;
  port: number;
  apiKey: string;
  timeoutSeconds: number;
}

export const wanNsSimulatorAlarmData =
  '4040A8A4015D1C07001D081A06D5AD2B560000000000000030000202010110003C000C000200202020202020202020202020202020202020202020202020202020202020201C07001D081AE72323';

export const wanNsSimulatorDefaultSettings: WanNsSimulatorWidgetSettings = {
  scheme: 'http',
  host: 'localhost',
  port: 18081,
  apiKey: '',
  timeoutSeconds: 10
};

export function evenLengthHexValidator(control: AbstractControl): ValidationErrors | null {
  const value = control.value as string;
  if (!value) {
    return null;
  }
  if (!/^[0-9a-fA-F]+$/.test(value)) {
    return {hex: true};
  }
  return value.length % 2 === 0 ? null : {evenLength: true};
}

export function buildWanNsSimulatorUrl(settings: WanNsSimulatorWidgetSettings, devEui: string): string {
  return `${settings.scheme}://${settings.host}:${settings.port}` +
    `/api/devices/${encodeURIComponent(devEui)}/simulate-join-uplink`;
}
