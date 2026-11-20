import React, { memo } from 'react';
import { ComposableMap, Geographies, Geography, Marker, ZoomableGroup } from 'react-simple-maps';
import type { DashboardRegion } from '../../../types/dspmPages';
import { regionCoordinates, regionName } from './regionCatalog';

/** The world outline the app's other region maps draw (AzureMapSection, GcpMapSection, MapSection). */
const GEO_URL = 'https://cdn.jsdelivr.net/npm/world-atlas@2/countries-110m.json';

/** The console's map settings (bundle `index-b21c9a6f.js`): Mercator at scale 118, centred on [10, 20]. */
const MAP_SCALE = 118;
const MAP_CENTER: [number, number] = [10, 20];

/** Dot colour by datastore count, with the console's thresholds (above 35,000 critical, above 20,000 high). */
function dotColour(datastores: number): string {
  if (datastores > 35_000) return '#dc2626';
  if (datastores > 20_000) return '#f97316';
  return '#2563eb';
}

/** Dot radius: datastores / 5,000 kept between 4 and 10, 1.4 times larger for the selected region (console). */
function dotRadius(datastores: number, active: boolean): number {
  const radius = Math.max(4, Math.min(10, datastores / 5_000));
  return active ? radius * 1.4 : radius;
}

interface Props {
  regions: DashboardRegion[];
  active: string;
  onSelect: (region: string) => void;
  zoom: number;
}

/** The world map with one dot per region the catalogue can place; a dot selects its region. */
const RegionMap: React.FC<Props> = ({ regions, active, onSelect, zoom }) => (
  <ComposableMap
    projection="geoMercator"
    projectionConfig={{ scale: MAP_SCALE }}
    style={{ width: '100%', height: '100%' }}
  >
    <ZoomableGroup center={MAP_CENTER} zoom={zoom} minZoom={1} maxZoom={4}>
      <Geographies geography={GEO_URL}>
        {({ geographies }: { geographies: Array<{ rsmKey: string }> }) => geographies.map(geography => (
          <Geography
            key={geography.rsmKey}
            geography={geography}
            style={{
              default: { fill: '#e5e7eb', stroke: '#d1d5db', strokeWidth: 0.5, outline: 'none' },
              hover: { fill: '#f3f4f6', stroke: '#d1d5db', strokeWidth: 0.5, outline: 'none' },
              pressed: { outline: 'none' },
            }}
          />
        ))}
      </Geographies>
      {regions.map(region => {
        const coordinates = regionCoordinates(region.region);
        if (!coordinates) return null;
        const selected = region.region === active;
        const colour = dotColour(region.datastores);
        const radius = dotRadius(region.datastores, selected);
        return (
          <Marker key={region.region} coordinates={coordinates} onClick={() => onSelect(region.region)} style={{ default: { cursor: 'pointer' }, hover: { cursor: 'pointer' }, pressed: { cursor: 'pointer' } }}>
            <title>{regionName(region.region)}</title>
            {selected && <circle r={radius + 4} fill={colour} opacity={0.25} />}
            <circle
              r={radius}
              fill={colour}
              opacity={selected ? 0.9 : 0.6}
              stroke={selected ? colour : 'none'}
              strokeWidth={selected ? 2 : 0}
            />
          </Marker>
        );
      })}
    </ZoomableGroup>
  </ComposableMap>
);

export default memo(RegionMap);
