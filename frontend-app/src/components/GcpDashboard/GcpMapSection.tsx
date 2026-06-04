import React, { useState, memo } from 'react';
import {
    ComposableMap,
    Geographies,
    Geography,
    Marker,
    ZoomableGroup,
} from 'react-simple-maps';

interface GcpMapSectionProps {
    regionStatus: any[];
    isLoading: boolean;
    onRegionClick: (name: string) => void;
}

const GEO_URL = 'https://cdn.jsdelivr.net/npm/world-atlas@2/countries-110m.json';

const STATUS_COLORS: Record<string, string> = {
    'active':      '#60c8f5',
    'issues':      '#f87171',
    'sustainable': '#6ee7b7',
    'inactive':    '#94a3b8',
};

const GcpMapSection: React.FC<GcpMapSectionProps> = ({ regionStatus, isLoading, onRegionClick }) => {
    const [tooltipContent, setTooltipContent] = useState<string | null>(null);
    const [tooltipPosition, setTooltipPosition] = useState({ x: 0, y: 0 });
    const [zoom, setZoom] = useState(1);
    const [center, setCenter] = useState<[number, number]>([0, 20]);

    const handleZoomIn  = () => { if (zoom < 4) setZoom(zoom * 1.5); };
    const handleZoomOut = () => { if (zoom > 1) setZoom(zoom / 1.5); };
    const handleMoveEnd = (position: { coordinates: [number, number]; zoom: number }) => {
        setCenter(position.coordinates);
        setZoom(position.zoom);
    };

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
                        <span className="text-[11px] font-medium text-[rgba(0,0,0,0.5)]">Issues</span>
                    </div>
                </div>
            </div>

            {/* Loading skeleton */}
            {isLoading && (
                <div className="h-[320px] bg-[rgba(0,0,0,0.03)] rounded-lg animate-pulse" />
            )}

            {/* Map Container */}
            {!isLoading && (
                <div className="relative rounded-lg overflow-hidden" style={{ height: '320px' }}>
                    {/* Zoom Controls */}
                    <div style={{
                        position: 'absolute',
                        right: 10,
                        bottom: 10,
                        zIndex: 50,
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
                            onClick={handleZoomIn}
                            style={{ width: 34, height: 34, border: 'none', background: 'transparent', fontSize: 20, lineHeight: 1, cursor: 'pointer', color: '#0f172a' }}
                            aria-label="Zoom in"
                        >+</button>
                        <div style={{ height: 1, background: 'rgba(15,23,42,0.08)' }} />
                        <button
                            onClick={handleZoomOut}
                            style={{ width: 34, height: 34, border: 'none', background: 'transparent', fontSize: 22, lineHeight: 1, cursor: 'pointer', color: '#0f172a' }}
                            aria-label="Zoom out"
                        >−</button>
                    </div>

                    {/* Tooltip */}
                    {tooltipContent && (
                        <div
                            className="absolute z-30 pointer-events-none px-3 py-2 rounded-lg text-white text-[11px] font-bold shadow-xl"
                            style={{
                                left: tooltipPosition.x,
                                top: tooltipPosition.y,
                                backgroundColor: 'rgba(0,0,0,0.88)',
                                border: '1px solid rgba(255,255,255,0.08)',
                                transform: 'translate(-50%, -100%)',
                                marginTop: '-10px',
                            }}
                        >
                            <div dangerouslySetInnerHTML={{ __html: tooltipContent }} />
                        </div>
                    )}

                    <ComposableMap
                        projection="geoMercator"
                        projectionConfig={{ scale: 160 }}
                        style={{ width: '100%', height: '100%', position: 'relative', zIndex: 1 }}
                    >
                        <defs>
                            <pattern
                                id="gcpDotPattern"
                                x="0" y="0"
                                width="7" height="7"
                                patternUnits="userSpaceOnUse"
                            >
                                <circle cx="2.5" cy="2.5" r="2.5" fill="#B0BEC5" />
                            </pattern>
                        </defs>

                        <ZoomableGroup zoom={zoom} center={center} onMoveEnd={handleMoveEnd} minZoom={1} maxZoom={4}>
                            <Geographies geography={GEO_URL}>
                                {({ geographies }: { geographies: Array<{ rsmKey: string }> }) =>
                                    geographies.map((geo: { rsmKey: string }) => (
                                        <Geography
                                            key={geo.rsmKey}
                                            geography={geo}
                                            style={{
                                                default: { fill: 'url(#gcpDotPattern)', stroke: 'transparent', outline: 'none' },
                                                hover:   { fill: 'url(#gcpDotPattern)', outline: 'none' },
                                                pressed: { outline: 'none' },
                                            }}
                                        />
                                    ))
                                }
                            </Geographies>

                            {regionStatus.map((region) => {
                                const color = STATUS_COLORS[region.status] ?? '#94a3b8';
                                return (
                                    <Marker
                                        key={region.region}
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
                                            setTooltipContent(`<div><p style="color:#94a3b8;font-size:9px;margin-bottom:2px;text-transform:uppercase;">GCP Region</p><p>${region.region} (${region.status})</p></div>`);
                                        }}
                                        onMouseLeave={() => setTooltipContent(null)}
                                        onClick={() => onRegionClick(region.region)}
                                    >
                                        <circle r={32 / zoom} fill={color} fillOpacity={0.08} />
                                        <circle r={22 / zoom} fill={color} fillOpacity={0.15} />
                                        <circle r={14 / zoom} fill={color} fillOpacity={0.30} />
                                        <circle r={8 / zoom} fill={color} stroke="#fff" strokeWidth={2.5 / zoom} style={{ cursor: 'pointer' }} />
                                        <text
                                            textAnchor="middle"
                                            y={26 / zoom}
                                            stroke="#fff"
                                            strokeWidth={4 / zoom}
                                            paintOrder="stroke"
                                            style={{ fontSize: 11 / zoom, fill: '#1e293b', fontFamily: 'inherit', fontWeight: 700, pointerEvents: 'none' }}
                                        >
                                            {region.region}
                                        </text>
                                    </Marker>
                                );
                            })}
                        </ZoomableGroup>
                    </ComposableMap>
                </div>
            )}
        </div>
    );
};

export default memo(GcpMapSection);
