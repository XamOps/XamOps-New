import React, { useEffect, useRef } from 'react';

interface GcpMapSectionProps {
    regionStatus: any[];
    isLoading: boolean;
    onRegionClick: (name: string) => void;
}

declare global {
    interface Window {
        Highcharts: any;
    }
}

const GcpMapSection: React.FC<GcpMapSectionProps> = ({ regionStatus, isLoading, onRegionClick }) => {
    const mapContainerRef = useRef<HTMLDivElement>(null);
    const chartRef = useRef<any>(null);

    const statusColors: Record<string, string> = {
        'active': '#4285F4',     // Google Blue
        'issues': '#EA4335',     // Google Red
        'sustainable': '#34A853', // Google Green
        'inactive': '#9AA0A6'    // Google Gray
    };

    useEffect(() => {
        if (!mapContainerRef.current || isLoading) {
            return;
        }

        if (!regionStatus || regionStatus.length === 0) {
            return;
        }

        if (!window.Highcharts) {
            console.error('Highcharts not found');
            return;
        }

        // Prepare region points
        const regionPoints = regionStatus.map(r => ({
            name: r.region, // GcpDashboard passes 'region' not 'regionId'
            fullName: `${r.region} (${r.status})`,
            lat: r.latitude,
            lon: r.longitude,
            color: statusColors[r.status] || '#9AA0A6',
            id: r.region,
            status: r.status
        }));

        // Fetch world map topology
        fetch('https://code.highcharts.com/mapdata/custom/world.topo.json')
            .then(response => response.json())
            .then(worldMap => {
                if (chartRef.current) {
                    chartRef.current.destroy();
                    chartRef.current = null;
                }

                const chart = window.Highcharts.mapChart(mapContainerRef.current, {
                    chart: {
                        map: worldMap,
                        backgroundColor: 'transparent',
                        style: {
                            fontFamily: 'Inter, sans-serif'
                        }
                    },
                    title: { text: null },
                    credits: { enabled: false },
                    mapNavigation: {
                        enabled: true,
                        enableMouseWheelZoom: true,
                        buttonOptions: {
                            verticalAlign: 'bottom',
                            theme: {
                                'stroke-width': 1,
                                stroke: '#4285F4',
                                r: 4,
                                fill: 'white'
                            }
                        }
                    },
                    tooltip: {
                        useHTML: true,
                        backgroundColor: 'rgba(0, 0, 0, 0.85)',
                        borderColor: '#4285F4',
                        borderRadius: 8,
                        style: {
                            color: 'white',
                            fontSize: '12px'
                        },
                        pointFormat: '<div style="padding: 4px;">' +
                            '<b>{point.fullName}</b><br/>' +
                            '<span style="font-size: 10px;">Click to filter dashboard</span>' +
                            '</div>'
                    },
                    plotOptions: {
                        mappoint: {
                            point: {
                                events: {
                                    click: function (this: any) {
                                        onRegionClick(this.id);
                                    }
                                }
                            }
                        }
                    },
                    series: [
                        {
                            type: 'map',
                            name: 'World',
                            mapData: worldMap,
                            color: '#f8f9fa',
                            nullColor: '#e9ecef',
                            enableMouseTracking: false,
                            borderColor: '#dee2e6',
                            borderWidth: 0.5,
                            dataLabels: { enabled: false }
                        },
                        {
                            type: 'mappoint',
                            name: 'GCP Regions',
                            data: regionPoints,
                            marker: {
                                lineWidth: 2,
                                lineColor: '#ffffff',
                                radius: 8,
                                symbol: 'circle',
                                states: {
                                    hover: {
                                        lineWidth: 3,
                                        radius: 12,
                                        animation: { duration: 200 }
                                    }
                                }
                            },
                            cursor: 'pointer',
                            dataLabels: {
                                enabled: true,
                                format: '{point.name}',
                                style: {
                                    color: '#202124',
                                    fontSize: '10px',
                                    fontWeight: 'bold',
                                    textOutline: '2px white'
                                },
                                y: -15
                            }
                        }
                    ]
                });

                chartRef.current = chart;
            })
            .catch(err => console.error('Failed to load world map:', err));

        return () => {
            if (chartRef.current) {
                chartRef.current.destroy();
                chartRef.current = null;
            }
        };
    }, [regionStatus, isLoading, onRegionClick]);

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

            {isLoading && (
                <div className="absolute inset-x-0 bottom-0 top-16 bg-white/50 backdrop-blur-sm z-10 flex items-center justify-center rounded-b-xl">
                    <div className="loading-spinner w-12 h-12"></div>
                </div>
            )}

            <div ref={mapContainerRef} style={{ height: '400px' }} className="rounded-lg overflow-hidden"></div>
        </div>
    );
};

export default GcpMapSection;
