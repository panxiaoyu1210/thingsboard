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

import { TenantId } from '@shared/models/id/tenant-id';

export interface WanConnection {
  id?: string;
  createdTime?: number;
  tenantId?: TenantId;
  name: string;
  brokerHost: string;
  brokerPort: number;
  tls: boolean;
  clientId: string;
  username?: string;
  password?: string;
  nsPublishTopic: string;
  nsSubscribeTopic: string;
  qos: number;
  enabled: boolean;
  requestTimeoutMs: number;
  syncIntervalHours: number;
  passwordSet?: boolean;
  version?: number;
}

export interface WanConnectionTestResult {
  success: boolean;
  code: string;
  message: string;
}

export const defaultWanConnection = (): WanConnection => ({
  name: '',
  brokerHost: '',
  brokerPort: 1883,
  tls: false,
  clientId: '',
  username: '',
  password: '',
  nsPublishTopic: '',
  nsSubscribeTopic: '',
  qos: 1,
  enabled: true,
  requestTimeoutMs: 10000,
  syncIntervalHours: 24
});
