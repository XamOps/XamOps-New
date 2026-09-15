import React, { useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import type {
  ComplianceFramework, IssueSummary, FrameworkControls, FrameworkControl,
} from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import { FrameworkLogo } from '../../utils/frameworkBranding';
import DetailPanel, { PanelBlock } from './DetailPanel';
import SecurityMarkup from './SecurityMarkup';

interface Props {
  framework: ComplianceFramework;
  /** Failing checks attributed to this framework, from the already-loaded issues list. */
  failingChecks: IssueSummary[];
  onClose: () => void;
  onOpenCheck?: (check: string) => void;
  /** Per-control breakdown, fetched on open. Omit and that section is hidden. */
  loadControls?: (frameworkId: string) => Promise<FrameworkControls | null>;
}

const fmtDate = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleString() : 'Never scanned';

/**
 * Upwind's framework `state`, in words. A framework with no results is not a framework that
 * failed, and these are the three reasons the API actually gives for the difference.
 */
const STATE_REASON: Record<string, string> = {
  no_cloud_account_connected:
    'No cloud account is connected for this framework, so nothing has been evaluated.',
  during_first_scan: 'The first scan for this framework is still running.',
  no_finding_resources:
    'This framework found no relevant resources in your environment, so it has nothing to score.',
};

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
const FrameworkDrawer: React.FC<Props> = ({
  framework: f, failingChecks, onClose, onOpenCheck, loadControls,
}) => {
  const [controls, setControls] = useState<FrameworkControls | null>(null);
  const [loadingControls, setLoadingControls] = useState(false);

  // Keyed on the framework id: without one there is nothing to query, which is the case for the
  // synthetic rows our v1 compliance list produces for unscanned frameworks.
  useEffect(() => {
    if (!loadControls || !f.frameworkId) return;
    let cancelled = false;
    setLoadingControls(true);
    loadControls(f.frameworkId)
      .then((c) => { if (!cancelled) setControls(c); })
      .catch((err) => {
        console.error('[XamSecure] framework controls failed:', err);
        if (!cancelled) setControls(null);
      })
      .finally(() => { if (!cancelled) setLoadingControls(false); });
    return () => { cancelled = true; };
  }, [loadControls, f.frameworkId]);

  // Upwind's score, with no locally computed fallback — PASS findings are no longer stored,
  // and a 0% stand-in for "Upwind never scored this" reads as total failure.
  const score = f.score ?? null;
  const tone = score === null ? 'text-gray-400'
    : score >= 80 ? 'text-emerald-600' : score >= 50 ? 'text-yellow-600' : 'text-red-600';
  const bar = score === null ? 'bg-gray-200'
    : score >= 80 ? 'bg-emerald-500' : score >= 50 ? 'bg-yellow-400' : 'bg-red-500';

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
      size={controls && controls.controls.length > 0 ? 'wide' : 'narrow'}
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
                <p className={`text-3xl font-extrabold leading-none ${tone}`}>
                  {score === null ? 'Not scored' : `${score}%`}
                </p>
              </div>
              <div className="mt-2 h-2 bg-gray-100 rounded-full overflow-hidden">
                <div className={`h-full ${bar}`} style={{ width: `${score ?? 0}%` }} />
              </div>
              <p className="mt-2 text-[11px] text-gray-500">
                {/* Failing counts are local and tenant-scoped; the score is Upwind's and
                    org-wide (cloud-account-id is ignored on that route). Per-control pass/fail
                    below is fetched live per framework, so the drill-down still has both
                    sides even though PASS findings are no longer synced. */}
                {f.failCount > 0
                  ? `${f.failCount.toLocaleString()} failing controls in this account. Score is organization-wide.`
                  // Upwind's own reason where it gives one, rather than implying we evaluated
                  // this framework and found nothing.
                  : STATE_REASON[f.state ?? ''] ?? 'No failing controls in this account.'}
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
      },
      ...(loadControls && f.frameworkId ? [{
        id: 'controls',
        label: 'Controls',
        icon: 'mdi:format-list-checks',
        count: controls?.controls.length,
        render: () => (
          <PanelBlock>
            {loadingControls ? (
              <p className="text-xs text-gray-400">Loading controls...</p>
            ) : !controls || controls.controls.length === 0 ? (
              <p className="text-xs text-gray-500">
                No per-control findings for this framework.
              </p>
            ) : (
              <>
                {/* Findings from accounts this client does not own are dropped server-side;
                    saying so keeps the totals explainable. */}
                {!!controls.skippedOtherAccounts && controls.skippedOtherAccounts > 0 && (
                  <p className="mb-3 text-[10px] text-gray-400">
                    {controls.skippedOtherAccounts.toLocaleString()} findings from other accounts
                    are excluded.
                  </p>
                )}
                <div className="space-y-2">
                  {controls.controls.map((c) => <ControlRow key={c.ruleId} control={c} />)}
                </div>
              </>
            )}
          </PanelBlock>
        ),
      }] : []),
      ]}
    />
  );
};

/**
 * One control. Leads with the compliant-asset ratio because that is the number Upwind shows and
 * the one that tells you whether a control is nearly clean or nowhere near.
 */
const ControlRow: React.FC<{ control: FrameworkControl }> = ({ control: c }) => {
  const [open, setOpen] = useState(false);
  const pct = c.compliancePercent;
  const bar = pct == null ? 'bg-gray-300'
    : pct >= 80 ? 'bg-emerald-500' : pct >= 50 ? 'bg-yellow-400' : 'bg-red-500';
  return (
    <div className="border border-gray-200 rounded-lg">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="w-full flex items-start gap-3 p-3 text-left hover:bg-gray-50
                   focus:outline-none focus-visible:ring-2 focus-visible:ring-inset
                   focus-visible:ring-gray-900 rounded-lg"
      >
        <Icon icon={open ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={15}
              className="mt-0.5 flex-shrink-0 text-gray-400" />
        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-2">
            <SeverityChip severity={c.severity} />
            <span className="text-xs font-medium text-gray-800">{c.title ?? c.ruleId}</span>
          </span>
          {/* Null percent is Upwind's "no relevant assets" state, not a zero score. */}
          {pct == null ? (
            <span className="mt-1.5 block text-[11px] text-gray-400">
              {/* Reached when nothing about this control was scored, rather than when it scored
                  zero. Saying "0% compliant" here would report a failure that was never found. */}
              No control results were scored in this account.
            </span>
          ) : (
            <span className="mt-1.5 flex items-center gap-2">
              <span className="h-1.5 w-32 rounded-full bg-gray-100 overflow-hidden">
                <span className={`block h-full ${bar}`} style={{ width: `${pct}%` }} />
              </span>
              <span className="text-[11px] font-semibold text-gray-700">{pct}%</span>
              <span className="text-[10px] text-gray-500">
                {c.passCount}/{c.assetCount} compliant
              </span>
            </span>
          )}
        </span>
        <span className="flex flex-shrink-0 flex-col items-end gap-1">
          {c.failCount > 0 && (
            <span className="rounded-full bg-red-50 px-2 py-0.5 text-[10px] font-bold text-red-700">
              {c.failCount} failing
            </span>
          )}
          {/* Zero on every response measured. Shown when present so an unrecognised status is
              visible rather than quietly excluded from both counts. */}
          {!!c.unscoredCount && c.unscoredCount > 0 && (
            <span className="rounded-full bg-gray-100 px-2 py-0.5 text-[10px] font-semibold text-gray-600">
              {c.unscoredCount} unscored
            </span>
          )}
        </span>
      </button>
      {open && (
        <div className="border-t border-gray-100 px-3 py-3">
          {c.remediation ? (
            <SecurityMarkup text={c.remediation} />
          ) : (
            <p className="text-[11px] text-gray-500">No remediation text for this control.</p>
          )}
          <p className="mt-2 text-[10px] text-gray-400 font-mono">{c.ruleId}</p>
        </div>
      )}
    </div>
  );
};

export default FrameworkDrawer;
