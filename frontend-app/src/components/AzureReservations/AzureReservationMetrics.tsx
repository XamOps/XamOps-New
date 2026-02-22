import React from 'react';
import type { AzureReservationMetrics } from '../../types/azureReservations';

interface AzureReservationMetricsProps {
  metrics: AzureReservationMetrics;
}

const MetricCard: React.FC<{
  title: string;
  value: number;
  icon: string;
  gradient: string;
  bgColor: string;
  textColor: string;
}> = ({ title, value, icon, gradient, bgColor, textColor }) => (
  <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
    <div className="flex items-center justify-between mb-3">
      <span className={`text-xs font-bold uppercase tracking-wider ${textColor}`}>{title}</span>
      <div className={`w-9 h-9 rounded-lg ${bgColor} flex items-center justify-center shadow-sm`}>
        <i className={`${icon} ${textColor} text-sm`}></i>
      </div>
    </div>
    <div className={`text-3xl font-black bg-gradient-to-r ${gradient} bg-clip-text text-transparent`}>
      {value}
    </div>
  </div>
);

const AzureReservationMetrics: React.FC<AzureReservationMetricsProps> = ({ metrics }) => {
  return (
    <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
      <MetricCard
        title="Total Reservations"
        value={metrics.totalReservations}
        icon="fas fa-server"
        gradient="from-blue-600 to-blue-800"
        bgColor="bg-blue-100"
        textColor="text-blue-700"
      />
      <MetricCard
        title="Active Reservations"
        value={metrics.activeReservations}
        icon="fas fa-check-circle"
        gradient="from-green-600 to-green-800"
        bgColor="bg-green-100"
        textColor="text-green-700"
      />
      <MetricCard
        title="Savings Plans"
        value={metrics.totalSavingsPlans}
        icon="fas fa-piggy-bank"
        gradient="from-purple-600 to-purple-800"
        bgColor="bg-purple-100"
        textColor="text-purple-700"
      />
      <MetricCard
        title="Active Savings Plans"
        value={metrics.activeSavingsPlans}
        icon="fas fa-bolt"
        gradient="from-amber-500 to-amber-700"
        bgColor="bg-amber-100"
        textColor="text-amber-700"
      />
    </div>
  );
};

export default AzureReservationMetrics;
