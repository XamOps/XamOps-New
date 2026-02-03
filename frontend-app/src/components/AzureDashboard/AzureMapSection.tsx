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
              color: '#f1f5f9',
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
    <div className="lg:col-span-3 chart-container p-6 rounded-xl">
      <div className="flex items-center justify-between mb-6">
        <h3 className="text-lg font-bold text-gray-800 flex items-center">
          <i className="fas fa-arrows-alt widget-handle mr-3 text-gray-400"></i>
          <i className="fas fa-globe-americas mr-2 text-blue-500"></i>
          Active Regions
        </h3>
        <div className="flex items-center space-x-4 text-sm">
          <div className="flex items-center space-x-1">
            <div className="w-3 h-3 bg-blue-500 rounded-full"></div>
            <span className="text-gray-600">Active</span>
          </div>
          <div className="flex items-center space-x-1">
            <div className="w-3 h-3 bg-green-500 rounded-full"></div>
            <span className="text-gray-600">Sustainable</span>
          </div>
          <div className="flex items-center space-x-1">
            <div className="w-3 h-3 bg-red-500 rounded-full"></div>
            <span className="text-gray-600">Issues</span>
          </div>
        </div>
      </div>
      <div id="azure-map-container" style={{ height: '400px' }} className="rounded-lg overflow-hidden"></div>
    </div>
  );
};

export default AzureMapSection;
