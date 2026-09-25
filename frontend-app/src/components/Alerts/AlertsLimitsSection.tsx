/**
 * AlertsLimitsSection
 *
 * Service limits close to full: one row per limit, one chip per region (or zone) where it is
 * close. Each chip opens that region's Service Quotas console, which is where an increase is
 * requested.
 */

import React from 'react';
import { Icon } from '@iconify/react';
import AlertsSection, { SEVERITY_STYLES } from './AlertsSection';
import type { QuotaGroup } from './alertGroups';

interface AlertsLimitsSectionProps {
  groups: QuotaGroup[];
  total: number;
  isLoading: boolean;
  incomplete: boolean;
  consoleLink: (region: string) => string;
}

const AlertsLimitsSection: React.FC<AlertsLimitsSectionProps> = ({
  groups,
  total,
  isLoading,
  incomplete,
  consoleLink,
}) => {
  const visible = groups.reduce((n, g) => n + g.usages.length, 0);

  return (
    <AlertsSection
      icon="mdi:gauge"
      title="Service limits"
      description="Limits at 75% or more of what AWS allows. At 100% nothing new of that kind can be created. Select a region to request an increase."
      total={total}
      visible={visible}
      isLoading={isLoading}
      incomplete={incomplete}
      emptyText="No service limit is above 75% in any region."
    >
      <ul>
        {groups.map((group) => {
          const perZone = group.usages.some((u) => u.label !== u.region);
          const places = `${group.usages.length} ${perZone ? 'zone' : 'region'}${group.usages.length === 1 ? '' : 's'}`;
          return (
            <li key={group.key} className="flex items-stretch border-b border-[rgba(0,0,0,0.05)] last:border-b-0">
              <span className={`w-1 shrink-0 ${SEVERITY_STYLES[group.severity].bar}`} aria-hidden />
              <div className="flex-1 min-w-0 px-5 py-3.5 flex flex-col lg:flex-row lg:items-center gap-3">
                <div className="lg:w-72 shrink-0">
                  <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{group.name}</p>
                  <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-0.5">
                    {group.service} · close to full in {places}
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  {group.usages.map((u) => (
                    <a
                      key={u.label}
                      href={consoleLink(u.region)}
                      target="_blank"
                      rel="noopener noreferrer"
                      title={`${u.label}: ${u.usage} of ${u.limit} used (${u.percent.toFixed(0)}%). Opens Service Quotas to request an increase.`}
                      className={`inline-flex items-center gap-2 rounded-md border px-2.5 py-1.5 text-[12px] transition-colors ${SEVERITY_STYLES[u.severity].chip}`}
                    >
                      <span className="font-medium">{u.label}</span>
                      <span className="font-semibold tabular-nums">
                        {u.usage.toLocaleString()} / {u.limit.toLocaleString()}
                      </span>
                      <span className="w-10 h-1 rounded-full bg-[rgba(0,0,0,0.1)] overflow-hidden" aria-hidden>
                        <span
                          className={`block h-full rounded-full ${SEVERITY_STYLES[u.severity].bar}`}
                          style={{ width: `${Math.min(u.percent, 100)}%` }}
                        />
                      </span>
                      <Icon icon="mdi:open-in-new" width={11} className="opacity-50" />
                    </a>
                  ))}
                </div>
              </div>
            </li>
          );
        })}
      </ul>
    </AlertsSection>
  );
};

export default AlertsLimitsSection;
