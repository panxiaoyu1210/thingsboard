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

import { TimeseriesData } from '@shared/models/telemetry/telemetry.models';

export interface WanUplinkHistoryRecord {
  ts: number;
  requestId?: number;
  port?: number;
  rssi?: number;
  snr?: number;
  data: string;
  byteCount: number;
}

export const WAN_UPLINK_HISTORY_KEYS = [
  'wanData',
  'wanRequestId',
  'wanPort',
  'rssi',
  'snr'
];

export function toWanUplinkHistoryRecords(timeseries: TimeseriesData): WanUplinkHistoryRecord[] {
  const rows = new Map<number, Partial<WanUplinkHistoryRecord>>();
  const fieldByKey: Record<string, keyof WanUplinkHistoryRecord> = {
    wanRequestId: 'requestId',
    wanPort: 'port',
    rssi: 'rssi',
    snr: 'snr',
    wanData: 'data'
  };
  for (const key of WAN_UPLINK_HISTORY_KEYS) {
    for (const value of timeseries?.[key] || []) {
      const row = rows.get(value.ts) || {ts: value.ts};
      const field = fieldByKey[key];
      if (field === 'data') {
        row.data = String(value.value ?? '');
      } else {
        const numberValue = Number(value.value);
        if (Number.isFinite(numberValue)) {
          (row as Record<string, unknown>)[field] = numberValue;
        }
      }
      rows.set(value.ts, row);
    }
  }
  return Array.from(rows.values())
    .filter(row => typeof row.data === 'string')
    .map(row => ({
      ts: row.ts,
      requestId: row.requestId,
      port: row.port,
      rssi: row.rssi,
      snr: row.snr,
      data: row.data,
      byteCount: row.data.length / 2
    }))
    .sort((left, right) => right.ts - left.ts);
}
