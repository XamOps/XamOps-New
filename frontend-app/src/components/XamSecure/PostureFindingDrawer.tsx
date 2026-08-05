import React, { useMemo } from 'react';
import { Icon } from '@iconify/react';
import type { PostureFinding } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import SecurityMarkup from './SecurityMarkup';
import DetailPanel, { PanelBlock, PanelRow, type PanelSection } from './DetailPanel';
import { FrameworkLogo, hasFrameworkLogo } from '../../utils/frameworkBranding';

interface Props {
  finding: PostureFinding;
  onClose: () => void;
  /** Jumps to the cross-domain view for this finding's resource. */
  onOpenResource?: (resourceId: string) => void;
  /**
   * Every finding for the same check across the estate. Powers Statistics and Violating
   * controls, which are about the check rather than this one row.
   */
  siblings?: PostureFinding[];
}

const fmt = (iso: string | null | undefined): string =>
  iso ? new Date(iso).toLocaleString() : '—';

/**
 * Configuration-finding panel. Section names and order follow Upwind's own finding panel:
 * Finding details / Statistics / Violating controls / Remediation / Rule query.
 *
 * "Rule query" is shown disabled — the underlying policy expression is not in the API
 * payload. Marking it unavailable is deliberate: silently dropping it would misrepresent
 * what Upwind offers, and inventing a query would be worse.
 */
const PostureFindingDrawer: React.FC<Props> = ({
  finding, onClose, onOpenResource, siblings,
}) => {
  const raw = useMemo<any>(() => {
    try { return finding.rawPayload ? JSON.parse(finding.rawPayload) : {}; }
    catch { return {}; }
  }, [finding.rawPayload]);

  const check = raw.check ?? {};
  const resource = raw.resource ?? {};
  const framework = raw.framework ?? {};

  const title = finding.title ?? check.title ?? 'Configuration finding';
  const status = finding.status ?? raw.status ?? null;
  const description: string | null = check.description ?? null;
  const remediation: string | null = check.remediation ?? null;
  const frameworkTitle: string | null = finding.frameworkTitle ?? framework.title ?? null;
  const frameworkVersion: string | null = framework.version ?? null;
  const resourceName = finding.resourceName ?? resource.name ?? null;
  const resourceType = finding.resourceType ?? resource.type ?? null;
  const region = finding.region ?? resource.region ?? null;
  const clusterId = finding.clusterId ?? resource.cluster_id ?? null;
  const namespace = finding.namespace ?? resource.namespace ?? null;
  const accountName: string | null = resource.cloud_account_name ?? null;
  const accountId = finding.cloudAccountId ?? resource.cloud_account_id ?? null;
  const arn: string | null = resource.arn ?? resource.id ?? null;
  const riskCategories: string[] =
    Array.isArray(resource.risk_categories) ? resource.risk_categories : [];

  const checkKey = finding.checkTitle ?? finding.title;
  const sameCheck = useMemo(
    () => (siblings ?? []).filter((s) => (s.checkTitle ?? s.title) === checkKey),
    [siblings, checkKey]);

  // Violating controls: the frameworks this same check maps to. A finding is stored once per
  // framework, so the sibling rows ARE the control list.
  const frameworks = useMemo(() => {
    const m = new Map<string, number>();
    for (const s of sameCheck) {
      if (s.frameworkTitle) m.set(s.frameworkTitle, (m.get(s.frameworkTitle) ?? 0) + 1);
    }
    if (frameworkTitle && !m.has(frameworkTitle)) m.set(frameworkTitle, 1);
    return [...m.entries()].sort((a, b) => b[1] - a[1]);
  }, [sameCheck, frameworkTitle]);

  const affected = useMemo(() => {
    const ids = new Set<string>();
    for (const s of sameCheck) if (s.resourceId) ids.add(s.resourceId);
    if (finding.resourceId) ids.add(finding.resourceId);
    return ids.size;
  }, [sameCheck, finding.resourceId]);

  const severityMix = useMemo(() => {
    const m = new Map<string, number>();
    for (const s of sameCheck) {
      const k = (s.severity ?? 'UNKNOWN').toString();
      m.set(k, (m.get(k) ?? 0) + 1);
    }
    return [...m.entries()].sort((a, b) => b[1] - a[1]);
  }, [sameCheck]);

  const sections: PanelSection[] = [
    {
      id: 'details', label: 'Finding details', icon: 'mdi:format-list-bulleted',
      render: () => (
        <>
          <PanelBlock><SecurityMarkup text={description} fallback="No description provided." /></PanelBlock>
          <PanelBlock>
            <PanelRow label="Default rule severity" value={<SeverityChip severity={finding.severity} />} />
            <PanelRow label="Status" value={status} />
            <PanelRow label="Last evaluation" value={fmt(finding.lastSeenTime)} />
            <PanelRow label="First seen" value={fmt(finding.firstSeenTime)} />
            <PanelRow label="Risk category" value={
              riskCategories.length === 0 ? null : (
                <span className="flex flex-wrap gap-1.5">
                  {riskCategories.map((rc) => (
                    <span key={rc} className="px-2 py-0.5 rounded-md text-[10px] font-semibold
                                              bg-amber-50 text-amber-700 border border-amber-200">{rc}</span>
                  ))}
                </span>
              )} />
          </PanelBlock>
          <PanelBlock title="Affected resource"
                      action={onOpenResource && finding.resourceId ? (
                        <button type="button" onClick={() => onOpenResource(finding.resourceId!)}
                                className="inline-flex items-center gap-1 rounded-lg border border-gray-200
                                           px-2.5 py-1 text-[10px] font-bold text-gray-700
                                           hover:border-gray-400 focus:outline-none
                                           focus-visible:ring-2 focus-visible:ring-gray-900">
                          Open resource <Icon icon="mdi:arrow-top-right" width={11} />
                        </button>
                      ) : undefined}>
            <PanelRow label="Name" value={resourceName} />
            <PanelRow label="Type" value={resourceType} />
            <PanelRow label="Region" value={region} />
            {namespace && <PanelRow label="Namespace" value={namespace} />}
            {clusterId && <PanelRow label="Cluster" value={clusterId} mono />}
            <PanelRow label="Account"
                      value={accountName ? `${accountName}${accountId ? ` (${accountId})` : ''}` : accountId} />
            {arn && <PanelRow label="ARN" value={arn} mono />}
          </PanelBlock>
        </>
      ),
    },
    {
      id: 'statistics', label: 'Statistics', icon: 'mdi:chart-box-outline',
      render: () => (
        <>
          <PanelBlock>
            <div className="grid grid-cols-3 gap-3">
              <Stat label="Affected resources" value={affected} />
              <Stat label="Findings for this check" value={Math.max(sameCheck.length, 1)} />
              <Stat label="Frameworks" value={frameworks.length} />
            </div>
          </PanelBlock>
          {severityMix.length > 0 && (
            <PanelBlock title="Severity across this check">
              {severityMix.map(([sev, n]) => (
                <div key={sev} className="flex items-center gap-3 py-1">
                  <span className="w-24"><SeverityChip severity={sev as never} /></span>
                  <div className="flex-1 h-2 rounded-full bg-gray-100 overflow-hidden">
                    <div className="h-full rounded-full bg-gray-900"
                         style={{ width: `${(n / Math.max(1, sameCheck.length)) * 100}%` }} />
                  </div>
                  <span className="w-8 text-right text-xs font-semibold text-gray-700">{n}</span>
                </div>
              ))}
            </PanelBlock>
          )}
          {sameCheck.length === 0 && (
            <p className="text-[11px] text-gray-400">
              Counts cover only the findings loaded on this page.
            </p>
          )}
        </>
      ),
    },
    {
      id: 'controls', label: 'Violating controls', icon: 'mdi:shield-alert-outline',
      count: frameworks.length,
      render: () => (
        <PanelBlock>
          {frameworks.length === 0 ? (
            <p className="text-xs text-gray-400">No framework mapping recorded for this check.</p>
          ) : (
            <div className="space-y-1.5">
              {frameworks.map(([fw, n]) => (
                <div key={fw} className="flex items-center justify-between gap-3 rounded-lg
                                         border border-gray-200 px-3 py-2">
                  <span className="flex min-w-0 items-center gap-2">
                    {hasFrameworkLogo(fw) && <FrameworkLogo framework={fw} size={16} />}
                    <span className="truncate text-xs font-medium text-gray-800">{fw}</span>
                    {fw === frameworkTitle && frameworkVersion && (
                      <span className="text-[10px] text-gray-400">v{frameworkVersion}</span>
                    )}
                  </span>
                  <span className="flex-shrink-0 text-[10px] text-gray-500">
                    <span className="font-bold text-gray-800">{n}</span> findings
                  </span>
                </div>
              ))}
            </div>
          )}
        </PanelBlock>
      ),
    },
    {
      id: 'remediation', label: 'Remediation', icon: 'mdi:auto-fix',
      render: () => (
        <PanelBlock>
          <SecurityMarkup text={remediation} fallback="No remediation guidance provided." />
        </PanelBlock>
      ),
    },
    {
      id: 'rule-query', label: 'Rule query', icon: 'mdi:code-braces',
      available: false,
      unavailableReason:
        'Upwind does not expose the underlying policy expression through the API.',
      render: () => null,
    },
  ];

  return (
    <DetailPanel
      title={title}
      subtitle={
        <>
          <SeverityChip severity={finding.severity} />
          {status && (
            <span className={`text-[10px] font-bold uppercase tracking-wide ${
              status === 'FAIL' ? 'text-red-600'
                : status === 'PASS' ? 'text-emerald-600' : 'text-gray-500'}`}>
              {status}
            </span>
          )}
          {frameworkTitle && (
            <span className="inline-flex items-center gap-1.5 rounded-full border border-gray-200
                             bg-gray-50 py-0.5 pl-1 pr-2 text-[11px] font-semibold text-gray-700">
              {hasFrameworkLogo(frameworkTitle) && <FrameworkLogo framework={frameworkTitle} size={16} />}
              {frameworkTitle}{frameworkVersion ? ` · v${frameworkVersion}` : ''}
            </span>
          )}
        </>
      }
      sections={sections}
      onClose={onClose}
    />
  );
};

const Stat: React.FC<{ label: string; value: number }> = ({ label, value }) => (
  <div className="rounded-lg border border-gray-200 p-3">
    <p className="text-lg font-bold text-gray-900">{value.toLocaleString()}</p>
    <p className="mt-0.5 text-[10px] text-gray-500">{label}</p>
  </div>
);

export default PostureFindingDrawer;
