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
            borderColor: 'rgba(79, 70, 229, 0.8)',
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

  if (isLoading) {
    return (
      <>
        <div className="lg:col-span-2 chart-container p-6 rounded-xl skeleton" style={{ height: '300px' }}></div>
        <div className="chart-container p-6 rounded-xl skeleton" style={{ height: '300px' }}></div>
      </>
    );
  }

  return (
    <>
      <div className="lg:col-span-2 glass-card chart-container p-6 rounded-xl">
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center text-slate-600">
            <i className="fas fa-arrows-alt widget-handle mr-3 text-lg cursor-move opacity-40 hover:opacity-100 transition-opacity"></i>
            <h3 className="text-lg font-black tracking-tight text-slate-800 uppercase italic flex items-center">
              <i className="fas fa-chart-bar mr-2 text-emerald-500"></i>
              Cost History (Last 6 Months)
            </h3>
          </div>
          <div className="flex items-center gap-2 text-[10px] font-black uppercase text-slate-400 tracking-widest bg-slate-100 px-3 py-1 rounded-full border border-slate-200">
            <i className="fas fa-exclamation-triangle text-amber-500"></i>
            <span>Anomaly detection active</span>
          </div>
        </div>
        <div className="h-80 p-4 bg-slate-50/50 rounded-xl border border-slate-100">
          <canvas ref={canvasRef}></canvas>
        </div>
      </div>
      <div className="glass-card chart-container p-6 rounded-xl">
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center text-slate-600">
            <i className="fas fa-arrows-alt widget-handle mr-3 text-lg cursor-move opacity-40 hover:opacity-100 transition-opacity"></i>
            <h3 className="text-lg font-black tracking-tight text-slate-800 uppercase italic flex items-center">
              <i className="fas fa-receipt mr-2 text-blue-500"></i>
              Billing
            </h3>
          </div>
          <div className="text-[10px] font-black uppercase text-slate-400 tracking-widest bg-slate-100 px-3 py-1 rounded-full border border-slate-200">
            MTD Summary
          </div>
        </div>
        <div className="overflow-y-auto max-h-80 custom-scrollbar pr-2">
          <table className="w-full text-sm text-left">
            <thead className="text-[10px] text-slate-400 uppercase tracking-widest bg-slate-50/80 sticky top-0 backdrop-blur-md">
              <tr>
                <th className="px-4 py-3 rounded-l-lg font-black">Service</th>
                <th className="px-4 py-3 text-right rounded-r-lg font-black">Cost (USD)</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {billingSummary && billingSummary.length > 0 ? (
                billingSummary.sort((a, b) => b.monthToDateCost - a.monthToDateCost).map((item, index) => (
                  <tr key={index} className="group hover:bg-slate-50/50 transition-colors">
                    <td className="px-4 py-4">
                      <div className="flex items-center space-x-3">
                        <div className={`w-2 h-2 rounded-full shadow-sm ${index === 0 ? 'bg-blue-500' :
                          index === 1 ? 'bg-indigo-500' :
                            index === 2 ? 'bg-purple-500' :
                              'bg-slate-300'
                          }`}></div>
                        <span className="font-bold text-slate-700 group-hover:text-blue-600 transition-colors">{item.serviceName}</span>
                      </div>
                    </td>
                    <td className="px-4 py-4 text-right">
                      <span className="font-black text-slate-900 metric-value">{formatCurrency(item.monthToDateCost)}</span>
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={2} className="px-4 py-12 text-center">
                    <div className="w-12 h-12 bg-slate-50 rounded-full flex items-center justify-center mx-auto mb-3">
                      <i className="fas fa-receipt text-slate-300 text-xl"></i>
                    </div>
                    <p className="text-[11px] font-bold text-slate-400 uppercase tracking-widest">No billing data available</p>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
};

export default AzureCostSection;
