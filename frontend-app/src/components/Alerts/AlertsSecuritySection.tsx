/**
 * AlertsSecuritySection
 *
 * Critical and high security findings. Each row leads with what is wrong and where; the long
 * explanation is trimmed to two lines, and the remediation — which both sources append to the
 * description — is split out under "Show details" instead of running on as one paragraph.
 */

import React, { useState } from 'react';
import AlertsSection, { AlertActionLink, SEVERITY_STYLES, SeverityBadge } from './AlertsSection';
import { splitFinding } from './alertGroups';
import type { AlertLink, SecurityAlert } from '../../types/alerts';

interface AlertsSecuritySectionProps {
  alerts: SecurityAlert[];
  total: number;
  isLoading: boolean;
  incomplete: boolean;
  getAlertLink: (alert: SecurityAlert) => AlertLink;
}

/** Past this many characters the two-line clamp is likely to cut the summary off. */
const LONG_SUMMARY = 180;

const FindingRow: React.FC<{ alert: SecurityAlert; link: AlertLink }> = ({ alert, link }) => {
  const [open, setOpen] = useState(false);
  const { summary, remediation } = splitFinding(alert.name, alert.description);
  const expandable = remediation !== null || summary.length > LONG_SUMMARY;

  return (
    <li className="flex items-stretch border-b border-[rgba(0,0,0,0.05)] last:border-b-0">
      <span className={`w-1 shrink-0 ${SEVERITY_STYLES[alert.severity].bar}`} aria-hidden />
      <div className="flex-1 min-w-0 px-5 py-3.5 flex flex-col md:flex-row md:items-start gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <SeverityBadge severity={alert.severity} />
            <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{alert.name}</p>
          </div>
          <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-1">
            {alert.service}
            {alert.region && ` · ${alert.region}`}
          </p>
          {summary && (
            <p className={`text-[12px] leading-5 text-[rgba(0,0,0,0.6)] mt-1.5 ${open ? '' : 'line-clamp-2'}`}>
              {summary}
            </p>
          )}
          {open && remediation && (
            <div className="mt-2 rounded-md border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)] px-3 py-2">
              <p className="text-[11px] font-semibold uppercase tracking-[0.06em] text-[rgba(0,0,0,0.5)] mb-0.5">How to fix</p>
              <p className="text-[12px] leading-5 text-[rgba(0,0,0,0.7)]">{remediation}</p>
            </div>
          )}
          {expandable && (
            <button
              type="button"
              onClick={() => setOpen((o) => !o)}
              className="mt-1.5 text-[12px] font-medium text-indigo-600 hover:text-indigo-800"
              aria-expanded={open}
            >
              {open ? 'Show less' : remediation ? 'Show details and how to fix' : 'Show more'}
            </button>
          )}
        </div>
        <div className="shrink-0">
          <AlertActionLink link={link} />
        </div>
      </div>
    </li>
  );
};

const AlertsSecuritySection: React.FC<AlertsSecuritySectionProps> = ({
  alerts,
  total,
  isLoading,
  incomplete,
  getAlertLink,
}) => (
  <AlertsSection
    icon="mdi:shield-alert-outline"
    title="Security findings"
    description="Critical and high findings from Security Hub and XamOps's own checks. The full list is in the Security Center."
    total={total}
    visible={alerts.length}
    isLoading={isLoading}
    incomplete={incomplete}
    emptyText="No critical or high security findings."
  >
    <ul>
      {alerts.map((alert) => (
        <FindingRow key={alert.id} alert={alert} link={getAlertLink(alert)} />
      ))}
    </ul>
  </AlertsSection>
);

export default AlertsSecuritySection;
