import React, { useEffect, useRef } from 'react';
import { AzureCostHistory, AzureBillingItem } from '../../types/azureDashboard';

declare global {
  interface Window {
    Chart: any;
  }
}

interface AzureCostSectionProps {
  costHistory: AzureCostHistory;
  billingSummary: AzureBillingItem[];
  isLoading: boolean;
}

const AzureCostSection: React.FC<AzureCostSectionProps> = ({ costHistory, billingSummary, isLoading }) => {
  const chartRef = useRef<any>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const formatCurrency = (num: number): string => {
    return '$' + (num != null ? num.toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }) : '0.00');
  };

  useEffect(() => {
    if (!canvasRef.current || !costHistory || !window.Chart) return;

    // Destroy existing chart
    if (chartRef.current) {
      chartRef.current.destroy();
    }

    const backgroundColors = costHistory.anomalies.map(isAnomaly =>
      isAnomaly ? 'rgba(244, 63, 94, 0.8)' : 'rgba(0, 120, 212, 0.8)'
    );
    const borderColors = costHistory.anomalies.map(isAnomaly =>
      isAnomaly ? 'rgba(225, 29, 72, 1)' : 'rgba(0, 90, 158, 1)'
    );

    chartRef.current = new window.Chart(canvasRef.current, {
      type: 'bar',
      data: {
        labels: costHistory.labels,
        datasets: [{
          label: 'Monthly Cost',
          data: costHistory.costs,
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
            borderColor: 'rgba(0, 120, 212, 0.8)',
            borderWidth: 1,
            cornerRadius: 8,
            callbacks: {
              label: (c: any) => `Cost: ${formatCurrency(c.parsed.y)}`,
              afterLabel: (c: any) => costHistory.anomalies[c.dataIndex] ? 'Anomaly detected!' : ''
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
            grid: { color: 'rgba(0, 0, 0, 0.05)' }
          },
          x: {
            ticks: { color: '#6b7280' },
            grid: { display: false }
          }
        }
      }
    });

    return () => {
      if (chartRef.current) {
        chartRef.current.destroy();
      }
    };
  }, [costHistory]);

  const sortedBilling = billingSummary ? [...billingSummary].sort((a, b) => b.monthToDateCost - a.monthToDateCost).slice(0, 5) : [];

  return (
    <>
      {/* Cost History Chart */}
      <div className="lg:col-span-2 glass-card p-6 relative overflow-hidden h-full hover-glow">
        <div className="flex items-center justify-between mb-8">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 bg-white/80 rounded-2xl flex items-center justify-center border border-white shadow-sm transition-transform duration-300 hover:rotate-6">
              <svg className="w-7 h-7 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
              </svg>
            </div>
            <div>
              <h3 className="text-xl font-extrabold text-slate-900 tracking-tight uppercase px-1">Cost History</h3>
              <div className="flex items-center gap-2 mt-0.5 px-1">
                <span className="w-2 h-2 bg-blue-500 rounded-full animate-pulse shadow-[0_0_8px_rgba(59,130,246,0.5)]"></span>
                <span className="text-[11px] text-slate-400 font-extrabold uppercase tracking-widest italic">6-Month Trend</span>
              </div>
            </div>
          </div>
          {!isLoading && (
            <div className="flex items-center gap-2 bg-white/60 backdrop-blur-sm px-4 py-2 rounded-xl border border-white shadow-sm transition-all hover:bg-white">
              <i className="fas fa-microchip text-blue-500 text-xs animate-pulse"></i>
              <span className="text-[10px] uppercase font-extrabold tracking-widest text-slate-500">ML Anomaly Detection</span>
            </div>
          )}
        </div>

        {isLoading && (
          <div className="absolute inset-x-0 bottom-0 top-16 z-10 flex items-center justify-center bg-white/40 backdrop-blur-sm rounded-b-xl">
            <div className="loading-spinner w-12 h-12"></div>
          </div>
        )}

        <div className="h-80 p-6 bg-white/40 backdrop-blur-sm rounded-2xl border border-white shadow-inner">
          <canvas ref={canvasRef}></canvas>
        </div>
      </div>

      {/* Billing Summary */}
      <div className="glass-card p-6 relative overflow-hidden h-full hover-glow">
        <div className="flex items-center justify-between mb-8">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 bg-white/80 rounded-2xl flex items-center justify-center border border-white shadow-sm transition-transform duration-300 hover:rotate-6">
              <svg className="w-7 h-7 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
            </div>
            <div>
              <h3 className="text-xl font-extrabold text-slate-900 tracking-tight uppercase px-1">Billing Details</h3>
              <div className="flex items-center gap-2 mt-0.5 px-1">
                <span className="w-2 h-2 bg-emerald-500 rounded-full animate-pulse shadow-[0_0_8px_rgba(16,185,129,0.5)]"></span>
                <span className="text-[11px] text-slate-400 font-extrabold uppercase tracking-widest italic">Month-to-Date</span>
              </div>
            </div>
          </div>
        </div>

        {isLoading && (
          <div className="absolute inset-x-0 bottom-0 top-16 z-10 p-6 space-y-3 bg-white/80 backdrop-blur-sm rounded-b-xl">
            <div className="h-10 w-full skeleton rounded"></div>
            <div className="h-10 w-full skeleton rounded"></div>
            <div className="h-10 w-full skeleton rounded"></div>
          </div>
        )}

        {!isLoading && (
          <div className="overflow-y-auto max-h-80 custom-scrollbar pr-2">
            <table className="w-full text-sm text-left border-separate border-spacing-y-2">
              <thead className="text-[10px] text-slate-400 uppercase tracking-widest font-extrabold">
                <tr>
                  <th className="px-4 py-2">Service</th>
                  <th className="px-4 py-2 text-right">Cost (USD)</th>
                </tr>
              </thead>
              <tbody>
                {sortedBilling.length > 0 ? (
                  sortedBilling.map((item, index) => (
                    <tr key={index} className="group transition-all duration-300">
                      <th className="px-4 py-3 bg-white/40 group-hover:bg-white rounded-l-2xl border-l border-t border-b border-white transition-all duration-300">
                        <div className="flex items-center space-x-3">
                          <div
                            className="w-2.5 h-2.5 rounded-full shadow-sm"
                            style={{
                              backgroundColor: `hsl(${210 + (index * 60) % 360}, 75%, 60%)`,
                              boxShadow: `0 0 10px hsl(${210 + (index * 60) % 360}, 75%, 60%, 0.3)`
                            }}
                          ></div>
                          <span className="text-slate-700 font-extrabold tracking-tight">{item.serviceName}</span>
                        </div>
                      </th>
                      <td className="px-4 py-3 text-right bg-white/40 group-hover:bg-white rounded-r-2xl border-r border-t border-b border-white transition-all duration-300">
                        <span className="font-extrabold text-slate-900 metric-value">{formatCurrency(item.monthToDateCost)}</span>
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={2} className="px-4 py-12 text-center">
                      <div className="w-12 h-12 bg-gray-50 rounded-full flex items-center justify-center mx-auto mb-3 border border-gray-100">
                        <svg className="w-6 h-6 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                        </svg>
                      </div>
                      <p className="text-gray-500 font-bold text-xs uppercase tracking-widest">No Billing Data Available</p>
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

export default AzureCostSection;
