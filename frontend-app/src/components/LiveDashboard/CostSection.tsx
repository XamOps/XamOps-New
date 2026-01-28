/**
 * CostSection Component
 *
 * Converted from: dashboard.html (cost history and billing summary section)
 *
 * Displays 6-month cost history with Chart.js and billing summary table.
 * All logic preserved from original dashboard.html.
 */

import React, { useEffect, useRef } from 'react';
import type { CostHistory } from '../../types/liveDashboard';

// API returns billing summary as an array with serviceName and monthToDateCost
interface BillingSummaryItem {
  serviceName: string;
  monthToDateCost: number;
}

interface CostSectionProps {
  costHistory: CostHistory;
  billingSummary: BillingSummaryItem[] | any; // Handle both array and object format
  isLoading: boolean;
}

// Chart.js global type (will be loaded via npm)
declare global {
  interface Window {
    Chart: any;
  }
}

const CostSection: React.FC<CostSectionProps> = ({ costHistory, billingSummary, isLoading }) => {
  const chartCanvasRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<any>(null);

  const formatCurrency = (num: number): string => {
    return '$' + (num != null ? num.toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }) : '0.00');
  };

  /**
   * Render Chart.js bar chart
   * Mimics the original renderCostHistoryChart() function exactly
   */
  useEffect(() => {
    if (!chartCanvasRef.current || isLoading || !costHistory) {
      return;
    }

    if (!window.Chart) {
      console.warn('⚠️ Chart.js not loaded');
      return;
    }

    // Destroy previous chart instance
    if (chartInstanceRef.current) {
      chartInstanceRef.current.destroy();
    }

    const ctx = chartCanvasRef.current.getContext('2d');
    if (!ctx) return;

    // Prepare anomaly colors (matches original dashboard.html)
    // anomalies array contains boolean values (true = anomaly, false = normal)
    const anomalies = costHistory.anomalies || [];
    const backgroundColors = costHistory.labels?.map((_, idx) =>
      anomalies[idx] ? 'rgba(239, 68, 68, 0.8)' : 'rgba(79, 70, 229, 0.8)'
    ) || [];
    const borderColors = costHistory.labels?.map((_, idx) =>
      anomalies[idx] ? 'rgba(220, 38, 38, 1)' : 'rgba(79, 70, 229, 1)'
    ) || [];

    // Create chart (matches original dashboard.html exactly)
    const chart = new window.Chart(ctx, {
      type: 'bar',
      data: {
        labels: costHistory.labels || [],
        datasets: [{
          label: 'Monthly Cost',
          data: costHistory.costs || [],
          backgroundColor: backgroundColors,
          borderColor: borderColors,
          borderWidth: 2,
          borderRadius: 8,
          borderSkipped: false
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: 'rgba(0, 0, 0, 0.8)',
            titleColor: 'white',
            bodyColor: 'white',
            borderColor: 'rgba(79, 70, 229, 0.8)',
            borderWidth: 1,
            cornerRadius: 8,
            callbacks: {
              label: (c: any) => `Cost: ${formatCurrency(c.parsed.y)}`,
              afterLabel: (c: any) => anomalies[c.dataIndex] ? 'Anomaly detected!' : ''
            }
          }
        },
        scales: {
          y: {
            beginAtZero: true,
            ticks: {
              callback: (v: any) => formatCurrency(v),
              color: '#6b7280'
            },
            grid: {
              color: 'rgba(0, 0, 0, 0.05)'
            }
          },
          x: {
            ticks: {
              color: '#6b7280'
            },
            grid: {
              display: false
            }
          }
        },
        onClick: (_evt: any, elements: any) => {
          if (elements.length > 0) {
            const index = elements[0].index;
            if (anomalies[index]) {
              alert(
                `Cost Anomaly Detected!\n\n` +
                `Month: ${costHistory.labels?.[index]}\n` +
                `Cost: ${formatCurrency(costHistory.costs?.[index] || 0)}\n\n` +
                `This cost is significantly higher than expected. Consider investigating your resource usage for this period.`
              );
            }
          }
        }
      }
    });

    chartInstanceRef.current = chart;

    // Cleanup
    return () => {
      if (chartInstanceRef.current) {
        chartInstanceRef.current.destroy();
        chartInstanceRef.current = null;
      }
    };
  }, [costHistory, isLoading]);

  // Normalize billing summary to array format
  const getBillingSummaryArray = (): BillingSummaryItem[] => {
    if (!billingSummary) return [];

    // If it's already an array
    if (Array.isArray(billingSummary)) {
      return billingSummary;
    }

    // If it has a services property (new format)
    if (billingSummary.services && Array.isArray(billingSummary.services)) {
      return billingSummary.services.map((s: any) => ({
        serviceName: s.serviceName,
        monthToDateCost: s.cost || s.monthToDateCost || 0
      }));
    }

    return [];
  };

  const summaryArray = getBillingSummaryArray();
  // Sort by cost descending (matches original dashboard.html)
  const sortedSummary = [...summaryArray].sort((a, b) => b.monthToDateCost - a.monthToDateCost);

  return (
    <>
      {/* Cost History Chart */}
      <div className="lg:col-span-2 chart-container p-6 rounded-xl relative">
        <div className="flex items-center justify-between mb-6">
          <h3 className="text-lg font-bold text-gray-800 flex items-center">
            <i className="fas fa-arrows-alt widget-handle mr-3 text-gray-400"></i>
            <i className="fas fa-chart-bar mr-2 text-green-500"></i>
            Cost History (Last 6 Months)
          </h3>
          <div className="flex items-center space-x-2 text-sm text-gray-500">
            <i className="fas fa-exclamation-triangle text-orange-500"></i>
            <span>Anomaly detection</span>
          </div>
        </div>

        {/* Chart Loading Skeleton */}
        {isLoading && (
          <div className="absolute inset-x-0 bottom-0 top-16 z-10 flex items-center justify-center bg-white/50 backdrop-blur-sm rounded-b-xl">
            <div className="loading-spinner w-12 h-12"></div>
          </div>
        )}

        {/* Chart Canvas */}
        <div className="h-80 p-4 bg-gray-50 rounded-lg">
          <canvas ref={chartCanvasRef}></canvas>
        </div>
      </div>

      {/* Billing Summary - matches original dashboard.html table format */}
      <div className="chart-container p-6 rounded-xl relative">
        <div className="flex items-center justify-between mb-6">
          <h3 className="text-lg font-bold text-gray-800 flex items-center">
            <i className="fas fa-arrows-alt widget-handle mr-3 text-gray-400"></i>
            <i className="fas fa-receipt mr-2 text-blue-500"></i>
            Billing Summary
          </h3>
          <div className="text-sm text-gray-500">MTD</div>
        </div>

        {/* Billing Loading Skeleton */}
        {isLoading && (
          <div className="absolute inset-x-0 bottom-0 top-16 z-10 p-6 space-y-3 bg-white/80 backdrop-blur-sm rounded-b-xl">
            <div className="h-10 w-full skeleton rounded"></div>
            <div className="h-10 w-full skeleton rounded"></div>
            <div className="h-10 w-full skeleton rounded"></div>
            <div className="h-10 w-full skeleton rounded"></div>
          </div>
        )}

        {/* Billing Table - matches original dashboard.html exactly */}
        {!isLoading && (
          <div className="overflow-y-auto max-h-80 custom-scrollbar">
            <table className="w-full text-sm text-left text-gray-500">
              <thead className="text-xs text-gray-700 uppercase bg-gray-50 sticky top-0 rounded-lg">
                <tr>
                  <th className="px-4 py-3 rounded-l-lg">Service</th>
                  <th className="px-4 py-3 text-right rounded-r-lg">Cost (USD)</th>
                </tr>
              </thead>
              <tbody>
                {sortedSummary.length > 0 ? (
                  sortedSummary.map((item, index) => (
                    <tr key={index} className="border-b hover:bg-gray-50 transition-colors">
                      <th className="px-4 py-4 font-semibold text-gray-800 text-left">
                        <div className="flex items-center space-x-2">
                          <div
                            className="w-2 h-2 rounded-full"
                            style={{
                              backgroundColor: `hsl(${240 + (index * 100) % 400}, 70%, 60%)`
                            }}
                          ></div>
                          <span>{item.serviceName}</span>
                        </div>
                      </th>
                      <td className="px-4 py-4 text-right">
                        <span className="font-bold text-gray-900">{formatCurrency(item.monthToDateCost)}</span>
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={2} className="px-4 py-8 text-center text-gray-500">
                      <i className="fas fa-receipt text-2xl mb-2"></i>
                      <p>No billing data available</p>
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
};

export default CostSection;
