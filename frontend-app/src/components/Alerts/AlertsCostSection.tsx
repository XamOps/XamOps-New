/**
 * AlertsCostSection
 *
 * Cost spikes from the last 14 days: which service, by how much, against what was expected.
 */

import React from 'react';
import AlertsSection, { AlertActionLink, SEVERITY_STYLES, SeverityBadge } from './AlertsSection';
import { costSummary } from './alertGroups';
import type { AlertLink, AnomalyAlert } from '../../types/alerts';

interface AlertsCostSectionProps {
  alerts: AnomalyAlert[];
  total: number;
  isLoading: boolean;
  incomplete: boolean;
  getAlertLink: (alert: AnomalyAlert) => AlertLink;
}

const AlertsCostSection: React.FC<AlertsCostSectionProps> = ({
  alerts,
  total,
  isLoading,
  incomplete,
  getAlertLink,
}) => (
  <AlertsSection
    icon="mdi:chart-timeline-variant-shimmer"
    title="Cost spikes"
    description="Spend above what AWS Cost Explorer expected, in the last 14 days. Spikes under $1 are left out; the FinOps page lists every anomaly."
    total={total}
    visible={alerts.length}
    isLoading={isLoading}
    incomplete={incomplete}
    emptyText="No cost spikes over $1 in the last 14 days."
  >
    <ul>
      {alerts.map((alert) => (
        <li key={alert.id} className="flex items-stretch border-b border-[rgba(0,0,0,0.05)] last:border-b-0">
          <span className={`w-1 shrink-0 ${SEVERITY_STYLES[alert.severity].bar}`} aria-hidden />
          <div className="flex-1 min-w-0 px-5 py-3.5 flex flex-col md:flex-row md:items-center gap-3">
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <SeverityBadge severity={alert.severity} />
                <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{alert.service}</p>
              </div>
              <p className="text-[12px] text-[rgba(0,0,0,0.6)] mt-1">{costSummary(alert)}</p>
            </div>
            <div className="shrink-0 md:text-right">
              <p className="text-[15px] font-semibold tabular-nums text-amber-700">
                +{alert.currencySymbol}
                {alert.usage.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
              </p>
              <p className="text-[11px] text-[rgba(0,0,0,0.45)]">over expected</p>
            </div>
            <div className="shrink-0">
              <AlertActionLink link={getAlertLink(alert)} />
            </div>
          </div>
        </li>
      ))}
    </ul>
  </AlertsSection>
);

export default AlertsCostSection;
