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

    const globalAny = globalThis as any;
    if (!globalAny.Highcharts) {
      console.error('[Map Debug] CRITICAL: Highcharts object NOT found on globalThis.');
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
        const chart = globalAny.Highcharts.mapChart(mapContainerRef.current, {
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
                  click: function (this: any) {
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
            '<svg class="w-12 h-12 text-red-500 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">' +
            '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />' +
            '</svg>' +
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
