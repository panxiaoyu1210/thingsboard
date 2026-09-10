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

import { RpcStatus } from '@shared/models/rpc.models';

export enum WanDownlinkCommandSource {
  RAW_HEX = 'RAW_HEX',
  TEMPLATE = 'TEMPLATE'
}

export enum WanDownlinkMode {
  UNICAST = 'UNICAST',
  GATEWAY_BROADCAST = 'GATEWAY_BROADCAST',
  NETWORK_BROADCAST = 'NETWORK_BROADCAST'
}

export enum WanDownlinkOrigin {
  DEVICE_DETAILS = 'DEVICE_DETAILS',
  DASHBOARD = 'DASHBOARD',
  MAP = 'MAP'
}

export interface WanDownlinkDialogData {
  deviceId: string;
  deviceName?: string;
  origin: WanDownlinkOrigin;
}

export interface WanRawDownlinkCommand {
  data: string;
  port?: number;
  mode: WanDownlinkMode;
  reason?: string;
  timeoutMs?: number;
}

export interface WanDownlinkSendResult {
  rpcId?: string;
  status: RpcStatus;
  response?: unknown;
}

export const WAN_BROADCAST_MAX_BYTES = 35;
export const WAN_DOWNLINK_DEFAULT_TIMEOUT_MS = 30000;

export function normalizeWanHexData(value: string): string {
  return (value || '').replace(/\s+/g, '').toUpperCase();
}
