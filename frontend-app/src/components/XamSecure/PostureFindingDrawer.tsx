import React, { useMemo } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@iconify/react';
import type { PostureFinding } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import SecurityMarkup from './SecurityMarkup';
import { FrameworkLogo, hasFrameworkLogo } from '../../utils/frameworkBranding';

interface Props {
  finding: PostureFinding;
  onClose: () => void;
}

const fmt = (iso: string | null | undefined): string =>
  iso ? new Date(iso).toLocaleString() : '—';

/**
 * Read-only detail panel for a single configuration (posture) finding. Parses the
 * synced raw payload into a human-readable layout — severity, resource, finding
 * details and remediation markup — instead of dumping raw JSON. Mirrors Upwind's
 * finding detail pane.
 */
const PostureFindingDrawer: React.FC<Props> = ({ finding, onClose }) => {
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
  const accountName: string | null = resource.cloud_account_name ?? null;
  const accountId = finding.cloudAccountId ?? resource.cloud_account_id ?? null;
  const arn: string | null = resource.arn ?? resource.id ?? null;
  const riskCategories: string[] = Array.isArray(resource.risk_categories) ? resource.risk_categories : [];

  return createPortal((
    <div className="fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      <div className="absolute inset-0 bg-black/30" />
      <div
        className="relative w-full max-w-2xl h-full bg-white shadow-2xl flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-start justify-between px-6 py-4 border-b border-gray-200">
          <div className="pr-4 min-w-0">
            <div className="flex items-center gap-2 mb-1.5">
              <SeverityChip severity={finding.severity} />
              {status && (
                <span className={`text-[10px] font-bold uppercase tracking-wide ${
                  status === 'FAIL' ? 'text-red-600' : status === 'PASS' ? 'text-emerald-600' : 'text-gray-500'}`}>
                  {status}
                </span>
              )}
            </div>
            <h3 className="text-sm font-bold text-gray-900 leading-snug break-words">{title}</h3>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100 flex-shrink-0" aria-label="Close">
            <Icon icon="mdi:close" width={18} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto overflow-x-hidden">
          <div className="px-6 py-5 space-y-6">
            {/* Meta */}
            <div className="grid grid-cols-2 gap-4">
              <Meta label="First seen" value={fmt(finding.firstSeenTime)} />
              <Meta label="Last evaluation" value={fmt(finding.lastSeenTime)} />
            </div>

            {/* Framework */}
            {frameworkTitle && (
              <Section title="Framework">
                <span className="inline-flex items-center gap-1.5 pl-1 pr-2 py-0.5 rounded-full text-[11px] font-semibold bg-gray-50 text-gray-700 border border-gray-200">
                  {hasFrameworkLogo(frameworkTitle) && <FrameworkLogo framework={frameworkTitle} size={16} />}
                  {frameworkTitle}{frameworkVersion ? ` · v${frameworkVersion}` : ''}
                </span>
              </Section>
            )}

            {/* Resource */}
            <Section title="Affected resource">
              <div className="border border-gray-200 rounded-lg divide-y divide-gray-50">
                <Row label="Name" value={resourceName} />
                <Row label="Type" value={resourceType} />
                <Row label="Region" value={region} />
                <Row label="Account" value={accountName ? `${accountName}${accountId ? ` (${accountId})` : ''}` : accountId} />
                {arn && <Row label="ARN" value={arn} mono />}
              </div>
              {riskCategories.length > 0 && (
                <div className="flex flex-wrap gap-1.5 mt-2">
                  {riskCategories.map((rc) => (
                    <span key={rc} className="px-2 py-0.5 rounded-md text-[10px] font-semibold bg-amber-50 text-amber-700 border border-amber-200">
                      {rc}
                    </span>
                  ))}
                </div>
              )}
            </Section>

            {/* Finding details */}
            <Section title="Finding details">
              <SecurityMarkup text={description} fallback="No description provided." />
            </Section>

            {/* Remediation */}
            <Section title="Remediation">
              <SecurityMarkup text={remediation} fallback="No remediation guidance provided." />
            </Section>
          </div>
        </div>
      </div>
    </div>
  ), document.body);
};

const Section: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
  <div>
    <h4 className="text-[10px] font-bold uppercase tracking-wide text-gray-400 mb-2">{title}</h4>
    {children}
  </div>
);

const Meta: React.FC<{ label: string; value: string }> = ({ label, value }) => (
  <div>
    <p className="text-[10px] font-bold uppercase tracking-wide text-gray-400 mb-1">{label}</p>
    <p className="text-xs font-medium text-gray-700">{value}</p>
  </div>
);

const Row: React.FC<{ label: string; value: string | null; mono?: boolean }> = ({ label, value, mono }) => (
  <div className="flex items-start gap-3 px-3 py-2">
    <span className="text-[10px] font-bold uppercase tracking-wide text-gray-400 w-20 flex-shrink-0 pt-0.5">{label}</span>
    <span className={`text-xs text-gray-700 break-all min-w-0 ${mono ? 'font-mono text-[11px]' : ''}`}>
      {value ?? '—'}
    </span>
  </div>
);

export default PostureFindingDrawer;
