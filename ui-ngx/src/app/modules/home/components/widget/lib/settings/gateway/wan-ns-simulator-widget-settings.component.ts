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

import { Component } from '@angular/core';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { AppState } from '@core/core.state';
import { wanNsSimulatorDefaultSettings } from '@home/components/widget/lib/wan-ns-simulator/wan-ns-simulator-widget.models';
import { Store } from '@ngrx/store';
import { WidgetSettings, WidgetSettingsComponent } from '@shared/models/widget.models';

@Component({
  selector: 'tb-wan-ns-simulator-widget-settings',
  templateUrl: './wan-ns-simulator-widget-settings.component.html',
  styleUrls: ['../widget-settings.scss'],
  standalone: false
})
export class WanNsSimulatorWidgetSettingsComponent extends WidgetSettingsComponent {

  simulatorSettingsForm: UntypedFormGroup;

  constructor(protected store: Store<AppState>,
              private fb: UntypedFormBuilder) {
    super(store);
  }

  protected settingsForm(): UntypedFormGroup {
    return this.simulatorSettingsForm;
  }

  protected defaultSettings(): WidgetSettings {
    return wanNsSimulatorDefaultSettings;
  }

  protected onSettingsSet(settings: WidgetSettings): void {
    this.simulatorSettingsForm = this.fb.group({
      scheme: [settings.scheme, [Validators.required, Validators.pattern(/^https?$/)]],
      host: [settings.host, [Validators.required, Validators.pattern(/^[a-zA-Z0-9.-]+$/)]],
      port: [settings.port, [Validators.required, Validators.min(1), Validators.max(65535), Validators.pattern(/^\d+$/)]],
      apiKey: [settings.apiKey, Validators.required],
      timeoutSeconds: [settings.timeoutSeconds,
        [Validators.required, Validators.min(1), Validators.max(120), Validators.pattern(/^\d+$/)]]
    });
  }
}
