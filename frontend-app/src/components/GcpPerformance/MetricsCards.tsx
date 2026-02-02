/**
 * Metrics Cards Component
 * Displays performance summary metrics
 */

import React from 'react';
import { PerformanceSummary } from '../../types/gcpPerformance';

interface MetricsCardsProps {
  summary: PerformanceSummary;
  loading: boolean;
}

const MetricsCards: React.FC<MetricsCardsProps> = ({ summary, loading }) => {
  const metrics = [
    {
      title: 'Performance Score',
      value: summary.performanceScore || 0,
      icon: 'fa-tachometer-alt',
      bgColor: 'bg-blue-100',
      iconColor: 'text-blue-600',
    },
    {
      title: 'Total Insights',
      value: summary.totalInsights || 0,
      icon: 'fa-lightbulb',
      bgColor: 'bg-blue-100',
      iconColor: 'text-blue-600',
    },
    {
      title: 'Critical',
      value: summary.critical || 0,
      icon: 'fa-exclamation-triangle',
      bgColor: 'bg-red-100',
      iconColor: 'text-red-600',
      valueColor: 'text-red-600',
    },
    {
      title: 'Warnings',
      value: (summary.warning || 0) + (summary.weakWarning || 0),
      icon: 'fa-exclamation-circle',
      bgColor: 'bg-orange-100',
      iconColor: 'text-orange-600',
      valueColor: 'text-orange-600',
    },
  ];

  if (loading) {
    return (
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        {[1, 2, 3, 4].map((i) => (
          <div key={i} className="glass-card rounded-2xl p-6">
            <div className="flex items-center justify-between">
              <div className="flex-1">
                <div className="skeleton h-4 w-20 mb-2"></div>
                <div className="skeleton h-8 w-16"></div>
              </div>
              <div className="skeleton w-12 h-12 rounded-xl"></div>
            </div>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
      {metrics.map((metric, index) => (
        <div key={index} className="glass-card metric-card rounded-2xl p-6">
          <div className="flex items-center justify-between">
            <div>
              <h3 className="text-gray-600 text-sm font-medium">{metric.title}</h3>
              <p className={`text-3xl font-bold mt-1 ${metric.valueColor || 'text-gray-800'}`}>
                {metric.value}
              </p>
            </div>
            <div className={`w-12 h-12 ${metric.bgColor} rounded-xl flex items-center justify-center`}>
              <i className={`fas ${metric.icon} ${metric.iconColor}`}></i>
            </div>
          </div>
        </div>
      ))}
    </div>
  );
};

export default MetricsCards;
