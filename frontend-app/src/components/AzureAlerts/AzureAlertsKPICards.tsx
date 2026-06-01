import React from 'react';
import type { AzureAlertsData } from '../../types/azureAlerts';

interface AzureAlertsKPICardsProps {
  alertsData: AzureAlertsData | null;
  isLoading: boolean;
}

const SkeletonCard: React.FC = () => (
  <div className="bg-[rgba(0,0,0,0.04)] rounded-xl h-24 animate-pulse" />
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
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
        <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">
          Total Alerts
        </p>
        <p
          className="tabular-nums text-[rgba(0,0,0,0.88)]"
          style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, letterSpacing: '0px', lineHeight: '32px' }}
        >
          {totalCount}
        </p>
        <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">All monitor alerts</p>
      </div>

      {/* Active */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
        <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">
          Active
        </p>
        <p
          className="tabular-nums text-red-600"
          style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, letterSpacing: '0px', lineHeight: '32px' }}
        >
          {activeCount}
        </p>
        <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">New + Acknowledged alerts</p>
      </div>

      {/* Critical / Error */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
        <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">
          Critical / Error
        </p>
        <p
          className="tabular-nums text-orange-600"
          style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, letterSpacing: '0px', lineHeight: '32px' }}
        >
          {criticalCount}
        </p>
        <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">Sev0 + Sev1 severity alerts</p>
      </div>

      {/* Warnings */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
        <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">
          Warnings
        </p>
        <p
          className="tabular-nums text-amber-500"
          style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, letterSpacing: '0px', lineHeight: '32px' }}
        >
          {warningCount}
        </p>
        <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">Sev2 warning alerts</p>
      </div>
    </div>
  );
};

export default AzureAlertsKPICards;
