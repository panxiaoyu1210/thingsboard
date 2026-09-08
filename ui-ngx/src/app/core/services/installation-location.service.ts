/**
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
import { AttributeService } from '@core/http/attribute.service';
import { EntityId } from '@shared/models/id/entity-id';
import { AttributeScope } from '@shared/models/telemetry/telemetry.models';
import {
  emptyInstallationLocation,
  INSTALLATION_LATITUDE_ATTRIBUTE,
  INSTALLATION_LONGITUDE_ATTRIBUTE,
  InstallationLocation,
  isInstallationLocationEmpty,
  isInstallationLocationValid
} from '@shared/models/installation-location.models';
import { map } from 'rxjs/operators';
import { Observable, throwError } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class InstallationLocationService {

  constructor(private attributeService: AttributeService) {
  }

  getLocation(entityId: EntityId): Observable<InstallationLocation> {
    return this.attributeService.getEntityAttributes(
      entityId,
      AttributeScope.SERVER_SCOPE,
      [INSTALLATION_LATITUDE_ATTRIBUTE, INSTALLATION_LONGITUDE_ATTRIBUTE]
    ).pipe(
      map(attributes => {
        const location = emptyInstallationLocation();
        for (const attribute of attributes) {
          if (attribute.key === INSTALLATION_LATITUDE_ATTRIBUTE) {
            location.latitude = this.toNumber(attribute.value);
          } else if (attribute.key === INSTALLATION_LONGITUDE_ATTRIBUTE) {
            location.longitude = this.toNumber(attribute.value);
          }
        }
        return isInstallationLocationValid(location) ? location : emptyInstallationLocation();
      })
    );
  }

  saveLocation(entityId: EntityId, location: InstallationLocation | null): Observable<void> {
    const normalizedLocation = location || emptyInstallationLocation();
    if (!isInstallationLocationValid(normalizedLocation)) {
      return throwError(() => new Error('Invalid installation location.'));
    }
    const empty = isInstallationLocationEmpty(normalizedLocation);
    return this.attributeService.saveEntityAttributes(
      entityId,
      AttributeScope.SERVER_SCOPE,
      [
        {key: INSTALLATION_LATITUDE_ATTRIBUTE, value: empty ? null : normalizedLocation.latitude},
        {key: INSTALLATION_LONGITUDE_ATTRIBUTE, value: empty ? null : normalizedLocation.longitude}
      ]
    ).pipe(map(() => undefined));
  }

  private toNumber(value: unknown): number | null {
    if (value === null || value === undefined || value === '') {
      return null;
    }
    const result = typeof value === 'number' ? value : Number(value);
    return Number.isFinite(result) ? result : null;
  }
}
