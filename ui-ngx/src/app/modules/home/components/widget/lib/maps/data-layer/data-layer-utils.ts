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

import {
  DataLayerTooltipSettings,
  DataLayerTooltipTrigger, processTooltipTemplate,
  TbMapDatasource
} from '@shared/models/widget/maps/map.models';
import { TbMap } from '@home/components/widget/lib/maps/map';
import { FormattedData } from '@shared/models/widget.models';
import L from 'leaflet';
import { DataLayerPatternProcessor } from '@home/components/widget/lib/maps/data-layer/map-data-layer';

export const createTooltip = (map: TbMap<any>,
                              layer: L.Layer,
                              settings: DataLayerTooltipSettings,
                              data: FormattedData<TbMapDatasource>,
                              canOpen: () => boolean): L.Popup => {
  const tooltip = L.popup({autoClose: settings.autoclose, closeOnClick: false});
  let hoverCloseTimer: ReturnType<typeof setTimeout>;
  let hoverTooltipElement: HTMLElement;
  const cancelHoverClose = () => {
    if (hoverCloseTimer) {
      clearTimeout(hoverCloseTimer);
      hoverCloseTimer = null;
    }
  };
  const scheduleHoverClose = () => {
    cancelHoverClose();
    hoverCloseTimer = setTimeout(() => tooltip.close(), 250);
  };
  const unbindHoverTooltipElement = () => {
    if (hoverTooltipElement) {
      hoverTooltipElement.removeEventListener('mouseenter', cancelHoverClose);
      hoverTooltipElement.removeEventListener('mouseleave', scheduleHoverClose);
      hoverTooltipElement = null;
    }
  };
  (tooltip as any)._source = layer;
  layer.on('move', (e) => {
    tooltip.setLatLng((e as any).latlng);
  });
  layer.on('remove', () => {
    cancelHoverClose();
    unbindHoverTooltipElement();
    tooltip.close();
  });
  if (settings.trigger === DataLayerTooltipTrigger.click) {
    layer.on('click', (e) => {
      L.DomEvent.stop(e);
      if (tooltip.isOpen()) {
        tooltip.close();
      } else if (canOpen()) {
        if ((tooltip as any)._prepareOpen((layer as any)._latlng)) {
          map.deselectItem();
          tooltip.openOn(map.getMap());
        }
      }
    });
  } else if (settings.trigger === DataLayerTooltipTrigger.hover) {
    layer.on('mouseover', () => {
      cancelHoverClose();
      if (canOpen()) {
        if ((tooltip as any)._prepareOpen((layer as any)._latlng)) {
          tooltip.openOn(map.getMap());
        }
      }
    });
    layer.on('mousemove', (e) => {
      tooltip.setLatLng(e.latlng);
    });
    layer.on('mouseout', () => {
      scheduleHoverClose();
    });
  }
  layer.on('popupopen', () => {
    if (settings.trigger === DataLayerTooltipTrigger.hover) {
      unbindHoverTooltipElement();
      hoverTooltipElement = tooltip.getElement();
      hoverTooltipElement?.addEventListener('mouseenter', cancelHoverClose);
      hoverTooltipElement?.addEventListener('mouseleave', scheduleHoverClose);
    }
    bindTooltipActions(map, tooltip, settings, data);
  });
  layer.on('popupclose', () => {
    cancelHoverClose();
    unbindHoverTooltipElement();
  });
  return tooltip;
}

export const updateTooltip = (map: TbMap<any>,
                              tooltip: L.Popup,
                              settings: DataLayerTooltipSettings,
                              processor: DataLayerPatternProcessor,
                              data: FormattedData<TbMapDatasource>,
                              dsData: FormattedData<TbMapDatasource>[]): void => {
  let tooltipTemplate = processor.processPattern(data, dsData);
  tooltipTemplate = processTooltipTemplate(tooltipTemplate);
  tooltip.setContent(tooltipTemplate);
  if (tooltip.isOpen() && tooltip.getElement()) {
    bindTooltipActions(map, tooltip, settings, data);
  }
}

const bindTooltipActions = (map: TbMap<any>, tooltip: L.Popup, settings: DataLayerTooltipSettings, data: FormattedData<TbMapDatasource>): void => {
  const actions = tooltip.getElement().getElementsByClassName('tb-custom-action');
  Array.from(actions).forEach(
    (element: HTMLElement) => {
      const actionName = element.getAttribute('data-action-name');
      if (settings?.tagActions) {
        const action = settings.tagActions.find(action => action.name === actionName);
        if (action) {
          element.onclick = ($event) =>
          {
            map.dataItemClick($event, action, data);
            return false;
          };
        }
      }
    }
  );
}
