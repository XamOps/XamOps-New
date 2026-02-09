import React, { useState, memo } from 'react';
import {
  ComposableMap,
  Geographies,
  Geography,
  Marker,
  ZoomableGroup,
} from 'react-simple-maps';
import { useTheme } from '../../contexts/ThemeContext';
import { AzureRegionStatus } from '../../types/azureDashboard';

interface AzureMapSectionProps {
  regionStatus: AzureRegionStatus[];
  isLoading: boolean;
  onRegionClick: (regionName: string) => void;
}

// World map TopoJSON URL
const geoUrl = 'https://cdn.jsdelivr.net/npm/world-atlas@2/countries-110m.json';

// Status colors matching Azure brand
const statusColors: Record<string, string> = {
  'ACTIVE': '#0078D4',      // Azure Blue
  'SEMI_ACTIVE': '#ef4444', // Red
  'SUSTAINABLE': '#22c55e', // Green
  'NON_ACTIVE': '#6b7280'   // Gray
};

const AzureMapSection: React.FC<AzureMapSectionProps> = ({ regionStatus, isLoading, onRegionClick }) => {
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
    <div className="lg:col-span-3 glass-card p-6 relative overflow-hidden h-full hover-glow">
      <div className="flex items-center justify-between mb-8">
        <div className="flex items-center gap-3">
          <div className="w-12 h-12 bg-white/80 rounded-2xl flex items-center justify-center border border-white shadow-sm transition-transform duration-300 hover:rotate-6">
            <svg className="w-7 h-7 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 002 2 2 2 0 012 2v.651M20 9v1a2 2 0 002 2 2 2 0 012 2v.651M20 9a2 2 0 00-2-2 2 2 0 01-2-2V3.935M16 2a11 11 0 1010 0v.5" />
            </svg>
          </div>
          <div>
            <h3 className="text-xl font-extrabold text-slate-900 tracking-tight uppercase px-1">Active Regions</h3>
            <div className="flex items-center gap-2 mt-0.5 px-1">
              <span className="w-2 h-2 bg-blue-500 rounded-full animate-pulse shadow-[0_0_8px_rgba(59,130,246,0.5)]"></span>
              <span className="text-[11px] text-slate-400 font-extrabold uppercase tracking-widest italic">Global Infrastructure</span>
            </div>
          </div>
        </div>
        {!isLoading && (
          <div className="hidden sm:flex items-center gap-4 bg-white/50 backdrop-blur-sm px-4 py-2 rounded-full border border-gray-100 shadow-sm">
            <div className="flex items-center gap-1.5">
              <div className="w-2 h-2 bg-blue-500 rounded-full shadow-lg shadow-blue-500/30"></div>
              <span className="text-[10px] uppercase font-black tracking-widest text-gray-500">Active</span>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="w-2 h-2 bg-green-500 rounded-full shadow-lg shadow-green-500/30"></div>
              <span className="text-[10px] uppercase font-black tracking-widest text-gray-500">Sustainable</span>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="w-2 h-2 bg-red-500 rounded-full shadow-lg shadow-red-500/30"></div>
              <span className="text-[10px] uppercase font-black tracking-widest text-gray-500">Issues</span>
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
        {/* Zoom Controls */}
        <div className="absolute bottom-4 left-4 z-50 flex flex-col gap-1">
          <button
            onClick={handleZoomIn}
            className="w-8 h-8 bg-white border border-slate-200 rounded-lg flex items-center justify-center hover:bg-slate-50 transition-colors shadow-sm"
            title="Zoom In"
          >
            <i className="fas fa-plus text-slate-600 text-[10px]"></i>
          </button>
          <button
            onClick={handleZoomOut}
            className="w-8 h-8 bg-white border border-slate-200 rounded-lg flex items-center justify-center hover:bg-slate-50 transition-colors shadow-sm"
            title="Zoom Out"
          >
            <i className="fas fa-minus text-slate-600 text-[10px]"></i>
          </button>
        </div>

        {/* Tooltip */}
        {tooltipContent && (
          <div
            className="absolute z-30 pointer-events-none px-3 py-2 rounded-lg text-white text-[11px] font-bold shadow-xl"
            style={{
              left: tooltipPosition.x,
              top: tooltipPosition.y,
              backgroundColor: 'rgba(15, 23, 42, 0.95)',
              border: '1px solid rgba(0, 120, 212, 0.5)',
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
                key={region.name}
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
                    <div style="padding: 2px;">
                      <p style="color: #94a3b8; font-size: 9px; margin-bottom: 2px; text-transform: uppercase;">Azure Region</p>
                      <p>${region.name} (${region.status})</p>
                    </div>
                  `);
                }}
                onMouseLeave={() => setTooltipContent(null)}
                onClick={() => onRegionClick(region.name)}
              >
                <circle
                  r={10 / zoom}
                  fill={statusColors[region.status] || '#6b7280'}
                  stroke="#ffffff"
                  strokeWidth={2.5 / zoom}
                  style={{
                    cursor: 'pointer',
                    transition: 'all 0.2s',
                  }}
                />
                <text
                  textAnchor="middle"
                  y={-20 / zoom}
                  style={{
                    fontFamily: 'Inter, sans-serif',
                    fill: '#475569',
                    fontSize: `${14 / zoom}px`,
                    fontWeight: '800',
                    paintOrder: 'stroke',
                    stroke: '#ffffff',
                    strokeWidth: 3 / zoom,
                    textTransform: 'uppercase'
                  }}
                >
                  {region.name}
                </text>
              </Marker>
            ))}
          </ZoomableGroup>
        </ComposableMap>
      </div>
    </div>
  );
};

export default memo(AzureMapSection);
