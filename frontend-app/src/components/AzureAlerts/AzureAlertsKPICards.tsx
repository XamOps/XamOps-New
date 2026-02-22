import React from 'react';
import type { AzureAlertsData } from '../../types/azureAlerts';

interface AzureAlertsKPICardsProps {
  alertsData: AzureAlertsData | null;
  isLoading: boolean;
}

const SkeletonCard: React.FC = () => (
  <div className="glass-card rounded-xl p-5 shadow-sm animate-pulse">
    <div className="flex items-center justify-between mb-3">
      <div className="h-3 bg-gray-200 rounded w-24"></div>
      <div className="w-9 h-9 rounded-lg bg-gray-200"></div>
    </div>
    <div className="h-8 bg-gray-200 rounded w-16 mb-1"></div>
    <div className="h-3 bg-gray-100 rounded w-28"></div>
  </div>
);

const AzureAlertsKPICards: React.FC<AzureAlertsKPICardsProps> = ({ alertsData, isLoading }) => {
  if (isLoading) {
    return (
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-4">
        <SkeletonCard />
        <SkeletonCard />
        <SkeletonCard />
        <SkeletonCard />
      </div>
    );
  }

  const alerts = alertsData?.alerts ?? [];
  const totalCount = alerts.length;
  const activeCount = alerts.filter(a => a.alertState === 'New' || a.alertState === 'Acknowledged').length;
  const criticalCount = alerts.filter(a => a.severity === 'Sev0' || a.severity === 'Sev1').length;
  const warningCount = alerts.filter(a => a.severity === 'Sev2').length;

  return (
    <div className="grid grid-cols-1 lg:grid-cols-4 gap-4">
      {/* Total Alerts */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-purple-700">
            Total Alerts
          </span>
          <div className="w-9 h-9 rounded-lg bg-purple-100 flex items-center justify-center">
            <i className="fas fa-list text-purple-600 text-sm"></i>
          </div>
        </div>
        <div className="text-3xl font-black bg-gradient-to-r from-purple-600 to-purple-800 bg-clip-text text-transparent">
          {totalCount}
        </div>
        <p className="text-xs text-gray-500 mt-1">All monitor alerts</p>
      </div>

      {/* Active */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-red-700">
            Active
          </span>
          <div className="w-9 h-9 rounded-lg bg-red-100 flex items-center justify-center">
            <i className="fas fa-exclamation-triangle text-red-600 text-sm"></i>
          </div>
        </div>
        <div className="text-3xl font-black bg-gradient-to-r from-red-600 to-red-800 bg-clip-text text-transparent">
          {activeCount}
        </div>
        <p className="text-xs text-gray-500 mt-1">New + Acknowledged alerts</p>
      </div>

      {/* Critical / Error */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-orange-700">
            Critical / Error
          </span>
          <div className="w-9 h-9 rounded-lg bg-orange-100 flex items-center justify-center">
            <i className="fas fa-fire text-orange-600 text-sm"></i>
          </div>
        </div>
        <div className="text-3xl font-black bg-gradient-to-r from-orange-600 to-orange-800 bg-clip-text text-transparent">
          {criticalCount}
        </div>
        <p className="text-xs text-gray-500 mt-1">Sev0 + Sev1 severity alerts</p>
      </div>

      {/* Warnings */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-amber-700">
            Warnings
          </span>
          <div className="w-9 h-9 rounded-lg bg-amber-100 flex items-center justify-center">
            <i className="fas fa-exclamation-circle text-amber-600 text-sm"></i>
          </div>
        </div>
        <div className="text-3xl font-black bg-gradient-to-r from-amber-600 to-amber-800 bg-clip-text text-transparent">
          {warningCount}
        </div>
        <p className="text-xs text-gray-500 mt-1">Sev2 warning alerts</p>
      </div>
    </div>
  );
};

export default AzureAlertsKPICards;
