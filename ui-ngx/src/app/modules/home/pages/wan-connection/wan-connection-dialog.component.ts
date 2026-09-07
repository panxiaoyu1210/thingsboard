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

import { Component, Inject } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { finalize } from 'rxjs/operators';
import { WanConnectionService } from '@core/http/wan-connection.service';
import {
  defaultWanConnection,
  WanConnection,
  WanConnectionTestResult
} from '@shared/models/wan-connection.models';

export interface WanConnectionDialogData {
  connection?: WanConnection;
}

@Component({
  selector: 'tb-wan-connection-dialog',
  templateUrl: './wan-connection-dialog.component.html',
  styleUrls: ['./wan-connection-dialog.component.scss'],
  standalone: false
})
export class WanConnectionDialogComponent {

  readonly connectionForm: FormGroup;
  readonly editing: boolean;

  testing = false;
  saving = false;
  clearStoredPassword = false;
  testResult: WanConnectionTestResult;

  constructor(private fb: FormBuilder,
              private wanConnectionService: WanConnectionService,
              private dialogRef: MatDialogRef<WanConnectionDialogComponent, WanConnection>,
              @Inject(MAT_DIALOG_DATA) public data: WanConnectionDialogData) {
    this.editing = !!data.connection;
    const value = {...defaultWanConnection(), ...data.connection, password: ''};
    this.connectionForm = this.fb.group({
      name: [value.name, [Validators.required, Validators.maxLength(255), Validators.pattern(/.*\S.*/)]],
      brokerHost: [value.brokerHost, [Validators.required, Validators.maxLength(255), Validators.pattern(/.*\S.*/)]],
      brokerPort: [value.brokerPort, [Validators.required, Validators.min(1), Validators.max(65535)]],
      tls: [value.tls],
      clientId: [value.clientId, [Validators.required, Validators.maxLength(255), Validators.pattern(/.*\S.*/)]],
      username: [value.username, [Validators.maxLength(255)]],
      password: ['', [Validators.maxLength(2048)]],
      nsPublishTopic: [value.nsPublishTopic, [Validators.required, Validators.maxLength(255), Validators.pattern(/.*\S.*/)]],
      nsSubscribeTopic: [value.nsSubscribeTopic, [Validators.required, Validators.maxLength(255),
        Validators.pattern(/^[^#+]+$/)]],
      qos: [value.qos, [Validators.required, Validators.min(0), Validators.max(2)]],
      enabled: [value.enabled],
      requestTimeoutMs: [value.requestTimeoutMs, [Validators.required, Validators.min(1000), Validators.max(120000)]],
      syncEnabled: [value.syncEnabled],
      syncIntervalHours: [value.syncIntervalHours, [Validators.required, Validators.min(1), Validators.max(8760)]]
    });
    this.connectionForm.get('password').valueChanges.subscribe(value => {
      if (value) {
        this.clearStoredPassword = false;
      }
      this.testResult = null;
    });
  }

  cancel(): void {
    this.dialogRef.close();
  }

  clearPassword(): void {
    this.clearStoredPassword = true;
    this.connectionForm.get('password').setValue('');
    this.testResult = null;
  }

  testConnection(): void {
    if (this.connectionForm.invalid) {
      this.connectionForm.markAllAsTouched();
      return;
    }
    this.testing = true;
    this.testResult = null;
    this.wanConnectionService.testConnection(this.buildConnection()).pipe(
      finalize(() => this.testing = false)
    ).subscribe(result => this.testResult = result);
  }

  save(): void {
    if (this.connectionForm.invalid) {
      this.connectionForm.markAllAsTouched();
      return;
    }
    this.saving = true;
    this.wanConnectionService.saveConnection(this.buildConnection()).pipe(
      finalize(() => this.saving = false)
    ).subscribe(connection => this.dialogRef.close(connection));
  }

  private buildConnection(): WanConnection {
    const formValue = this.connectionForm.getRawValue();
    const password = this.clearStoredPassword ? '' : (formValue.password || null);
    return {
      ...this.data.connection,
      ...formValue,
      password
    } as WanConnection;
  }

}
