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

export const INSTALLATION_LATITUDE_ATTRIBUTE = 'latitude';
export const INSTALLATION_LONGITUDE_ATTRIBUTE = 'longitude';

export interface InstallationLocation {
  latitude: number | null;
  longitude: number | null;
}

export const emptyInstallationLocation = (): InstallationLocation => ({
  latitude: null,
  longitude: null
});

export const isInstallationLocationEmpty = (location: InstallationLocation | null | undefined): boolean =>
  (location?.latitude === null || location?.latitude === undefined) &&
  (location?.longitude === null || location?.longitude === undefined);

export const isInstallationLocationValid = (location: InstallationLocation | null | undefined): boolean =>
  isInstallationLocationEmpty(location) ||
  Number.isFinite(location?.latitude) && location.latitude >= -90 && location.latitude <= 90 &&
  Number.isFinite(location?.longitude) && location.longitude >= -180 && location.longitude <= 180;
