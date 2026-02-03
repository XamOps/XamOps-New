import React from 'react';
import { AzureKPIData, AzureCostHistory } from '../../types/azureDashboard';

interface AzureKPISectionProps {
  kpiData: AzureKPIData;
  costHistory: AzureCostHistory;
  isLoading: boolean;
}

const AzureKPISection: React.FC<AzureKPISectionProps> = ({ kpiData, costHistory, isLoading }) => {
  const formatCurrency = (num: number): string => {
    return '$' + (num != null ? num.toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }) : '0.00');
  };

  const kpis = [
    {
      id: 'mtdSpend',
      title: 'Month-to-Date Spend',
      value: formatCurrency(kpiData.mtdSpend),
      icon: 'fa-dollar-sign',
      color: { iconClass: 'text-blue-500', gradientClass: 'from-blue-500 to-blue-600' },
      change: kpiData.lastMonthSpend > 0 ? ((kpiData.mtdSpend - kpiData.lastMonthSpend) / kpiData.lastMonthSpend * 100).toFixed(1) : '0.0'
    },
    {
      id: 'forecastedSpend',
      title: 'Forecasted Spend',
      value: formatCurrency(kpiData.forecastedSpend),
      icon: 'fa-chart-line',
      color: { iconClass: 'text-yellow-500', gradientClass: 'from-yellow-500 to-yellow-600' },
      change: kpiData.mtdSpend > 0 ? '+' + ((kpiData.forecastedSpend - kpiData.mtdSpend) / kpiData.mtdSpend * 100).toFixed(1) : '+0.0'
    },
    {
      id: 'lastMonthSpend',
      title: 'Last Month Spend',
      value: formatCurrency(kpiData.lastMonthSpend),
      icon: 'fa-calendar-alt',
      color: { iconClass: 'text-gray-500', gradientClass: 'from-gray-500 to-gray-600' },
      change: costHistory.costs.length > 2 ? ((kpiData.lastMonthSpend - costHistory.costs[costHistory.costs.length - 3]) / costHistory.costs[costHistory.costs.length - 3] * 100).toFixed(1) : '0.0'
    },
    {
      id: 'potentialSavings',
      title: 'Potential Savings',
      value: formatCurrency(kpiData.potentialSavings),
      icon: 'fa-piggy-bank',
      color: { iconClass: 'text-green-500', gradientClass: 'from-green-500 to-green-600' },
      change: '+100.0'
    }
  ];

  if (isLoading) {
    return (
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        {[1, 2, 3, 4].map((i) => (
          <div key={i} className="glass-card p-6 rounded-xl h-32 skeleton"></div>
        ))}
      </div>
    );
  }

  return (
    <div className="fade-in">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center text-gray-600">
          <i className="fas fa-arrows-alt widget-handle mr-3 text-lg"></i>
          <h2 className="text-lg font-bold">Key Performance Indicators</h2>
        </div>
        <div className="flex items-center space-x-2 text-sm text-gray-500">
          <i className="fas fa-chart-line"></i>
          <span>Live metrics</span>
        </div>
      </div>
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        {kpis.map(kpi => {
          const changeAsString = String(kpi.change);
          return (
            <div key={kpi.id} className="kpi-card rounded-xl p-6 group">
              <div className="main-content">
                <div className="flex justify-between items-start">
                  <div className="flex-1">
                    <h3 className="text-gray-600 text-sm font-medium mb-1">{kpi.title}</h3>
                    <p className="text-2xl font-bold text-gray-900 mb-2">{kpi.value}</p>
                    <div className="flex items-center text-sm">
                      <span className={`text-${changeAsString.startsWith('+') ? 'green' : changeAsString.startsWith('-') ? 'red' : 'gray'}-600 font-medium`}>
                        {kpi.change}%
                      </span>
                      <span className="text-gray-500 ml-1">vs last period</span>
                    </div>
                  </div>
                  <div className={`bg-gradient-to-br ${kpi.color.gradientClass} p-3 rounded-xl shadow-lg`}>
                    <i className={`fas ${kpi.icon} text-white text-lg`}></i>
                  </div>
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default AzureKPISection;
