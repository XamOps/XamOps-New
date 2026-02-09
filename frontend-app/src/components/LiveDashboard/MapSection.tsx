/**
 * MapSection Component
 *
 * Displays interactive world map with AWS region points using react-simple-maps.
 * Replaced Highcharts due to loading issues with external CDN dependencies.
 */

import React, { useState, useCallback, memo } from 'react';
import {
  ComposableMap,
  Geographies,
  Geography,
  Marker,
  ZoomableGroup,
} from 'react-simple-maps';
import { useTheme } from '../../contexts/ThemeContext';
import type { RegionStatus } from '../../types/liveDashboard';

interface MapSectionProps {
  regionStatus: RegionStatus[];
  isLoading: boolean;
}

// World map TopoJSON URL (Natural Earth data, very reliable)
const geoUrl = 'https://cdn.jsdelivr.net/npm/world-atlas@2/countries-110m.json';

// Status colors matching original
const statusColors: Record<string, string> = {
  'ACTIVE': '#3b82f6',     // blue
  'SEMI_ACTIVE': '#ef4444', // red
  'SUSTAINABLE': '#22c55e', // green
  'NON_ACTIVE': '#6b7280'   // gray
};

const MapSection: React.FC<MapSectionProps> = ({ regionStatus, isLoading }) => {
  const { theme } = useTheme();
  const [tooltipContent, setTooltipContent] = useState<string | null>(null);
  const [tooltipPosition, setTooltipPosition] = useState({ x: 0, y: 0 });
  const [zoom, setZoom] = useState(1);
  const [center, setCenter] = useState<[number, number]>([0, 20]);

  // Map colors based on theme
  const mapColors = {
    geography: theme === 'dark' ? '#334155' : '#f1f5f9',
    stroke: theme === 'dark' ? '#1e293b' : '#cbd5e1',
    hover: theme === 'dark' ? '#475569' : '#e2e8f0',
    background: theme === 'dark' ? '#0f172a' : '#f8fafc',
  };

  /**
   * Handle region click - show notification
   */
  const filterByRegion = useCallback((regionId: string) => {
    const notification = document.createElement('div');
    notification.className = 'fixed top-4 right-4 bg-blue-50 border border-blue-200 text-blue-800 px-4 py-3 rounded-lg shadow-lg z-50 max-w-sm';
    notification.innerHTML = `
      <div class="flex items-center space-x-2">
        <svg class="w-5 h-5 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 4a1 1 0 011-1h16a1 1 0 011 1v2.586a1 1 0 01-.293.707l-6.414 6.414a1 1 0 00-.293.707V17l-4 4v-6.586a1 1 0 00-.293-.707L3.293 7.293A1 1 0 013 6.586V4z" />
        </svg>
        <div>
          <p class="font-semibold">Region Filter Applied</p>
          <p class="text-sm">Dashboard filtered by: ${regionId}</p>
        </div>
      </div>
    `;
    document.body.appendChild(notification);

    setTimeout(() => {
      notification.remove();
    }, 4000);
  }, []);

  const handleZoomIn = () => {
    if (zoom < 4) setZoom(zoom * 1.5);
  };

  const handleZoomOut = () => {
    if (zoom > 1) setZoom(zoom / 1.5);
  };

  const handleMoveEnd = (position: { coordinates: [number, number]; zoom: number }) => {
    setCenter(position.coordinates);
    setZoom(position.zoom);
  };

  return (
    <div className="lg:col-span-3 glass-card p-6 rounded-2xl border border-gray-200 shadow-xl shadow-blue-500/5 relative overflow-hidden h-full">
      <div className="flex items-center justify-between mb-8">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-blue-50 rounded-xl flex items-center justify-center border border-blue-100 shadow-sm">
            <svg className="w-6 h-6 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 002 2 2 2 0 012 2v.651M20 9v1a2 2 0 002 2 2 2 0 012 2v.651M20 9a2 2 0 00-2-2 2 2 0 01-2-2V3.935M16 2a11 11 0 1010 0v.5" />
            </svg>
          </div>
          <div>
            <h3 className="text-xl font-black text-gray-900 tracking-tight uppercase px-1">Active Regions</h3>
            <p className="text-[10px] text-gray-400 font-bold uppercase tracking-widest flex items-center gap-1.5 px-1 mt-0.5">
              <span className="w-1.5 h-1.5 bg-blue-400 rounded-full animate-pulse"></span>&nbsp;Global Footprint
            </p>
          </div>
        </div>
        {!isLoading && (
          <div className="flex items-center gap-4 bg-white/50 backdrop-blur-sm px-4 py-2 rounded-full border border-gray-100 shadow-sm">
            <div className="flex items-center gap-1.5">
              <div className="w-2 h-2 bg-blue-500 rounded-full shadow-lg shadow-blue-500/30"></div>
              <span className="text-[10px] uppercase font-black tracking-widest text-gray-500">Active</span>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="w-2 h-2 bg-emerald-500 rounded-full shadow-lg shadow-emerald-500/30"></div>
              <span className="text-[10px] uppercase font-black tracking-widest text-gray-500">Fixed</span>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="w-2 h-2 bg-rose-500 rounded-full shadow-lg shadow-rose-500/30"></div>
              <span className="text-[10px] uppercase font-black tracking-widest text-gray-500">Alerts</span>
            </div>
          </div>
        )}
      </div>

      {/* Map Loading Skeleton */}
      {isLoading && (
        <div className="absolute inset-x-0 bottom-0 top-16 bg-white/50 backdrop-blur-sm z-10 flex items-center justify-center rounded-b-xl">
          <div className="loading-spinner w-12 h-12"></div>
        </div>
      )}

      {/* Map Container */}
      <div className="relative rounded-lg overflow-hidden" style={{ height: '480px', backgroundColor: mapColors.background }}>
        {/* Zoom Controls - positioned outside SVG stacking context */}
        <div className="absolute bottom-4 left-4 z-50 flex flex-col gap-1" style={{ pointerEvents: 'auto' }}>
          <button
            onClick={handleZoomIn}
            className="w-10 h-10 bg-white border-2 border-orange-400 rounded-lg flex items-center justify-center hover:bg-orange-50 transition-colors shadow-md"
            title="Zoom In"
            type="button"
          >
            <svg className="w-5 h-5 text-gray-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 6v6m0 0v6m0-6h6m-6 0H6" />
            </svg>
          </button>
          <button
            onClick={handleZoomOut}
            className="w-10 h-10 bg-white border-2 border-orange-400 rounded-lg flex items-center justify-center hover:bg-orange-50 transition-colors shadow-md"
            title="Zoom Out"
            type="button"
          >
            <svg className="w-5 h-5 text-gray-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 12H4" />
            </svg>
          </button>
        </div>

        {/* Tooltip */}
        {tooltipContent && (
          <div
            className="absolute z-30 pointer-events-none px-3 py-2 rounded-lg text-white text-sm font-medium shadow-lg"
            style={{
              left: tooltipPosition.x,
              top: tooltipPosition.y,
              backgroundColor: 'rgba(0, 0, 0, 0.85)',
              border: '1px solid rgba(255, 153, 0, 0.8)',
              transform: 'translate(-50%, -100%)',
              marginTop: '-10px',
            }}
          >
            <div dangerouslySetInnerHTML={{ __html: tooltipContent }} />
          </div>
        )}

        <ComposableMap
          projection="geoMercator"
          projectionConfig={{
            scale: 160,
          }}
          style={{ width: '100%', height: '100%', position: 'relative', zIndex: 1 }}
        >
          <ZoomableGroup
            zoom={zoom}
            center={center}
            onMoveEnd={handleMoveEnd}
            minZoom={1}
            maxZoom={4}
          >
            <Geographies geography={geoUrl}>
              {({ geographies }: { geographies: Array<{ rsmKey: string }> }) =>
                geographies.map((geo: { rsmKey: string }) => (
                  <Geography
                    key={geo.rsmKey}
                    geography={geo}
                    fill={mapColors.geography}
                    stroke={mapColors.stroke}
                    strokeWidth={0.5}
                    style={{
                      default: { outline: 'none' },
                      hover: { fill: mapColors.hover, outline: 'none' },
                      pressed: { outline: 'none' },
                    }}
                  />
                ))
              }
            </Geographies>

            {/* Region Markers */}
            {regionStatus.map((region) => (
              <Marker
                key={region.regionId}
                coordinates={[region.longitude, region.latitude]}
                onMouseEnter={(e: React.MouseEvent<SVGElement>) => {
                  const rect = (e.target as SVGElement).getBoundingClientRect();
                  const container = (e.target as SVGElement).closest('.relative')?.getBoundingClientRect();
                  if (container) {
                    setTooltipPosition({
                      x: rect.left - container.left + rect.width / 2,
                      y: rect.top - container.top,
                    });
                  }
                  setTooltipContent(`
                    <div style="padding: 4px;">
                      <b>${region.regionId} (${region.status})</b><br/>
                      <span style="font-size: 10px;">Click to filter dashboard</span>
                    </div>
                  `);
                }}
                onMouseLeave={() => setTooltipContent(null)}
                onClick={() => {
                  console.log('Region clicked:', region.regionId);
                  filterByRegion(region.regionId);
                }}
              >
                <circle
                  r={10 / zoom}
                  fill={statusColors[region.status] || '#6b7280'}
                  stroke="#ffffff"
                  strokeWidth={2.5 / zoom}
                  style={{
                    cursor: 'pointer',
                    transition: 'r 0.2s, stroke-width 0.2s',
                  }}
                  onMouseEnter={(e: React.MouseEvent<SVGCircleElement>) => {
                    (e.target as SVGCircleElement).setAttribute('r', String(14 / zoom));
                    (e.target as SVGCircleElement).setAttribute('stroke-width', String(3.5 / zoom));
                  }}
                  onMouseLeave={(e: React.MouseEvent<SVGCircleElement>) => {
                    (e.target as SVGCircleElement).setAttribute('r', String(10 / zoom));
                    (e.target as SVGCircleElement).setAttribute('stroke-width', String(2.5 / zoom));
                  }}
                />
                <text
                  textAnchor="middle"
                  y={-20 / zoom}
                  style={{
                    fontFamily: 'Inter, sans-serif',
                    fill: '#1f2937',
                    fontSize: `${14 / zoom}px`,
                    fontWeight: 'bold',
                    paintOrder: 'stroke',
                    stroke: '#ffffff',
                    strokeWidth: 3 / zoom,
                  }}
                >
                  {region.regionId}
                </text>
              </Marker>
            ))}
          </ZoomableGroup>
        </ComposableMap>
      </div>
    </div>
  );
};

export default memo(MapSection);
