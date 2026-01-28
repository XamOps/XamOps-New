/**
 * MapSection Component
 *
 * Converted from: dashboard.html (Highcharts world map section)
 *
 * Displays interactive world map with AWS region points.
 * All logic and styling preserved from original.
 */

import React, { useEffect, useRef } from 'react';
import type { RegionStatus } from '../../types/liveDashboard';

interface MapSectionProps {
  regionStatus: RegionStatus[];
  isLoading: boolean;
}

// Highcharts global type (loaded via CDN)
declare global {
  interface Window {
    Highcharts: any;
  }
}

const MapSection: React.FC<MapSectionProps> = ({ regionStatus, isLoading }) => {
  const mapContainerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<any>(null);

  /**
   * Get color based on region status (matches original dashboard.html)
   */
  const statusColors: Record<string, string> = {
    'ACTIVE': '#3b82f6',     // blue
    'SEMI_ACTIVE': '#ef4444', // red
    'SUSTAINABLE': '#22c55e', // green
    'NON_ACTIVE': '#6b7280'   // gray
  };

  /**
   * Render Highcharts map (matches original renderHighchartMap function)
   */
  useEffect(() => {
    if (!mapContainerRef.current || isLoading) {
      return;
    }

    if (!regionStatus || regionStatus.length === 0) {
      console.warn('[Map] No region data available');
      return;
    }

    if (!window.Highcharts) {
      console.error('[Map Debug] CRITICAL: Highcharts object NOT found on window.');
      return;
    }

    console.log('[Map Debug] Highcharts object found on window.');

    // Prepare region points (matches original dashboard.html format)
    const regionPoints = regionStatus.map(r => ({
      name: r.regionId,
      fullName: `${r.regionId} (${r.status})`,
      lat: r.latitude,
      lon: r.longitude,
      color: statusColors[r.status] || '#6b7280',
      id: r.regionId,
      status: r.status
    }));

    console.log('Rendering map with', regionPoints.length, 'regions');

    // Fetch world map topology
    fetch('https://code.highcharts.com/mapdata/custom/world.topo.json')
      .then(response => {
        if (!response.ok) {
          throw new Error('Failed to fetch world map data');
        }
        return response.json();
      })
      .then(worldMap => {
        console.log('World map data loaded successfully');

        // Destroy existing chart if any
        if (chartRef.current) {
          chartRef.current.destroy();
          chartRef.current = null;
        }

        // Create Highcharts map (matches original dashboard.html exactly)
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
                stroke: '#FF9900',
                r: 4,
                fill: 'white'
              }
            }
          },
          tooltip: {
            useHTML: true,
            backgroundColor: 'rgba(0, 0, 0, 0.85)',
            borderColor: 'rgba(255, 153, 0, 0.8)',
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
                  click: function(this: any) {
                    console.log('Region clicked:', this.id);
                    filterByRegion(this.id);
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
              states: {
                hover: {
                  color: '#e2e8f0',
                  brightness: 0.05
                }
              },
              borderColor: '#cbd5e1',
              borderWidth: 0.5,
              dataLabels: {
                enabled: false
              }
            },
            {
              type: 'mappoint',
              name: 'AWS Regions',
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
                    animation: {
                      duration: 200
                    }
                  }
                }
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

        chartRef.current = chart;
        console.log('Map rendered successfully');
      })
      .catch(error => {
        console.error('Failed to load world map:', error);
        if (mapContainerRef.current) {
          mapContainerRef.current.innerHTML =
            '<div class="flex items-center justify-center h-full">' +
            '<div class="text-center p-8">' +
            '<i class="fas fa-exclamation-triangle text-red-500 text-4xl mb-4"></i>' +
            '<p class="text-red-600 font-semibold">Failed to load world map</p>' +
            '<p class="text-sm text-gray-500 mt-2">' + error.message + '</p>' +
            '</div></div>';
        }
      });

    // Cleanup
    return () => {
      if (chartRef.current) {
        chartRef.current.destroy();
        chartRef.current = null;
      }
    };
  }, [regionStatus, isLoading]);

  /**
   * Handle region click - show notification (matches original)
   */
  const filterByRegion = (regionId: string) => {
    const notification = document.createElement('div');
    notification.className = 'fixed top-4 right-4 bg-blue-50 border border-blue-200 text-blue-800 px-4 py-3 rounded-lg shadow-lg z-50 max-w-sm';
    notification.innerHTML = `
      <div class="flex items-center space-x-2">
        <i class="fas fa-filter text-blue-600"></i>
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
  };

  return (
    <div className="lg:col-span-3 chart-container p-6 rounded-xl relative">
      <div className="flex items-center justify-between mb-6">
        <h3 className="text-lg font-bold text-gray-800 flex items-center">
          <i className="fas fa-arrows-alt widget-handle mr-3 text-gray-400"></i>
          <i className="fas fa-globe-americas mr-2 text-blue-500"></i>
          Active Regions
        </h3>
        {!isLoading && (
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
        )}
      </div>

      {/* Map Loading Skeleton */}
      {isLoading && (
        <div className="absolute inset-x-0 bottom-0 top-16 bg-white/50 backdrop-blur-sm z-10 flex items-center justify-center rounded-b-xl">
          <div className="loading-spinner w-12 h-12"></div>
        </div>
      )}

      {/* Map Container */}
      <div
        ref={mapContainerRef}
        id="highcharts-map-container"
        style={{ height: '400px' }}
        className="rounded-lg overflow-hidden"
      ></div>
    </div>
  );
};

export default MapSection;
