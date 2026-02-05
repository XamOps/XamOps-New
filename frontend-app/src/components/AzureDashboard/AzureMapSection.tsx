import React, { useEffect } from 'react';
import { AzureRegionStatus } from '../../types/azureDashboard';

interface AzureMapSectionProps {
  regionStatus: AzureRegionStatus[];
  isLoading: boolean;
  onRegionClick: (regionName: string) => void;
}

const AzureMapSection: React.FC<AzureMapSectionProps> = ({ regionStatus, isLoading, onRegionClick }) => {
  useEffect(() => {
    if (!regionStatus || regionStatus.length === 0 || !window.Highcharts) {
      return;
    }

    const statusColors: Record<string, string> = {
      'ACTIVE': '#3b82f6',
      'SEMI_ACTIVE': '#ef4444',
      'SUSTAINABLE': '#22c55e',
      'NON_ACTIVE': '#6b7280'
    };

    const regionPoints = regionStatus.map(r => ({
      name: r.name,
      fullName: `${r.name} (${r.status})`,
      lat: r.latitude,
      lon: r.longitude,
      color: statusColors[r.status] || '#6b7280',
      id: r.name,
      status: r.status
    }));

    fetch('https://code.highcharts.com/mapdata/custom/world.topo.json')
      .then(response => response.json())
      .then(worldMap => {
        window.Highcharts.mapChart('azure-map-container', {
          chart: {
            map: worldMap,
            backgroundColor: 'transparent',
          },
          title: { text: null },
          credits: { enabled: false },
          mapNavigation: {
            enabled: true,
            enableMouseWheelZoom: true,
          },
          tooltip: {
            useHTML: true,
            backgroundColor: 'rgba(0, 0, 0, 0.85)',
            borderColor: 'rgba(255, 153, 0, 0.8)',
            borderRadius: 8,
            style: { color: 'white', fontSize: '12px' },
            pointFormat: '<div style="padding: 4px;"><b>{point.fullName}</b></div>'
          },
          plotOptions: {
            mappoint: {
              point: {
                events: {
                  click: (e: any) => {
                    onRegionClick(e.point.id);
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
              color: '#F8FAFC',
              nullColor: '#e5e7eb',
              enableMouseTracking: false,
              borderColor: '#cbd5e1',
              borderWidth: 0.5,
              dataLabels: { enabled: false }
            },
            {
              type: 'mappoint',
              name: 'Azure Regions',
              data: regionPoints,
              marker: {
                lineWidth: 2,
                lineColor: '#ffffff',
                radius: 8,
                symbol: 'circle',
              },
              cursor: 'pointer',
              dataLabels: {
                enabled: true,
                format: '{point.name}',
                style: {
                  color: '#1f2937',
                  fontSize: '10px',
                  fontWeight: 'bold',
                  textOutline: '2px white'
                },
                y: -15
              }
            }
          ]
        });
      })
      .catch(error => {
        console.error('Failed to load world map:', error);
      });
  }, [regionStatus, onRegionClick]);

  if (isLoading) {
    return (
      <div className="lg:col-span-3 chart-container p-6 rounded-xl skeleton" style={{ height: '450px' }}></div>
    );
  }

  return (
    <div className="lg:col-span-3 glass-card chart-container p-6 rounded-xl">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center text-slate-600">
          <i className="fas fa-arrows-alt widget-handle mr-3 text-lg cursor-move opacity-40 hover:opacity-100 transition-opacity"></i>
          <h3 className="text-lg font-black tracking-tight text-slate-800 uppercase italic flex items-center">
            <i className="fas fa-globe-americas mr-2 text-blue-500"></i>
            Active Regions
          </h3>
        </div>
        <div className="flex items-center gap-4 text-[10px] font-black uppercase text-slate-400 tracking-widest bg-slate-100/50 px-4 py-1.5 rounded-full border border-slate-200">
          <div className="flex items-center gap-1.5">
            <div className="w-2 h-2 bg-blue-500 rounded-full shadow-[0_0_5px_rgba(59,130,246,0.4)]"></div>
            <span>Active</span>
          </div>
          <div className="flex items-center gap-1.5 border-l border-slate-200 pl-4">
            <div className="w-2 h-2 bg-emerald-500 rounded-full shadow-[0_0_5px_rgba(16,185,129,0.4)]"></div>
            <span>Sustainable</span>
          </div>
          <div className="flex items-center gap-1.5 border-l border-slate-200 pl-4">
            <div className="w-2 h-2 bg-rose-500 rounded-full shadow-[0_0_5px_rgba(244,63,94,0.4)]"></div>
            <span>Issues</span>
          </div>
        </div>
      </div>
      <div id="azure-map-container" style={{ height: '400px' }} className="rounded-lg overflow-hidden"></div>
    </div>
  );
};

export default AzureMapSection;
