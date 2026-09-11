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

import { Injectable } from '@angular/core';
import { DeviceService } from '@core/http/device.service';
import { Authority } from '@shared/models/authority.enum';
import { DeviceInfo, DeviceTransportType, WanDeviceType } from '@shared/models/device.models';
import { PageData } from '@shared/models/page/page-data';
import { PageLink } from '@shared/models/page/page-link';
import { AuthUser } from '@shared/models/user.model';
import { Observable, of, throwError } from 'rxjs';
import { switchMap } from 'rxjs/operators';

export interface WanWidgetDeviceOption {
  id: string;
  name: string;
  displayName: string;
  deviceType: WanDeviceType;
  externalId: string;
}

@Injectable({
  providedIn: 'root'
})
export class WanWidgetDeviceService {

  constructor(private deviceService: DeviceService) {
  }

  getAvailableDevices(authUser: AuthUser,
                      allowedDeviceTypes: readonly WanDeviceType[] = [WanDeviceType.TERMINAL, WanDeviceType.GATEWAY]):
    Observable<WanWidgetDeviceOption[]> {
    if (!authUser || ![Authority.TENANT_ADMIN, Authority.CUSTOMER_USER].includes(authUser.authority)) {
      return throwError(() => new Error('Current user cannot access WAN devices'));
    }
    return this.loadDevicePage(authUser, allowedDeviceTypes, new PageLink(100, 0), []);
  }

  private loadDevicePage(authUser: AuthUser,
                         allowedDeviceTypes: readonly WanDeviceType[],
                         pageLink: PageLink,
                         collected: WanWidgetDeviceOption[]): Observable<WanWidgetDeviceOption[]> {
    return this.getDevicePage(authUser, pageLink).pipe(
      switchMap(page => {
        collected.push(...page.data
          .map(device => this.toDeviceOption(device, allowedDeviceTypes))
          .filter(Boolean));
        if (page.hasNext) {
          return this.loadDevicePage(authUser, allowedDeviceTypes, pageLink.nextPageLink(), collected);
        }
        return of(collected.sort((left, right) =>
          left.displayName.localeCompare(right.displayName) || left.externalId.localeCompare(right.externalId)));
      })
    );
  }

  private getDevicePage(authUser: AuthUser, pageLink: PageLink): Observable<PageData<DeviceInfo>> {
    return authUser.authority === Authority.CUSTOMER_USER ?
      this.deviceService.getCustomerDeviceInfos(authUser.customerId, pageLink) :
      this.deviceService.getTenantDeviceInfos(pageLink);
  }

  private toDeviceOption(device: DeviceInfo,
                         allowedDeviceTypes: readonly WanDeviceType[]): WanWidgetDeviceOption | null {
    const transport = device.deviceData?.transportConfiguration;
    if (!device.id?.id || transport?.type !== DeviceTransportType.WAN ||
        !transport.deviceType || !allowedDeviceTypes.includes(transport.deviceType)) {
      return null;
    }
    const externalId = transport.deviceType === WanDeviceType.TERMINAL ?
      transport.terminal?.devEui : transport.gateway?.gwId;
    if (!/^[0-9a-fA-F]{16}$/.test(externalId || '')) {
      return null;
    }
    return {
      id: device.id.id,
      name: device.name,
      displayName: device.label || device.name,
      deviceType: transport.deviceType,
      externalId
    };
  }
}
