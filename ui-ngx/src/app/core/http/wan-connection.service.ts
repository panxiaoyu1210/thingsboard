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
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { defaultHttpOptionsFromConfig, RequestConfig } from '@core/http/http-utils';
import { PageLink } from '@shared/models/page/page-link';
import { PageData } from '@shared/models/page/page-data';
import { WanConnection, WanConnectionTestResult } from '@shared/models/wan-connection.models';

@Injectable({
  providedIn: 'root'
})
export class WanConnectionService {

  constructor(private http: HttpClient) {
  }

  saveConnection(connection: WanConnection, config?: RequestConfig): Observable<WanConnection> {
    return this.http.post<WanConnection>('/api/wan/connection', connection, defaultHttpOptionsFromConfig(config));
  }

  getConnection(connectionId: string, config?: RequestConfig): Observable<WanConnection> {
    return this.http.get<WanConnection>(`/api/wan/connection/${connectionId}`, defaultHttpOptionsFromConfig(config));
  }

  getConnections(pageLink: PageLink, config?: RequestConfig): Observable<PageData<WanConnection>> {
    return this.http.get<PageData<WanConnection>>(
      `/api/wan/connections${pageLink.toQuery()}`, defaultHttpOptionsFromConfig(config));
  }

  testConnection(connection: WanConnection, config?: RequestConfig): Observable<WanConnectionTestResult> {
    return this.http.post<WanConnectionTestResult>(
      '/api/wan/connection/test', connection, defaultHttpOptionsFromConfig(config));
  }

  deleteConnection(connectionId: string, config?: RequestConfig): Observable<void> {
    return this.http.delete<void>(`/api/wan/connection/${connectionId}`, defaultHttpOptionsFromConfig(config));
  }

}
