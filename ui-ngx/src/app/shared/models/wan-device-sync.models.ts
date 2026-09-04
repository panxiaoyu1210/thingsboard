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

import { DeviceId } from '@shared/models/id/device-id';
import { TenantId } from '@shared/models/id/tenant-id';
import { WanDeviceType } from '@shared/models/device.models';

export enum WanDeviceSyncStatus {
  PENDING = 'PENDING',
  SYNCING = 'SYNCING',
  CREATING = 'CREATING',
  ACTIVE = 'ACTIVE',
  UNKNOWN = 'UNKNOWN',
  FAILED = 'FAILED',
  RECREATING = 'RECREATING',
  DELETING = 'DELETING'
}

export interface WanDeviceSyncState {
  id: string;
  createdTime: number;
  tenantId: TenantId;
  deviceId: DeviceId;
  connectionId: string;
  deviceType: WanDeviceType;
  externalId: string;
  deviceName: string;
  syncStatus: WanDeviceSyncStatus;
  lastSyncTime?: number;
  lastSuccessfulSyncTime?: number;
  nextSyncTime?: number;
  error?: string;
  deletionExternalId?: string;
  retryCount?: number;
  version: number;
}
