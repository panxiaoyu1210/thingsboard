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

import { Component, DestroyRef, OnInit } from '@angular/core';
import { FormControl } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { PageEvent } from '@angular/material/paginator';
import { Store } from '@ngrx/store';
import { TranslateService } from '@ngx-translate/core';
import { debounceTime, distinctUntilChanged, finalize } from 'rxjs/operators';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AppState } from '@core/core.state';
import { WanConnectionService } from '@core/http/wan-connection.service';
import { DialogService } from '@core/services/dialog.service';
import { ActionNotificationShow } from '@core/notification/notification.actions';
import { PageLink } from '@shared/models/page/page-link';
import { Direction } from '@shared/models/page/sort-order';
import { WanConnection } from '@shared/models/wan-connection.models';
import {
  WanConnectionDialogComponent,
  WanConnectionDialogData
} from '@home/pages/wan-connection/wan-connection-dialog.component';

@Component({
  selector: 'tb-wan-connections',
  templateUrl: './wan-connections.component.html',
  styleUrls: ['./wan-connections.component.scss'],
  standalone: false
})
export class WanConnectionsComponent implements OnInit {

  readonly displayedColumns = ['name', 'broker', 'tls', 'qos', 'enabled', 'actions'];
  readonly pageSizeOptions = [10, 20, 50];
  readonly searchControl = new FormControl('', {nonNullable: true});

  connections: WanConnection[] = [];
  pageSize = 10;
  pageIndex = 0;
  totalElements = 0;
  loading = false;

  constructor(private wanConnectionService: WanConnectionService,
              private dialog: MatDialog,
              private dialogs: DialogService,
              private translate: TranslateService,
              private store: Store<AppState>,
              private destroyRef: DestroyRef) {
  }

  ngOnInit(): void {
    this.searchControl.valueChanges.pipe(
      debounceTime(250),
      distinctUntilChanged(),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(() => {
      this.pageIndex = 0;
      this.loadConnections();
    });
    this.loadConnections();
  }

  loadConnections(): void {
    const pageLink = new PageLink(this.pageSize, this.pageIndex, this.searchControl.value, {
      property: 'name',
      direction: Direction.ASC
    });
    this.loading = true;
    this.wanConnectionService.getConnections(pageLink).pipe(
      finalize(() => this.loading = false),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(page => {
      this.connections = page.data;
      this.totalElements = page.totalElements;
    });
  }

  pageChanged(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadConnections();
  }

  addConnection(): void {
    this.openConnectionDialog();
  }

  editConnection(connection: WanConnection): void {
    this.openConnectionDialog(connection);
  }

  deleteConnection(connection: WanConnection): void {
    this.dialogs.confirm(
      this.translate.instant('wan-connection.delete-title', {name: connection.name}),
      this.translate.instant('wan-connection.delete-text'),
      this.translate.instant('action.no'),
      this.translate.instant('action.yes')
    ).subscribe(confirmed => {
      if (confirmed) {
        this.wanConnectionService.deleteConnection(connection.id).subscribe(() => {
          this.store.dispatch(new ActionNotificationShow({
            message: this.translate.instant('wan-connection.deleted'),
            type: 'success'
          }));
          this.loadConnections();
        });
      }
    });
  }

  private openConnectionDialog(connection?: WanConnection): void {
    this.dialog.open<WanConnectionDialogComponent, WanConnectionDialogData, WanConnection>(
      WanConnectionDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        width: '760px',
        maxWidth: '96vw',
        data: {connection}
      }
    ).afterClosed().subscribe(result => {
      if (result) {
        this.loadConnections();
      }
    });
  }

}
