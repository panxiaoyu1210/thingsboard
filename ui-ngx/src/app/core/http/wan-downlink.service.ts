/*
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable } from '@angular/core';
import { DeviceService } from '@core/http/device.service';
import { Device, DeviceTransportType, WanDeviceType } from '@shared/models/device.models';
import { RpcStatus } from '@shared/models/rpc.models';
import {
  normalizeWanHexData,
  WanDownlinkCommandSource,
  WanDownlinkMode,
  WanDownlinkOrigin,
  WanDownlinkSendResult,
  WanRawDownlinkCommand,
  WAN_DOWNLINK_DEFAULT_TIMEOUT_MS
} from '@shared/models/wan-downlink.models';
import { Observable, of, throwError, timer } from 'rxjs';
import { catchError, filter, switchMap, take, timeout } from 'rxjs/operators';

@Injectable({
  providedIn: 'root'
})
export class WanDownlinkService {

  constructor(private deviceService: DeviceService) {}

  getDevice(deviceId: string): Observable<Device> {
    return this.deviceService.getDevice(deviceId);
  }

  sendRawCommand(device: Device, command: WanRawDownlinkCommand,
                 origin: WanDownlinkOrigin): Observable<WanDownlinkSendResult> {
    const request = this.buildRpcRequest(device, command, origin);
    const requestTimeout = command.timeoutMs || WAN_DOWNLINK_DEFAULT_TIMEOUT_MS;
    return this.deviceService.sendTwoWayRpcCommand(device.id.id, request).pipe(
      switchMap(response => {
        if (!response?.rpcId) {
          return throwError(() => new Error('Unable to create persistent WAN RPC request'));
        }
        return timer(1000, 1000).pipe(
          switchMap(() => this.deviceService.getPersistedRpc(response.rpcId, true)),
          filter(rpc => rpc.status !== RpcStatus.QUEUED && rpc.status !== RpcStatus.DELIVERED),
          take(1),
          switchMap(rpc => {
            if (rpc.status === RpcStatus.FAILED) {
              return throwError(() => new Error(this.rpcFailureMessage(rpc.response)));
            }
            if (rpc.status === RpcStatus.TIMEOUT || rpc.status === RpcStatus.EXPIRED) {
              return throwError(() => new Error('WAN RPC request timed out'));
            }
            return of({
              rpcId: response.rpcId,
              status: rpc.status,
              response: rpc.response
            });
          }),
          timeout(requestTimeout + 5000),
          catchError(error => error?.name === 'TimeoutError'
            ? throwError(() => new Error('WAN RPC request timed out'))
            : throwError(() => error))
        );
      })
    );
  }

  private buildRpcRequest(device: Device, command: WanRawDownlinkCommand,
                          origin: WanDownlinkOrigin): Record<string, unknown> {
    const transport = device.deviceData?.transportConfiguration;
    if (transport?.type !== DeviceTransportType.WAN || !transport.deviceType) {
      throw new Error('Target device is not a configured WAN device');
    }
    const normalizedData = normalizeWanHexData(command.data);
    const terminal = transport.deviceType === WanDeviceType.TERMINAL;
    const downlinkMode = terminal
      ? WanDownlinkMode.UNICAST
      : command.mode === WanDownlinkMode.NETWORK_BROADCAST
        ? WanDownlinkMode.NETWORK_BROADCAST
        : WanDownlinkMode.GATEWAY_BROADCAST;
    const method = terminal ? 'wanDownlink' : 'wanBroadcast';
    const params = terminal ? {
      port: command.port,
      data: normalizedData
    } : {
      data: normalizedData,
      broadcastAll: downlinkMode === WanDownlinkMode.NETWORK_BROADCAST
    };
    return {
      method,
      params,
      timeout: command.timeoutMs || WAN_DOWNLINK_DEFAULT_TIMEOUT_MS,
      persistent: true,
      retries: 0,
      additionalInfo: {
        commandSource: WanDownlinkCommandSource.RAW_HEX,
        commandOrigin: origin,
        downlinkMode,
        reason: command.reason?.trim() || null,
        templateId: null,
        templateVersion: null
      }
    };
  }

  private rpcFailureMessage(response: unknown): string {
    if (typeof response === 'string') {
      try {
        const parsed = JSON.parse(response);
        return parsed?.error || response;
      } catch {
        return response;
      }
    }
    return (response as {error?: string})?.error || 'WAN RPC request failed';
  }
}
