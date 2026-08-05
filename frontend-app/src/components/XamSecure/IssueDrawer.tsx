import React from 'react';
import { Icon } from '@iconify/react';
import type { IssueDetail } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import SecurityMarkup from './SecurityMarkup';
import ResourceLink from './ResourceLink';
import DetailPanel, { PanelBlock, PanelRow, type PanelSection } from './DetailPanel';
import { FrameworkLogo, hasFrameworkLogo } from '../../utils/frameworkBranding';

interface Props {
  title: string;
  detail: IssueDetail | null;
  isLoading: boolean;
  onClose: () => void;
  /** Opens the cross-domain resource view for an affected resource. */
  onOpenResource?: (resourceId: string) => void;
}

const fmt = (iso: string | null): string =>
  iso ? new Date(iso).toLocaleString() : '—';

/**
 * Issue panel — one posture check across every resource that fails it. Section names follow
 * Upwind's finding panel (Finding details / Violating controls / Remediation), with Affected
 * resources standing in for their Statistics view, since an issue IS the grouped form.
 */
const IssueDrawer: React.FC<Props> = ({
  title, detail, isLoading, onClose, onOpenResource,
}) => {
  const sections: PanelSection[] = detail ? [
    {
      id: 'details', label: 'Finding details', icon: 'mdi:format-list-bulleted', group: 'Issue',
      render: () => (
        <>
          <PanelBlock><SecurityMarkup text={detail.description} fallback="No description provided." /></PanelBlock>
          <PanelBlock>
            <PanelRow label="Severity" value={<SeverityChip severity={detail.severity} />} />
            <PanelRow label="Affected resources" value={detail.resourceCount} />
            <PanelRow label="First seen" value={fmt(detail.firstSeen)} />
            <PanelRow label="Last evaluation" value={fmt(detail.lastEvaluation)} />
          </PanelBlock>
        </>
      ),
    },
    {
      id: 'controls', label: 'Violating controls', icon: 'mdi:shield-alert-outline', group: 'Issue',
      count: detail.frameworks.length,
      available: detail.frameworks.length > 0,
      unavailableReason: 'No framework mapping recorded for this check.',
      render: () => (
        <PanelBlock>
          <div className="flex flex-wrap gap-1.5">
            {detail.frameworks.map((f) => (
              hasFrameworkLogo(f) ? (
                <span key={f} className="inline-flex items-center gap-1.5 rounded-full border
                                         border-gray-200 bg-gray-50 py-0.5 pl-1 pr-2 text-[11px]
                                         font-semibold text-gray-700">
                  <FrameworkLogo framework={f} size={16} />
                  {f}
                </span>
              ) : (
                <span key={f} className="rounded-md border border-gray-200 bg-gray-100 px-2 py-0.5
                                         text-[11px] font-semibold text-gray-600">{f}</span>
              )
            ))}
          </div>
        </PanelBlock>
      ),
    },
    {
      id: 'remediation', label: 'Remediation', icon: 'mdi:auto-fix', group: 'Issue',
      render: () => (
        <PanelBlock>
          <SecurityMarkup text={detail.remediation} fallback="No remediation guidance provided." />
        </PanelBlock>
      ),
    },
    {
      id: 'resources', label: 'Affected resources', icon: 'mdi:server', group: 'Resources',
      count: detail.resources.length,
      render: () => (
        <div className="overflow-hidden rounded-lg border border-gray-200">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-gray-100 bg-gray-50 text-left text-[10px]
                             uppercase text-gray-400">
                <th className="px-3 py-2">Resource</th>
                <th className="px-3 py-2">Type</th>
                <th className="px-3 py-2">Region</th>
                <th className="px-3 py-2">Severity</th>
              </tr>
            </thead>
            <tbody>
              {detail.resources.map((r, i) => (
                <tr key={`${r.resourceId ?? 'res'}-${i}`} className="border-b border-gray-50 last:border-0">
                  <td className="max-w-[220px] px-3 py-2 text-gray-800">
                    <ResourceLink resourceId={r.resourceId} label={r.resourceName ?? r.resourceId}
                                  sub={r.cloudProvider} onOpen={onOpenResource} />
                  </td>
                  <td className="px-3 py-2 text-gray-600">{r.resourceType ?? '—'}</td>
                  <td className="px-3 py-2 text-gray-600">{r.region ?? '—'}</td>
                  <td className="px-3 py-2"><SeverityChip severity={r.severity} /></td>
                </tr>
              ))}
              {detail.resources.length === 0 && (
                <tr>
                  <td colSpan={4} className="px-3 py-6 text-center text-gray-400">
                    No affected resources.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      ),
    },
  ] : [];

  return (
    <DetailPanel
      title={title}
      subtitle={detail ? (
        <>
          <SeverityChip severity={detail.severity} />
          <span className="text-[10px] font-semibold uppercase tracking-wide text-gray-400">
            {detail.resourceCount} affected {detail.resourceCount === 1 ? 'resource' : 'resources'}
          </span>
        </>
      ) : undefined}
      sections={sections}
      isLoading={isLoading}
      onClose={onClose}
      emptyState={!isLoading && !detail ? (
        <div className="max-w-xs text-center">
          <Icon icon="mdi:clipboard-remove-outline" width={36} className="mx-auto text-gray-300" />
          <p className="mt-3 text-sm font-semibold text-gray-900">Issue details unavailable</p>
          <p className="mt-1 text-xs text-gray-500">
            No detail could be loaded for this check.
          </p>
        </div>
      ) : undefined}
    />
  );
};

export default IssueDrawer;
