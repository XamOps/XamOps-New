import React, { memo, useState } from 'react';
import {
  ComposableMap,
  Geographies,
  Geography,
  Marker,
} from 'react-simple-maps';
import type { RegionStatus } from '../../types/liveDashboard';

interface MapSectionProps {
  regionStatus: RegionStatus[];
  isLoading: boolean;
}

const STATUS_COLORS: Record<string, string> = {
  ACTIVE:      '#60c8f5',
  SEMI_ACTIVE: '#f87171',
  SUSTAINABLE: '#6ee7b7',
  NON_ACTIVE:  '#94a3b8',
};

const GEO_URL = 'https://cdn.jsdelivr.net/npm/world-atlas@2/countries-110m.json';

const BASE_SCALE = 147;
const SCALE_STEP = 40;
const MIN_SCALE  = 80;
const MAX_SCALE  = 420;

const MapSection: React.FC<MapSectionProps> = ({ regionStatus, isLoading }) => {
  const [scale, setScale] = useState(BASE_SCALE);

  return (
    <div className="lg:col-span-3 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden h-full widget-panel-hover">
      {/* Header */}
      <div className="flex items-center justify-between mb-4 flex-shrink-0">
        <div>
          <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Active Regions</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
            <span className="w-1.5 h-1.5 rounded-full bg-blue-400 animate-pulse inline-block" />
            Global Footprint
          </p>
        </div>
        <div className="flex items-center gap-3 border border-[rgba(0,0,0,0.06)] rounded-md px-3 py-1.5">
          <div className="flex items-center gap-1.5">
            <div className="w-2 h-2 rounded-full" style={{ background: '#60c8f5' }} />
            <span className="text-[11px] font-medium text-[rgba(0,0,0,0.5)]">Active</span>
          </div>
          <div className="flex items-center gap-1.5">
            <div className="w-2 h-2 rounded-full" style={{ background: '#6ee7b7' }} />
            <span className="text-[11px] font-medium text-[rgba(0,0,0,0.5)]">Sustainable</span>
          </div>
          <div className="flex items-center gap-1.5">
            <div className="w-2 h-2 rounded-full" style={{ background: '#f87171' }} />
            <span className="text-[11px] font-medium text-[rgba(0,0,0,0.5)]">Alerts</span>
          </div>
        </div>
      </div>

      {/* Loading skeleton */}
      {isLoading ? (
        <div className="h-[320px] bg-[rgba(0,0,0,0.03)] rounded-lg animate-pulse" />
      ) : (
        <div style={{ position: 'relative', height: '320px' }}>
          <ComposableMap
            projection="geoMercator"
            projectionConfig={{ scale, center: [10, 15] }}
            style={{ width: '100%', height: '100%' }}
          >
            <defs>
              <pattern
                id="dotPattern"
                x="0" y="0"
                width="7" height="7"
                patternUnits="userSpaceOnUse"
              >
                <circle cx="2.5" cy="2.5" r="2.5" fill="#B0BEC5" />
              </pattern>
            </defs>

            <Geographies geography={GEO_URL}>
              {({ geographies }) =>
                geographies.map((geo) => (
                  <Geography
                    key={geo.rsmKey}
                    geography={geo}
                    style={{
                      default: { fill: 'url(#dotPattern)', stroke: 'transparent', outline: 'none' },
                      hover:   { fill: 'url(#dotPattern)', outline: 'none' },
                      pressed: { outline: 'none' },
                    }}
                  />
                ))
              }
            </Geographies>

            {regionStatus.map((r) => {
              const color = STATUS_COLORS[r.status] ?? '#94a3b8';
              const label = r.resourceCount !== undefined
                ? `${r.regionName ?? r.regionId} — ${r.status.replace('_', ' ')} (${r.resourceCount})`
                : `${r.regionName ?? r.regionId} — ${r.status.replace('_', ' ')}`;

              return (
                <Marker key={r.regionId} coordinates={[r.longitude, r.latitude]}>
                  <title>{label}</title>
                  {/* Layered glow rings — sized to match reference */}
                  <circle r={32} fill={color} fillOpacity={0.08} />
                  <circle r={22} fill={color} fillOpacity={0.15} />
                  <circle r={14} fill={color} fillOpacity={0.30} />
                  {/* Solid centre dot with stroke ring */}
                  <circle r={8} fill={color} stroke="#fff" strokeWidth={2.5} />
                  {/* Region name label — white pill for readability on dotted map */}
                  <rect x={-44} y={13} width={88} height={16} rx={3} fill="rgba(255,255,255,0.95)" stroke="rgba(0,0,0,0.1)" strokeWidth={0.5} />
                  <text
                    textAnchor="middle"
                    y={25}
                    style={{ fontSize: 11, fill: '#111827', fontFamily: 'inherit', fontWeight: 700, pointerEvents: 'none' }}
                  >
                    {r.regionName ?? r.regionId}
                  </text>
                </Marker>
              );
            })}
          </ComposableMap>

          {/* Zoom controls */}
          <div style={{
            position: 'absolute',
            right: 10,
            bottom: 10,
            display: 'flex',
            flexDirection: 'column',
            background: 'rgba(255,255,255,0.82)',
            backdropFilter: 'blur(14px)',
            border: '1px solid rgba(15,23,42,0.08)',
            borderRadius: 10,
            overflow: 'hidden',
            boxShadow: '0 4px 14px rgba(15,23,42,0.08)',
          }}>
            <button
              onClick={() => setScale(s => Math.min(s + SCALE_STEP, MAX_SCALE))}
              style={{ width: 34, height: 34, border: 'none', background: 'transparent', fontSize: 20, lineHeight: 1, cursor: 'pointer', color: '#0f172a' }}
              aria-label="Zoom in"
            >+</button>
            <div style={{ height: 1, background: 'rgba(15,23,42,0.08)' }} />
            <button
              onClick={() => setScale(s => Math.max(s - SCALE_STEP, MIN_SCALE))}
              style={{ width: 34, height: 34, border: 'none', background: 'transparent', fontSize: 22, lineHeight: 1, cursor: 'pointer', color: '#0f172a' }}
              aria-label="Zoom out"
            >−</button>
          </div>
        </div>
      )}
    </div>
  );
};

export default memo(MapSection);
