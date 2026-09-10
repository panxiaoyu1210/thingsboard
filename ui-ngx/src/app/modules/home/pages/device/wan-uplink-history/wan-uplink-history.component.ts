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

import { Component, DestroyRef, Input, OnChanges, SimpleChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AttributeService } from '@core/http/attribute.service';
import { DeviceId } from '@shared/models/id/device-id';
import { EntityType } from '@shared/models/entity-type.models';
import { DataSortOrder } from '@shared/models/telemetry/telemetry.models';
import { AggregationType } from '@shared/models/time/time.models';
import { finalize } from 'rxjs/operators';
import {
  toWanUplinkHistoryRecords,
  WanUplinkHistoryRecord,
  WAN_UPLINK_HISTORY_KEYS
} from './wan-uplink-history.models';

interface WanUplinkRangeOption {
  value: number;
  label: string;
}

@Component({
  selector: 'tb-wan-uplink-history',
  templateUrl: './wan-uplink-history.component.html',
  styleUrls: ['./wan-uplink-history.component.scss'],
  standalone: false
})
export class WanUplinkHistoryComponent implements OnChanges {

  readonly displayedColumns = ['receivedTime', 'requestId', 'port', 'signal', 'byteCount', 'data'];
  readonly rangeOptions: WanUplinkRangeOption[] = [
    {value: 24 * 60 * 60 * 1000, label: 'device.wan.uplink-range-24-hours'},
    {value: 7 * 24 * 60 * 60 * 1000, label: 'device.wan.uplink-range-7-days'},
    {value: 30 * 24 * 60 * 60 * 1000, label: 'device.wan.uplink-range-30-days'}
  ];
  readonly pageSize = 25;

  @Input() deviceId: DeviceId;
  @Input() active = false;

  records: WanUplinkHistoryRecord[] = [];
  selectedRange = this.rangeOptions[0].value;
  pageIndex = 0;
  hasNextPage = false;
  loading = false;
  loadFailed = false;

  private windowStart = 0;
  private pageEndCursors: number[] = [];
  private loadedDeviceId: string;
  private requestSequence = 0;

  constructor(private attributeService: AttributeService,
              private destroyRef: DestroyRef) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.active || !this.deviceId?.id) {
      return;
    }
    if (changes.deviceId || this.loadedDeviceId !== this.deviceId.id) {
      this.resetAndLoad();
    } else if (changes.active?.currentValue && !changes.active.previousValue) {
      this.loadPage();
    }
  }

  rangeChanged(): void {
    this.resetAndLoad();
  }

  refresh(): void {
    this.resetAndLoad();
  }

  previousPage(): void {
    if (this.loading || this.pageIndex === 0) {
      return;
    }
    this.pageIndex--;
    this.loadPage();
  }

  nextPage(): void {
    if (this.loading || !this.hasNextPage || !this.records.length) {
      return;
    }
    this.pageEndCursors[this.pageIndex + 1] = this.records[this.records.length - 1].ts - 1;
    this.pageIndex++;
    this.loadPage();
  }

  private resetAndLoad(): void {
    const windowEnd = Date.now();
    this.windowStart = windowEnd - this.selectedRange;
    this.pageEndCursors = [windowEnd];
    this.pageIndex = 0;
    this.loadedDeviceId = this.deviceId?.id;
    this.loadPage();
  }

  private loadPage(): void {
    if (!this.active || !this.deviceId?.id) {
      return;
    }
    const requestId = ++this.requestSequence;
    this.loading = true;
    this.loadFailed = false;
    this.attributeService.getEntityTimeseries(
      {entityType: EntityType.DEVICE, id: this.deviceId.id},
      WAN_UPLINK_HISTORY_KEYS,
      this.windowStart,
      this.pageEndCursors[this.pageIndex],
      this.pageSize + 1,
      AggregationType.NONE,
      undefined,
      DataSortOrder.DESC,
      true
    ).pipe(
      finalize(() => {
        if (requestId === this.requestSequence) {
          this.loading = false;
        }
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: timeseries => {
        if (requestId !== this.requestSequence) {
          return;
        }
        const records = toWanUplinkHistoryRecords(timeseries);
        this.hasNextPage = records.length > this.pageSize;
        this.records = records.slice(0, this.pageSize);
      },
      error: () => {
        if (requestId === this.requestSequence) {
          this.records = [];
          this.hasNextPage = false;
          this.loadFailed = true;
        }
      }
    });
  }
}
