import React from 'react';
import type { ComplianceFramework, IssueSummary } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import { FrameworkLogo } from '../../utils/frameworkBranding';
import DetailPanel, { PanelBlock } from './DetailPanel';

interface Props {
  framework: ComplianceFramework;
  /** Failing checks attributed to this framework, from the already-loaded issues list. */
  failingChecks: IssueSummary[];
  onClose: () => void;
  onOpenCheck?: (check: string) => void;
}

const fmtDate = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleString() : 'Never scanned';

/**
 * Framework detail. The card answers "what is the score"; this answers the question that
 * follows — which controls are failing, and where to go next.
 *
 * Single-pane on purpose: score, last evaluation and failing controls read as one story, so
 * splitting them across nav items would cost a click per item and buy nothing. It still uses
 * the shared shell for chrome, Escape/focus handling and scroll lock.
 *
 * Failing checks come from the issues already in memory, so opening this costs no request.
 */
const FrameworkDrawer: React.FC<Props> = ({ framework: f, failingChecks, onClose, onOpenCheck }) => {
  const total = f.failCount + f.passCount;
  const passPct = total === 0 ? 0 : Math.round((f.passCount / total) * 100);
  const score = f.score ?? passPct;
  const tone = score >= 80 ? 'text-emerald-600' : score >= 50 ? 'text-yellow-600' : 'text-red-600';
  const bar = score >= 80 ? 'bg-emerald-500' : score >= 50 ? 'bg-yellow-400' : 'bg-red-500';

  const title = (
    <span className="flex items-center gap-2.5">
      <FrameworkLogo framework={f.framework} size={28} />
      <span className="break-words">{f.framework}</span>
    </span>
  );

  const subtitle = (
    <>
      {f.version && <span className="text-[10px] font-semibold text-gray-500">v{f.version}</span>}
      {f.status && (
        <span className={`text-[9px] font-bold uppercase tracking-wide px-1.5 py-0.5 rounded-full ${
          f.status.toUpperCase() === 'ENABLED' ? 'bg-gray-900 text-white' : 'bg-gray-100 text-gray-500'}`}>
          {f.status}
        </span>
      )}
      <span className="text-[10px] text-gray-500">Evaluated {fmtDate(f.lastScanTime)}</span>
    </>
  );

  return (
    <DetailPanel
      title={title}
      subtitle={subtitle}
      onClose={onClose}
      size="narrow"
      sections={[{
        id: 'overview',
        label: f.framework,
        render: () => (
          <>
            <PanelBlock>
              <div className="flex items-end justify-between gap-3">
                <p className="text-[10px] font-bold uppercase tracking-wide text-gray-400">
                  Compliance score
                </p>
                <p className={`text-3xl font-extrabold leading-none ${tone}`}>{score}%</p>
              </div>
              <div className="mt-2 h-2 bg-gray-100 rounded-full overflow-hidden">
                <div className={`h-full ${bar}`} style={{ width: `${score}%` }} />
              </div>
              <p className="mt-2 text-[11px] text-gray-500">
                {/* Both counts are real since we started walking status=PASS as well as FAIL. */}
                {total > 0
                  ? `${f.failCount.toLocaleString()} failing · ${f.passCount.toLocaleString()} passing controls in this account.`
                  : 'No evaluated controls in this account.'}
              </p>
            </PanelBlock>

            <PanelBlock title={`Failing controls (${failingChecks.length})`}>
              {failingChecks.length > 0 ? (
                <div className="border border-gray-200 rounded-lg overflow-hidden">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100 bg-gray-50">
                        <th className="px-3 py-2">Control</th>
                        <th className="px-3 py-2">Resources</th>
                        <th className="px-3 py-2">Severity</th>
                      </tr>
                    </thead>
                    <tbody>
                      {failingChecks.map((c) => (
                        <tr key={c.check}
                            className={`border-b border-gray-50 last:border-0 ${
                              onOpenCheck ? 'hover:bg-gray-50 cursor-pointer' : ''}`}
                            onClick={onOpenCheck ? () => onOpenCheck(c.check) : undefined}>
                          <td className="px-3 py-2 text-gray-800 font-medium">{c.title}</td>
                          <td className="px-3 py-2 font-semibold text-gray-700">{c.resourceCount}</td>
                          <td className="px-3 py-2"><SeverityChip severity={c.severity} /></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className="text-xs text-gray-400">
                  {f.failCount > 0
                    ? 'Failing controls for this framework are not in the currently loaded issues.'
                    : 'Nothing failing against this framework.'}
                </p>
              )}
            </PanelBlock>
          </>
        ),
      }]}
    />
  );
};

export default FrameworkDrawer;
