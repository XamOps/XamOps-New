import React from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@iconify/react';
import type { IssueDetail } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import SecurityMarkup from './SecurityMarkup';
import ResourceLink from './ResourceLink';
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
 * Right-side detail panel for a single issue (posture check), mirroring Upwind's
 * layout: header with severity, finding details, remediation, framework badges,
 * first-seen / last-evaluation, and the affected-resources table. Read-only.
 */
const IssueDrawer: React.FC<Props> = ({ title, detail, isLoading, onClose, onOpenResource }) => {
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
              <SeverityChip severity={detail?.severity ?? null} />
              {detail && (
                <span className="text-[10px] font-semibold text-gray-400 uppercase tracking-wide">
                  {detail.resourceCount} affected {detail.resourceCount === 1 ? 'resource' : 'resources'}
                </span>
              )}
            </div>
            <h3 className="text-sm font-bold text-gray-900 leading-snug">{title}</h3>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100 flex-shrink-0" aria-label="Close">
            <Icon icon="mdi:close" width={18} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto overflow-x-hidden">
          {isLoading && (
            <div className="px-6 py-12 text-center text-xs text-gray-400">Loading issue details...</div>
          )}

          {!isLoading && detail && (
            <div className="px-6 py-5 space-y-6">
              {/* Meta row */}
              <div className="grid grid-cols-2 gap-4">
                <Meta label="First seen" value={fmt(detail.firstSeen)} />
                <Meta label="Last evaluation" value={fmt(detail.lastEvaluation)} />
              </div>

              {/* Frameworks */}
              {detail.frameworks.length > 0 && (
                <Section title="Frameworks">
                  <div className="flex flex-wrap gap-1.5">
                    {detail.frameworks.map((f) => (
                      hasFrameworkLogo(f) ? (
                        <span key={f}
                              className="inline-flex items-center gap-1.5 pl-1 pr-2 py-0.5 rounded-full text-[10px] font-semibold bg-gray-50 text-gray-700 border border-gray-200">
                          <FrameworkLogo framework={f} size={16} />
                          {f}
                        </span>
                      ) : (
                        <span key={f}
                              className="inline-block px-2 py-0.5 rounded-md text-[10px] font-semibold bg-gray-100 text-gray-600 border border-gray-200">
                          {f}
                        </span>
                      )
                    ))}
                  </div>
                </Section>
              )}

              {/* Finding details */}
              <Section title="Finding details">
                <SecurityMarkup text={detail.description} fallback="No description provided." />
              </Section>

              {/* Remediation */}
              <Section title="Remediation">
                <SecurityMarkup text={detail.remediation} fallback="No remediation guidance provided." />
              </Section>

              {/* Affected resources */}
              <Section title={`Affected resources (${detail.resources.length})`}>
                <div className="border border-gray-200 rounded-lg overflow-hidden">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100 bg-gray-50">
                        <th className="px-3 py-2">Resource</th>
                        <th className="px-3 py-2">Type</th>
                        <th className="px-3 py-2">Region</th>
                        <th className="px-3 py-2">Severity</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.resources.map((r, i) => (
                        <tr key={`${r.resourceId ?? 'res'}-${i}`} className="border-b border-gray-50 last:border-0">
                          <td className="px-3 py-2 text-gray-800 max-w-[220px]">
                            {onOpenResource ? (
                              <ResourceLink resourceId={r.resourceId} label={r.resourceName ?? r.resourceId}
                                            sub={r.cloudProvider} onOpen={onOpenResource} />
                            ) : (
                              <>
                                <span className="font-medium truncate block">
                                  {r.resourceName ?? r.resourceId ?? '—'}
                                </span>
                                {r.cloudProvider && (
                                  <span className="block text-[10px] text-gray-400">{r.cloudProvider}</span>
                                )}
                              </>
                            )}
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
              </Section>
            </div>
          )}

          {!isLoading && !detail && (
            <div className="px-6 py-12 text-center text-xs text-gray-400">
              Details unavailable for this issue.
            </div>
          )}
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

export default IssueDrawer;
