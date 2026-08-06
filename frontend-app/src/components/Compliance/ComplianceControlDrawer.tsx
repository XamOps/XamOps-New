import React, { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import type { ComplianceControl } from '../../types/compliance';
import FrameworkBadge from './FrameworkBadge';
import MdText from './MdText';
import {
  BTN_PRIMARY,
  BTN_SECONDARY,
  EYEBROW,
  deriveTitle,
  frameworkName,
  severityToken,
  statusToken,
} from './complianceTokens';

export interface OwnerDraft {
  ownerName: string;
  ownerEmail: string;
  team: string;
}

export interface ExceptionDraft {
  justification: string;
  compensatingControl: string;
  expiresAt: string;
}

interface Props {
  control: ComplianceControl | null;
  onClose: () => void;
  onSaveOwner: (checkId: string, draft: OwnerDraft) => Promise<void>;
  onRequestException: (checkId: string, draft: ExceptionDraft) => Promise<void>;
  onCreateTask: (checkId: string) => Promise<void>;
  busy: boolean;
  actionError: string | null;
}

type Tab = 'evidence' | 'fix' | 'frameworks' | 'govern';

const EVIDENCE_PREVIEW = 12;

/**
 * Control detail: the evidence, the fix, the framework mapping, and the human decisions.
 *
 * The Govern tab is the part that did not exist before. Previously a failing control could
 * only be looked at — there was nowhere to record who owns it, no way to formally accept it
 * with an expiry, and no way to track a fix. Those three things are what separates a
 * compliance record from a list of scanner output, so they live one click from the finding
 * rather than in a separate tool.
 */
const ComplianceControlDrawer: React.FC<Props> = ({
  control, onClose, onSaveOwner, onRequestException, onCreateTask, busy, actionError,
}) => {
  const [tab, setTab] = useState<Tab>('evidence');
  const [showAllEvidence, setShowAllEvidence] = useState(false);

  useEffect(() => {
    setTab('evidence');
    setShowAllEvidence(false);
  }, [control?.checkId]);

  useEffect(() => {
    if (!control) return undefined;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [control, onClose]);

  // The app scrolls a `.main-content-scroll` element, not <body>, so both need locking.
  useEffect(() => {
    const scroller = document.querySelector('.main-content-scroll') as HTMLElement | null;
    if (control) {
      document.body.style.overflow = 'hidden';
      if (scroller) scroller.style.overflow = 'hidden';
    }
    return () => {
      document.body.style.overflow = '';
      if (scroller) scroller.style.overflow = '';
    };
  }, [control]);

  return createPortal(
    <AnimatePresence>
      {control && (
        <React.Fragment key="compliance-control-drawer">
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2, ease: 'easeInOut' }}
            className="fixed inset-0 bg-black/30 z-50"
            onClick={onClose}
          />
          <motion.aside
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ duration: 0.2, ease: 'easeInOut' }}
            role="dialog"
            aria-label="Control detail"
            className="fixed right-0 top-0 h-full w-full max-w-2xl bg-white shadow-2xl z-50 flex flex-col"
          >
            <DrawerHeader control={control} onClose={onClose} />

            <nav className="flex items-center gap-1 px-4 border-b border-[#e5e7eb] bg-[#f9fafb]">
              {([
                { key: 'evidence' as Tab, label: `Evidence (${control.resources.length})` },
                { key: 'fix' as Tab, label: 'How to fix' },
                { key: 'frameworks' as Tab, label: `Frameworks (${control.frameworks.length})` },
                { key: 'govern' as Tab, label: 'Ownership & waivers' },
              ]).map((opt) => (
                <button
                  key={opt.key}
                  type="button"
                  onClick={() => setTab(opt.key)}
                  aria-pressed={tab === opt.key}
                  className={`py-2.5 px-2 text-[12px] font-medium border-b-2 -mb-px transition-colors ${
                    tab === opt.key
                      ? 'border-[rgba(0,0,0,0.88)] text-[rgba(0,0,0,0.88)]'
                      : 'border-transparent text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
                  }`}
                >
                  {opt.label}
                </button>
              ))}
            </nav>

            <div className="flex-1 overflow-y-auto px-4 py-4">
              {actionError && (
                <div className="mb-4 flex items-start gap-2.5 px-3 py-2.5 rounded-lg bg-red-50 border border-red-200">
                  <Icon
                    icon="mdi:alert-circle-outline"
                    width={15}
                    className="text-red-500 mt-[1px] shrink-0"
                  />
                  <p className="text-[12px] text-red-700 leading-relaxed">{actionError}</p>
                </div>
              )}

              {tab === 'evidence' && (
                <EvidenceTab
                  control={control}
                  showAll={showAllEvidence}
                  onToggleShowAll={() => setShowAllEvidence((v) => !v)}
                />
              )}
              {tab === 'fix' && <FixTab control={control} />}
              {tab === 'frameworks' && <FrameworksTab control={control} />}
              {tab === 'govern' && (
                <GovernTab
                  control={control}
                  busy={busy}
                  onSaveOwner={onSaveOwner}
                  onRequestException={onRequestException}
                  onCreateTask={onCreateTask}
                />
              )}
            </div>
          </motion.aside>
        </React.Fragment>
      )}
    </AnimatePresence>,
    document.body,
  );
};

const DrawerHeader: React.FC<{ control: ComplianceControl; onClose: () => void }> = ({
  control, onClose,
}) => {
  const status = statusToken(control.status);
  const severity = severityToken(control.severity);

  return (
    <header className="flex items-start justify-between gap-4 px-4 pt-4 pb-3 border-b border-[#e5e7eb]">
      <div className="min-w-0">
        <div className="flex items-center gap-2 flex-wrap mb-2">
          <span
            className={`inline-flex items-center gap-1.5 px-2 py-1 rounded-full text-[9.5px] font-semibold uppercase tracking-wider ${status.pill}`}
          >
            <Icon icon={status.icon} width={11} />
            {status.label}
          </span>
          <span className="inline-flex items-center gap-1">
            <Icon icon={severity.icon} width={12} className={severity.text} />
            <span className={`text-[10.5px] uppercase tracking-wide ${severity.text}`}>
              {control.severity}
            </span>
          </span>
          <code className="px-1.5 py-0.5 rounded-md bg-[rgba(0,0,0,0.05)] text-[10px] font-mono text-[rgba(0,0,0,0.55)]">
            {control.checkId}
          </code>
        </div>
        <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] leading-snug">
          {deriveTitle(control.title, control.checkId)}
        </h2>
        <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] mt-1.5">
          <span
            className={control.failedResources > 0 ? 'font-semibold text-red-600' : 'font-semibold'}
          >
            {control.failedResources}
          </span>
          {' failing'}
          <span className="text-[rgba(0,0,0,0.3)]"> · </span>
          <span className="font-semibold text-emerald-600">{control.passedResources}</span>
          {' passing'}
          {control.service && <span className="text-[rgba(0,0,0,0.3)]"> · {control.service}</span>}
          {control.failingForScans > 1 && (
            <span className="text-[rgba(0,0,0,0.3)]">
              {' '}· failing for {control.failingForScans} scans
            </span>
          )}
        </p>
      </div>
      <button
        type="button"
        onClick={onClose}
        aria-label="Close"
        className="w-7 h-7 flex items-center justify-center rounded-lg hover:bg-[rgba(0,0,0,0.05)] transition-colors shrink-0"
      >
        <Icon icon="mdi:close" width={17} className="text-[rgba(0,0,0,0.4)]" />
      </button>
    </header>
  );
};

const EvidenceTab: React.FC<{
  control: ComplianceControl;
  showAll: boolean;
  onToggleShowAll: () => void;
}> = ({ control, showAll, onToggleShowAll }) => {
  if (control.resources.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center px-6">
        <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] rounded-xl flex items-center justify-center mb-3">
          <Icon
            icon={control.status === 'PASS' ? 'mdi:check-circle-outline' : 'mdi:database-off-outline'}
            width={26}
            className="text-[rgba(0,0,0,0.35)]"
          />
        </div>
        <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.78)] mb-1">
          {control.status === 'PASS' ? 'Nothing to show' : 'No resource evidence recorded'}
        </p>
        <p className="text-[12px] text-[rgba(0,0,0,0.45)] max-w-[46ch] leading-relaxed">
          {control.status === 'PASS'
            ? `All ${control.passedResources} in-scope resources passed. Passing resources are `
              + 'counted rather than listed individually.'
            : 'The scan recorded no resource-level detail for this control.'}
        </p>
      </div>
    );
  }

  const shown = showAll ? control.resources : control.resources.slice(0, EVIDENCE_PREVIEW);
  const hidden = control.resources.length - EVIDENCE_PREVIEW;

  return (
    <section>
      <div className="flex items-center justify-between gap-3 mb-2.5">
        <span className={EYEBROW}>Affected resources</span>
        <span className="text-[10.5px] text-[rgba(0,0,0,0.35)] tabular-nums">
          {control.resources.length} recorded
        </span>
      </div>

      <ul className="space-y-1.5">
        {shown.map((r, i) => {
          const token = statusToken(r.status);
          return (
            <li
              key={`${r.uid ?? 'resource'}-${i}`}
              className="border border-[#e5e7eb] rounded-lg px-3 py-2.5"
            >
              <div className="flex items-start gap-2.5">
                <span
                  className={`w-1.5 h-1.5 rounded-full mt-1.5 shrink-0 ${token.accent}`}
                  aria-hidden="true"
                />
                <div className="min-w-0 flex-1">
                  <p className="text-[12px] font-medium text-[rgba(0,0,0,0.88)] break-all leading-snug">
                    {r.name && r.name !== r.uid ? r.name : r.uid ?? 'unnamed resource'}
                  </p>
                  {r.name && r.uid && r.name !== r.uid && (
                    <p className="text-[10.5px] font-mono text-[rgba(0,0,0,0.4)] break-all mt-0.5">
                      {r.uid}
                    </p>
                  )}
                  {r.detail && (
                    <p className="text-[11.5px] text-[rgba(0,0,0,0.6)] leading-relaxed mt-1.5">
                      <MdText text={r.detail} />
                    </p>
                  )}
                  <div className="flex items-center gap-2 mt-1.5">
                    {r.region && (
                      <span className="text-[10px] font-mono text-[rgba(0,0,0,0.35)]">
                        {r.region}
                      </span>
                    )}
                    {r.type && (
                      <span className="text-[10px] text-[rgba(0,0,0,0.35)] truncate">{r.type}</span>
                    )}
                  </div>
                </div>
              </div>
            </li>
          );
        })}
      </ul>

      {hidden > 0 && (
        <button
          type="button"
          onClick={onToggleShowAll}
          className="mt-2.5 text-[11.5px] font-medium text-[rgba(0,0,0,0.5)] underline underline-offset-2 hover:text-[rgba(0,0,0,0.88)] transition-colors"
        >
          {showAll ? 'Show fewer' : `Show ${hidden} more`}
        </button>
      )}

      {/* Truncation is disclosed rather than silent: a capped list that looks complete
          would understate the blast radius. */}
      {control.failedResources > control.resources.length && (
        <p className="mt-3 text-[11px] text-[rgba(0,0,0,0.4)] leading-relaxed">
          {control.failedResources.toLocaleString()} resources failed in total. Evidence is
          capped at 200 per control, so {(control.failedResources - control.resources.length)
            .toLocaleString()} are counted but not listed.
        </p>
      )}
    </section>
  );
};

const FixTab: React.FC<{ control: ComplianceControl }> = ({ control }) => (
  <section className="space-y-4">
    {control.remediation ? (
      <div>
        <span className={EYEBROW}>Remediation</span>
        <p className="text-[12.5px] text-[rgba(0,0,0,0.7)] leading-relaxed mt-2">
          <MdText text={control.remediation} />
        </p>
      </div>
    ) : (
      <p className="text-[12.5px] text-[rgba(0,0,0,0.45)] leading-relaxed">
        The scanner did not supply remediation guidance for this check.
      </p>
    )}

    {control.remediationRefs.length > 0 && (
      <div>
        <span className={EYEBROW}>References</span>
        <ul className="mt-2 space-y-1">
          {control.remediationRefs
            .filter((ref) => ref.startsWith('http'))
            .slice(0, 8)
            .map((ref) => (
              <li key={ref}>
                <a
                  href={ref}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="flex items-center gap-1.5 text-[11.5px] text-[rgba(0,0,0,0.6)] hover:text-[rgba(0,0,0,0.88)] underline underline-offset-2 transition-colors"
                >
                  <Icon icon="mdi:open-in-new" width={12} className="shrink-0" />
                  <span className="truncate">{ref}</span>
                </a>
              </li>
            ))}
        </ul>
      </div>
    )}

    {control.status === 'MANUAL' && (
      <div className="flex items-start gap-2.5 px-3 py-2.5 rounded-lg bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.1)]">
        <Icon
          icon="mdi:help-circle-outline"
          width={15}
          className="text-[rgba(0,0,0,0.45)] mt-[1px] shrink-0"
        />
        <p className="text-[12px] text-[rgba(0,0,0,0.6)] leading-relaxed">
          The scanner could not decide this check automatically, so it is neither passing
          nor failing. Someone has to verify it by hand and record the outcome.
        </p>
      </div>
    )}
  </section>
);

const FrameworksTab: React.FC<{ control: ComplianceControl }> = ({ control }) => {
  if (control.frameworks.length === 0) {
    return (
      <p className="text-[12.5px] text-[rgba(0,0,0,0.45)] leading-relaxed">
        This check is not mapped to any of the frameworks in scope for the last scan.
      </p>
    );
  }

  return (
    <section>
      <p className="text-[12px] text-[rgba(0,0,0,0.5)] leading-relaxed mb-3">
        Fixing this one check moves {control.frameworks.length} framework
        {control.frameworks.length === 1 ? '' : 's'} at once. Each clause below is satisfied
        by the same change.
      </p>
      <ul className="space-y-1.5">
        {control.frameworks.map((f) => (
          <li
            key={f.framework}
            className="flex items-start gap-2.5 border border-[#e5e7eb] rounded-lg px-3 py-2.5"
          >
            <FrameworkBadge framework={f.framework} size={26} shape="rounded" />
            <div className="min-w-0 flex-1">
              <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] truncate">
                {frameworkName(f.framework)}
              </p>
              <div className="flex flex-wrap gap-1 mt-1.5">
                {f.controls.map((ref) => (
                  <span
                    key={ref}
                    className="px-1.5 py-0.5 rounded-md bg-[rgba(0,0,0,0.04)] text-[10.5px] font-mono text-[rgba(0,0,0,0.6)]"
                  >
                    {ref}
                  </span>
                ))}
              </div>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
};

const GovernTab: React.FC<{
  control: ComplianceControl;
  busy: boolean;
  onSaveOwner: (checkId: string, draft: OwnerDraft) => Promise<void>;
  onRequestException: (checkId: string, draft: ExceptionDraft) => Promise<void>;
  onCreateTask: (checkId: string) => Promise<void>;
}> = ({ control, busy, onSaveOwner, onRequestException, onCreateTask }) => {
  const [owner, setOwner] = useState<OwnerDraft>({
    ownerName: control.owner?.name ?? '',
    ownerEmail: control.owner?.email ?? '',
    team: control.owner?.team ?? '',
  });
  const [showExceptionForm, setShowExceptionForm] = useState(false);
  const [draft, setDraft] = useState<ExceptionDraft>({
    justification: '',
    compensatingControl: '',
    expiresAt: defaultExpiry(),
  });

  useEffect(() => {
    setOwner({
      ownerName: control.owner?.name ?? '',
      ownerEmail: control.owner?.email ?? '',
      team: control.owner?.team ?? '',
    });
    setShowExceptionForm(false);
    setDraft({ justification: '', compensatingControl: '', expiresAt: defaultExpiry() });
  }, [control.checkId, control.owner]);

  const justificationLength = draft.justification.trim().length;

  return (
    <div className="space-y-5">
      {/* Ownership */}
      <section>
        <span className={EYEBROW}>Owner</span>
        <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] leading-relaxed mt-1 mb-2.5">
          Ownership is attached to the control, not to this scan, so it survives re-scanning.
        </p>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
          <Field
            label="Name"
            value={owner.ownerName}
            onChange={(v) => setOwner((p) => ({ ...p, ownerName: v }))}
            placeholder="Priya Sharma"
          />
          <Field
            label="Email"
            value={owner.ownerEmail}
            onChange={(v) => setOwner((p) => ({ ...p, ownerEmail: v }))}
            placeholder="priya@example.com"
            type="email"
          />
          <Field
            label="Team"
            value={owner.team}
            onChange={(v) => setOwner((p) => ({ ...p, team: v }))}
            placeholder="Platform"
          />
        </div>
        <div className="flex items-center gap-2 mt-2.5">
          <button
            type="button"
            disabled={busy || (!owner.ownerName.trim() && !owner.ownerEmail.trim() && !owner.team.trim())}
            onClick={() => onSaveOwner(control.checkId, owner)}
            className={BTN_PRIMARY}
          >
            <Icon icon="mdi:account-check-outline" width={14} />
            {control.owner ? 'Update owner' : 'Assign owner'}
          </button>
          {!control.task && (
            <button
              type="button"
              disabled={busy}
              onClick={() => onCreateTask(control.checkId)}
              className={BTN_SECONDARY}
            >
              <Icon icon="mdi:wrench-outline" width={14} />
              Track a fix
            </button>
          )}
        </div>
      </section>

      {/* Remediation task */}
      {control.task && (
        <section>
          <span className={EYEBROW}>Remediation</span>
          <div className="mt-2 border border-[#e5e7eb] rounded-lg px-3 py-2.5">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="px-1.5 py-0.5 rounded-md bg-[rgba(0,0,0,0.05)] text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.6)]">
                {control.task.status.replace('_', ' ')}
              </span>
              {control.task.overdue && (
                <span className="px-1.5 py-0.5 rounded-md bg-red-50 border border-red-200 text-[10px] font-semibold uppercase tracking-wider text-red-700">
                  overdue
                </span>
              )}
              {control.task.ticketRef && (
                <span className="text-[11px] font-mono text-[rgba(0,0,0,0.5)]">
                  {control.task.ticketRef}
                </span>
              )}
            </div>
            <p className="text-[11.5px] text-[rgba(0,0,0,0.5)] mt-1.5 leading-relaxed">
              {control.task.assigneeName || control.task.assigneeEmail
                ? `Assigned to ${control.task.assigneeName ?? control.task.assigneeEmail}. `
                : 'Unassigned. '}
              {control.task.dueAt
                ? `Due ${new Date(control.task.dueAt).toLocaleDateString()}.`
                : 'No due date set.'}
            </p>
            {control.task.status === 'RESOLVED' && (
              <p className="text-[11px] text-amber-700 mt-1.5 leading-relaxed">
                Marked resolved but not yet verified. The next scan that observes this
                control passing will mark it verified automatically.
              </p>
            )}
          </div>
        </section>
      )}

      {/* Exception */}
      <section>
        <span className={EYEBROW}>Exception</span>

        {control.exception ? (
          <div className="mt-2 border border-sky-200 bg-sky-50/60 rounded-lg px-3 py-3">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="px-1.5 py-0.5 rounded-md bg-sky-500/85 text-white text-[10px] font-semibold uppercase tracking-wider">
                {control.exception.status}
              </span>
              {control.exception.expiresAt && (
                <span
                  className={`text-[11px] font-medium ${
                    control.exception.daysUntilExpiry <= 30 ? 'text-amber-700' : 'text-sky-800'
                  }`}
                >
                  expires {new Date(control.exception.expiresAt).toLocaleDateString()}
                  {' '}({control.exception.daysUntilExpiry} days)
                </span>
              )}
            </div>
            <p className="text-[12px] text-[rgba(0,0,0,0.7)] leading-relaxed mt-2">
              {control.exception.justification}
            </p>
            {control.exception.compensatingControl && (
              <p className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed mt-1.5">
                <span className="font-semibold">Compensating control: </span>
                {control.exception.compensatingControl}
              </p>
            )}
            <p className="text-[10.5px] text-[rgba(0,0,0,0.4)] mt-2">
              Requested by {control.exception.requestedBy ?? 'unknown'}
              {control.exception.approvedBy ? `, approved by ${control.exception.approvedBy}` : ''}
            </p>
          </div>
        ) : !showExceptionForm ? (
          <>
            <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] leading-relaxed mt-1 mb-2.5">
              Record a time-boxed acceptance of this risk. A waived control is excluded from
              the score and never shown as passing.
            </p>
            <button
              type="button"
              onClick={() => setShowExceptionForm(true)}
              disabled={control.status === 'PASS'}
              title={control.status === 'PASS'
                ? 'This control is passing — there is nothing to waive'
                : undefined}
              className={BTN_SECONDARY}
            >
              <Icon icon="mdi:file-document-edit-outline" width={14} />
              Request an exception
            </button>
          </>
        ) : (
          <div className="mt-2 space-y-2.5">
            <div>
              <label
                htmlFor="exception-justification"
                className="block text-[10.5px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)] mb-1"
              >
                Justification
              </label>
              <textarea
                id="exception-justification"
                rows={3}
                value={draft.justification}
                onChange={(e) => setDraft((p) => ({ ...p, justification: e.target.value }))}
                placeholder="Why is this risk acceptable, and what is the plan to remove it?"
                className="w-full px-3 py-2 rounded-lg border border-[#e5e7eb] bg-white text-[12px] text-[rgba(0,0,0,0.78)] placeholder:text-[rgba(0,0,0,0.3)] focus:outline-none focus:border-[rgba(0,0,0,0.4)] resize-y"
              />
              <p
                className={`text-[10.5px] mt-1 ${
                  justificationLength >= 20 ? 'text-[rgba(0,0,0,0.35)]' : 'text-amber-600'
                }`}
              >
                {justificationLength}/20 characters minimum — an auditor will read this.
              </p>
            </div>

            <div>
              <label
                htmlFor="exception-compensating"
                className="block text-[10.5px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)] mb-1"
              >
                Compensating control (optional)
              </label>
              <input
                id="exception-compensating"
                type="text"
                value={draft.compensatingControl}
                onChange={(e) => setDraft((p) => ({ ...p, compensatingControl: e.target.value }))}
                placeholder="What reduces the risk in the meantime?"
                className="w-full h-8 px-3 rounded-lg border border-[#e5e7eb] bg-white text-[12px] text-[rgba(0,0,0,0.78)] placeholder:text-[rgba(0,0,0,0.3)] focus:outline-none focus:border-[rgba(0,0,0,0.4)]"
              />
            </div>

            <div>
              <label
                htmlFor="exception-expiry"
                className="block text-[10.5px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)] mb-1"
              >
                Expires on
              </label>
              <input
                id="exception-expiry"
                type="date"
                value={draft.expiresAt}
                onChange={(e) => setDraft((p) => ({ ...p, expiresAt: e.target.value }))}
                className="h-8 px-3 rounded-lg border border-[#e5e7eb] bg-white text-[12px] text-[rgba(0,0,0,0.78)] focus:outline-none focus:border-[rgba(0,0,0,0.4)]"
              />
              <p className="text-[10.5px] text-[rgba(0,0,0,0.35)] mt-1">
                Required. An exception with no expiry is an undocumented risk acceptance.
              </p>
            </div>

            <div className="flex items-center gap-2">
              <button
                type="button"
                disabled={busy || justificationLength < 20 || !draft.expiresAt}
                onClick={() => onRequestException(control.checkId, draft)}
                className={BTN_PRIMARY}
              >
                <Icon icon="mdi:send-outline" width={14} />
                Submit for approval
              </button>
              <button
                type="button"
                onClick={() => setShowExceptionForm(false)}
                className={BTN_SECONDARY}
              >
                Cancel
              </button>
            </div>
            <p className="text-[10.5px] text-[rgba(0,0,0,0.35)] leading-relaxed">
              Submitted as pending. Someone other than you has to approve it before it
              suppresses the control.
            </p>
          </div>
        )}
      </section>
    </div>
  );
};

const Field: React.FC<{
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  type?: string;
}> = ({ label, value, onChange, placeholder, type = 'text' }) => {
  const id = `field-${label.toLowerCase()}`;
  return (
    <div>
      <label
        htmlFor={id}
        className="block text-[10.5px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)] mb-1"
      >
        {label}
      </label>
      <input
        id={id}
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full h-8 px-3 rounded-lg border border-[#e5e7eb] bg-white text-[12px] text-[rgba(0,0,0,0.78)] placeholder:text-[rgba(0,0,0,0.3)] focus:outline-none focus:border-[rgba(0,0,0,0.4)]"
      />
    </div>
  );
};

/** 90 days out — long enough to be useful, short enough to force a real review. */
function defaultExpiry(): string {
  const d = new Date();
  d.setDate(d.getDate() + 90);
  return d.toISOString().slice(0, 10);
}

export default ComplianceControlDrawer;
