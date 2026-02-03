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
      isAnomaly ? 'rgba(239, 68, 68, 0.8)' : 'rgba(79, 70, 229, 0.8)'
    );
    const borderColors = costHistory.anomalies.map(isAnomaly =>
      isAnomaly ? 'rgba(220, 38, 38, 1)' : 'rgba(79, 70, 229, 1)'
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
      <div className="lg:col-span-2 chart-container p-6 rounded-xl">
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
        <div className="h-80 p-4 bg-gray-50 rounded-lg">
          <canvas ref={canvasRef}></canvas>
        </div>
      </div>
      <div className="chart-container p-6 rounded-xl">
        <div className="flex items-center justify-between mb-6">
          <h3 className="text-lg font-bold text-gray-800 flex items-center">
            <i className="fas fa-arrows-alt widget-handle mr-3 text-gray-400"></i>
            <i className="fas fa-receipt mr-2 text-blue-500"></i>
            Billing Summary
          </h3>
          <div className="text-sm text-gray-500">MTD</div>
        </div>
        <div className="overflow-y-auto max-h-80 custom-scrollbar">
          <table className="w-full text-sm text-left text-gray-500">
            <thead className="text-xs text-gray-700 uppercase bg-gray-50 sticky top-0 rounded-lg">
              <tr>
                <th className="px-4 py-3 rounded-l-lg">Service</th>
                <th className="px-4 py-3 text-right rounded-r-lg">Cost (USD)</th>
              </tr>
            </thead>
            <tbody>
              {billingSummary && billingSummary.length > 0 ? (
                billingSummary.sort((a, b) => b.monthToDateCost - a.monthToDateCost).map((item, index) => (
                  <tr key={index} className="border-b hover:bg-gray-50 transition-colors">
                    <th className="px-4 py-4 font-semibold text-gray-800 text-left">
                      <div className="flex items-center space-x-2">
                        <div className={`w-2 h-2 bg-indigo-${500 + (index * 100) % 400} rounded-full`}></div>
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
      </div>
    </>
  );
};

export default AzureCostSection;
