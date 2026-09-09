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
import { MatDialog } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { WanDownlinkDialogData, WanDownlinkSendResult } from '@shared/models/wan-downlink.models';
import { WanDownlinkDialogComponent } from './wan-downlink-dialog.component';

@Injectable({
  providedIn: 'root'
})
export class WanDownlinkDialogService {

  constructor(private dialog: MatDialog) {}

  open(data: WanDownlinkDialogData): Observable<WanDownlinkSendResult | undefined> {
    return this.dialog.open<WanDownlinkDialogComponent, WanDownlinkDialogData, WanDownlinkSendResult>(
      WanDownlinkDialogComponent,
      {
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog-lt-md'],
        disableClose: true,
        autoFocus: false,
        data
      }
    ).afterClosed();
  }
}
