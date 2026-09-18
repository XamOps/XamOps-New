import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import SreInvestigateButton from '../SreInvestigateButton';
import GcpAlertResources from './GcpAlertResources';
import { describeResource } from './resourceDisplay';
import { alertCount, type GcpAlert } from '../../types/gcpAlerts';
import type { SreInvestigation } from '../../hooks/useSreInvestigation';
import { findLatestInvestigation } from '../../hooks/useSreInvestigation';

interface Props {
  alerts: GcpAlert[];
  projectId: string | null;
  investigations: SreInvestigation[];
  investigationsResolved: boolean;
  getUsagePercentage: (alert: GcpAlert) => number;
  getUsageColor: (alert: GcpAlert) => string;
  getUsageBadgeClass: (alert: GcpAlert) => string;
  getSecurityBadgeClass: (status: string) => string;
  getServiceIconClass: (service: string) => string;
  getGcpConsoleLink: (alert: GcpAlert) => string;
  getGcpActionText: (alert: GcpAlert) => string;
}

const TYPE_BADGE: Record<string, string> = {
  QUOTA: 'bg-blue-50 text-blue-700 border-blue-200',
  ANOMALY: 'bg-amber-50 text-amber-700 border-amber-200',
  SECURITY: 'bg-red-50 text-red-700 border-red-200',
};

const GcpAlertsTable: React.FC<Props> = ({
  alerts,
  projectId,
  investigations,
  investigationsResolved,
  getUsagePercentage,
  getUsageColor,
  getUsageBadgeClass,
  getSecurityBadgeClass,
  getServiceIconClass,
  getGcpConsoleLink,
  getGcpActionText,
}) => {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const toggle = (id: string) =>
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <caption className="sr-only">
          GCP alerts, grouped by category and resource, sorted by severity
        </caption>
        <thead>
          <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
            <th scope="col" className="w-8 px-2 py-3">
              <span className="sr-only">Expand</span>
            </th>
            <th scope="col" className="px-3 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">
              Severity
            </th>
            <th scope="col" className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">
              Issue
            </th>
            <th scope="col" className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">
              Resource
            </th>
            <th scope="col" className="px-4 py-3 text-right text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">
              Affected
            </th>
            <th scope="col" className="px-4 py-3 text-center text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">
              Actions
            </th>
          </tr>
        </thead>
        <tbody>
          {alerts.map(alert => {
            const count = alertCount(alert);
            const resources = alert.resources || [];
            const expandable = resources.length > 0;
            const isOpen = expanded.has(alert.id);
            const firstResource = resources.length > 0 ? describeResource(resources[0]) : null;

            // Resolved once at page level and passed down. This component renders one
            // SreInvestigateButton per row, and that component used to fetch the investigation list
            // itself on mount — 2,124 rows meant 2,124 identical requests, which queued behind the
            // browser's six-connection limit and made the page look frozen.
            const investigation = investigationsResolved
              ? findLatestInvestigation(investigations, alert.id, 'GENERIC')
              : null;

            return (
              <React.Fragment key={alert.id}>
                <tr className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors">

                  {/* Expand toggle */}
                  <td className="px-2 py-3 align-top">
                    {expandable ? (
                      <button
                        onClick={() => toggle(alert.id)}
                        aria-expanded={isOpen}
                        aria-controls={`resources-${alert.id}`}
                        aria-label={`${isOpen ? 'Hide' : 'Show'} affected resources for ${alert.name}`}
                        className="w-6 h-6 rounded flex items-center justify-center text-[rgba(0,0,0,0.4)] hover:text-indigo-600 hover:bg-indigo-50 transition-colors"
                      >
                        <Icon
                          icon="mdi:chevron-right"
                          width={16}
                          className={`transition-transform ${isOpen ? 'rotate-90' : ''}`}
                        />
                      </button>
                    ) : (
                      <span className="block w-6" />
                    )}
                  </td>

                  {/* Severity + type */}
                  <td className="px-3 py-3 align-top whitespace-nowrap">
                    <div className="flex flex-col gap-1.5 items-start">
                      <span className={`inline-block px-2 py-0.5 rounded text-[11px] font-bold ${getSecurityBadgeClass(alert.status)}`}>
                        {alert.status}
                      </span>
                      <span className={`inline-flex items-center px-2 py-0.5 rounded text-[10px] font-semibold border ${TYPE_BADGE[alert.type] || 'bg-gray-50 text-gray-600 border-gray-200'}`}>
                        {alert.type}
                      </span>
                    </div>
                  </td>

                  {/* Issue */}
                  <td className="px-4 py-3 align-top">
                    <div className="flex items-start gap-2.5">
                      <div className={`w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0 ${getServiceIconClass(alert.service)}`}>
                        <Icon icon="mdi:cog-outline" width={13} />
                      </div>
                      <div className="min-w-0">
                        <div className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] mb-0.5">
                          {alert.name}
                        </div>
                        <div className="text-[12px] text-[rgba(0,0,0,0.5)] line-clamp-2">
                          {alert.description}
                        </div>
                        <div className="text-[11px] text-[rgba(0,0,0,0.35)] mt-1 font-mono">
                          {alert.service}
                          {alert.region && alert.region !== 'global' && ` · ${alert.region}`}
                        </div>
                      </div>
                    </div>
                  </td>

                  {/* Resource */}
                  <td className="px-4 py-3 align-top max-w-[260px]">
                    {alert.type === 'QUOTA' ? (
                      <div className="space-y-1.5">
                        <div
                          className="progress-bar"
                          role="progressbar"
                          aria-valuenow={Math.round(getUsagePercentage(alert))}
                          aria-valuemin={0}
                          aria-valuemax={100}
                          aria-label={`${alert.name} usage`}
                        >
                          <div
                            className={`h-full rounded-full ${getUsageColor(alert)}`}
                            style={{ width: `${Math.min(getUsagePercentage(alert), 100)}%` }}
                          />
                        </div>
                        <div className="flex items-center justify-between gap-2">
                          <span className={`text-[11px] font-bold px-1.5 py-0.5 rounded ${getUsageBadgeClass(alert)}`}>
                            {Math.round(getUsagePercentage(alert))}%
                          </span>
                          <span className="text-[12px] text-[rgba(0,0,0,0.5)] tabular-nums">
                            {alert.usage} / {alert.limit}
                          </span>
                        </div>
                      </div>
                    ) : alert.type === 'ANOMALY' ? (
                      /*
                       * The figure is the spend *above* this service's usual daily baseline, not a
                       * total — hence the leading "+". Currency comes from the backend, which knows
                       * the project's billing currency; the pill used to render a bare number, so a
                       * rupee amount and a dollar amount were indistinguishable.
                       */
                      <span
                        className="inline-flex items-center px-2.5 py-1 rounded text-[12px] font-bold bg-red-50 text-red-700 border border-red-200 tabular-nums"
                        title="Spend above this service's usual daily average"
                      >
                        {alert.usage
                          ? `+${alert.currencySymbol ?? '$'}${alert.usage.toLocaleString()}`
                          : 'N/A'}
                      </span>
                    ) : firstResource ? (
                      <div className="min-w-0">
                        <div
                          className="text-[12px] font-medium text-[rgba(0,0,0,0.8)] font-mono truncate"
                          title={firstResource.full}
                        >
                          {firstResource.label}
                        </div>
                        {firstResource.context && (
                          <div className="text-[11px] text-[rgba(0,0,0,0.4)] truncate" title={firstResource.context}>
                            {firstResource.context}
                          </div>
                        )}
                        {count > 1 && (
                          <button
                            onClick={() => toggle(alert.id)}
                            className="text-[11px] font-medium text-indigo-600 hover:text-indigo-800 mt-0.5 transition-colors"
                          >
                            {isOpen ? 'Hide' : 'Show'} all {resources.length.toLocaleString()}
                          </button>
                        )}
                      </div>
                    ) : (
                      <span className="text-[12px] text-[rgba(0,0,0,0.35)]">—</span>
                    )}
                  </td>

                  {/* Affected count */}
                  <td className="px-4 py-3 align-top text-right whitespace-nowrap">
                    {count > 1 ? (
                      <span className="inline-flex flex-col items-end">
                        <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.85)] tabular-nums">
                          {count.toLocaleString()}
                        </span>
                        <span className="text-[10px] uppercase tracking-[0.06em] text-[rgba(0,0,0,0.35)]">
                          findings
                        </span>
                      </span>
                    ) : (
                      <span className="text-[13px] text-[rgba(0,0,0,0.45)] tabular-nums">1</span>
                    )}
                  </td>

                  {/* Actions */}
                  <td className="px-4 py-3 align-top">
                    <div className="flex items-center gap-2 justify-center">
                      <a
                        href={getGcpConsoleLink(alert)}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="inline-flex items-center gap-1.5 px-2.5 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white text-[12px] font-semibold rounded-lg transition-all whitespace-nowrap"
                      >
                        <Icon icon="mdi:open-in-new" width={12} />
                        {getGcpActionText(alert)}
                      </a>
                      {/*
                        GENERIC rather than GCP_VM. Every row used to declare itself a GCP virtual
                        machine, including container image vulnerabilities, cost anomalies and VPC
                        quota warnings, which sent the investigation down the wrong analysis path.

                        cloudAccountId is omitted on purpose: the frontend Account model carries only
                        the provider account id (here the GCP project id), so the previous
                        Number(projectId) evaluated to NaN and serialised as null.
                      */}
                      <SreInvestigateButton
                        resourceType="GENERIC"
                        resourceId={alert.id}
                        resourceName={alert.name}
                        investigation={investigation}
                        investigationResolved={investigationsResolved}
                      />
                    </div>
                  </td>
                </tr>

                {isOpen && expandable && (
                  <tr id={`resources-${alert.id}`} className="border-b border-[rgba(0,0,0,0.04)] bg-[rgba(0,0,0,0.015)]">
                    <td />
                    <td colSpan={5} className="px-4 py-3">
                      <GcpAlertResources resources={resources} projectId={projectId} />
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};

export default GcpAlertsTable;
