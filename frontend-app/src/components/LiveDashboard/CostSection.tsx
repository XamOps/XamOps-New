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

    const globalAny = globalThis as any;
    if (!globalAny.Chart) {
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
    const chart = new globalAny.Chart(ctx, {
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
      <div className="lg:col-span-2 glass-card p-6 rounded-2xl border border-gray-200 shadow-xl shadow-emerald-500/5 relative overflow-hidden h-full">
        <div className="flex items-center justify-between mb-8">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-emerald-50 rounded-xl flex items-center justify-center border border-emerald-100 shadow-sm">
              <svg className="w-6 h-6 text-emerald-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
              </svg>
            </div>
            <div>
              <h3 className="text-xl font-black text-gray-900 tracking-tight uppercase px-1">Cost History</h3>
              <p className="text-[10px] text-gray-400 font-bold uppercase tracking-widest flex items-center gap-1.5 px-1 mt-0.5">
                <span className="w-1.5 h-1.5 bg-emerald-400 rounded-full animate-pulse"></span>
                6-Month Trend
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2 bg-white/50 backdrop-blur-sm px-4 py-2 rounded-full border border-gray-100 shadow-sm">
            <svg className="w-4 h-4 text-amber-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
            </svg>&nbsp;<span className="text-[10px] uppercase font-black tracking-widest text-gray-500">Anomaly Check Active</span>
          </div>
        </div>

        {/* Chart Loading Skeleton */}
        {isLoading && (
          <div className="absolute inset-x-0 bottom-0 top-16 z-10 flex items-center justify-center bg-white/50 backdrop-blur-sm rounded-b-xl">
            <div className="loading-spinner w-12 h-12"></div>
          </div>
        )}

        {/* Chart Canvas */}
        <div className="h-80 p-6 bg-gray-50/50 backdrop-blur-sm rounded-2xl border border-gray-100 shadow-inner">
          <canvas ref={chartCanvasRef}></canvas>
        </div>
      </div>

      {/* Billing Summary - matches original dashboard.html table format */}
      <div className="glass-card p-6 rounded-2xl border border-gray-200 shadow-xl shadow-blue-500/5 relative overflow-hidden h-full">
        <div className="flex items-center justify-between mb-8">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-blue-50 rounded-xl flex items-center justify-center border border-blue-100 shadow-sm">
              <svg className="w-6 h-6 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
            </div>
            <div>
              <h3 className="text-xl font-black text-gray-900 tracking-tight uppercase px-1">Billing Summary</h3>
              <p className="text-[10px] text-gray-400 font-bold uppercase tracking-widest flex items-center gap-1.5 px-1 mt-0.5">
                <span className="w-1.5 h-1.5 bg-blue-400 rounded-full animate-pulse"></span>
                Month-to-Date
              </p>
            </div>
          </div>
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
                    <tr key={item.serviceName} className="border-b border-gray-50 hover:bg-gray-50/80 transition-all group">
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
                      <svg className="w-10 h-10 text-gray-300 mx-auto mb-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                      </svg>
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
