/**
 * Trial Management section for the BillOps admin user manager.
 *
 * Tenant-centric by necessity: BillOpsUser carries clientName but no clientId, so joining
 * trial state into the user tree would be a name match, and every action here changes the
 * whole tenant anyway. Hence its own card, keyed by clientId.
 *
 * The verdict column is the derived one (src/utils/trialVerdict.ts), not the raw trial_status,
 * because the list endpoint never runs TrialService.effectiveStatus.
 */

import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import { AlertDialog, DialogPanel, DialogRoot } from '../ui';
import NotificationModal from '../Shared/NotificationModal';
import { useBillOpsAdminTrials } from '../../hooks/useBillOpsAdminTrials';
import { deriveTrialVerdict } from '../../utils/trialVerdict';
import type { TrialAdminRow, TrialEnforcementState } from '../../types/billopsAdminTrials';

const DAY_PRESETS = [14, 30, 90, 180];
const MIN_DAYS = 1;
const MAX_DAYS = 3650;

const VERDICT_META: Record<TrialEnforcementState, { icon: string; className: string }> = {
  EXPIRED: { icon: 'mdi:alert-circle-outline', className: 'bg-rose-50 border-rose-200 text-rose-700' },
  ACTIVE:  { icon: 'mdi:timer-sand',           className: 'bg-amber-50 border-amber-200 text-amber-700' },
  NONE:    { icon: 'mdi:shield-check-outline', className: 'bg-emerald-50 border-emerald-200 text-emerald-700' },
};

const formatDate = (iso: string | null): string => {
  if (!iso) return '—';
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return '—';
  return parsed.toLocaleDateString('en-US', { day: 'numeric', month: 'short', year: 'numeric' });
};

const Pill: React.FC<{ children: React.ReactNode; className?: string }> = ({ children, className }) => (
  <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full border text-[10px] font-bold uppercase tracking-wider ${className ?? 'bg-[rgba(0,0,0,0.04)] border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.6)]'}`}>
    {children}
  </span>
);

const TrialManagementSection: React.FC = () => {
  const {
    rows, loading, error, mutatingClientId, mutationError, lastResult,
    refresh, extendTrial, convertToPaid, clearMutationState,
  } = useBillOpsAdminTrials();

  const [extendTarget, setExtendTarget] = useState<TrialAdminRow | null>(null);
  const [convertTarget, setConvertTarget] = useState<TrialAdminRow | null>(null);
  const [selectedDays, setSelectedDays] = useState<number>(30);
  const [customDays, setCustomDays] = useState('');
  const [formError, setFormError] = useState<string | null>(null);

  const openExtend = (row: TrialAdminRow) => {
    clearMutationState();
    setFormError(null);
    setSelectedDays(30);
    setCustomDays('');
    setExtendTarget(row);
  };

  const openConvert = (row: TrialAdminRow) => {
    clearMutationState();
    setConvertTarget(row);
  };

  /**
   * Every path that closes the extend dialog without submitting — Cancel, the × button, ESC,
   * a backdrop click. It drops mutationError too: without that, dismissing a failed extend
   * would immediately re-raise the message the operator just dismissed as a full-screen modal,
   * because the error modal is gated on the dialog being closed.
   */
  const closeExtend = () => {
    clearMutationState();
    setFormError(null);
    setExtendTarget(null);
  };

  const resolveDays = (): number | null => {
    if (!customDays.trim()) return selectedDays;
    const parsed = Number(customDays.trim());
    if (!Number.isInteger(parsed) || parsed < MIN_DAYS || parsed > MAX_DAYS) return null;
    return parsed;
  };

  const submitExtend = async () => {
    if (!extendTarget) return;
    const days = resolveDays();
    if (days === null) {
      setFormError(`Enter a whole number of days between ${MIN_DAYS} and ${MAX_DAYS}.`);
      return;
    }
    const target = extendTarget;
    setFormError(null);
    const ok = await extendTrial(target.clientId, days);
    if (ok) setExtendTarget(null);
  };

  const submitConvert = async () => {
    if (!convertTarget) return;
    const target = convertTarget;
    // The confirm control stays mounted for the whole round trip, so without this a second
    // click sends a second POST. The writes are idempotent, but one click means one mutation.
    if (mutatingClientId === target.clientId) return;
    const ok = await convertToPaid(target.clientId);
    if (ok) setConvertTarget(null);
  };

  const convertInFlight = !!convertTarget && mutatingClientId === convertTarget.clientId;

  const successMessage = (): string => {
    if (!lastResult) return '';
    if (lastResult.kind === 'extend') {
      return `Trial extended. The tenant now runs until ${formatDate(lastResult.trialEndAt)} (${lastResult.daysRemaining} day${lastResult.daysRemaining === 1 ? '' : 's'} remaining).`;
    }
    return `Tenant ${lastResult.clientId} is now PAID and no longer trial-gated. No subscription or invoice record was created.`;
  };

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm overflow-hidden">
      {/* Header */}
      <div className="flex flex-wrap items-center gap-3 px-4 py-3 border-b border-[rgba(0,0,0,0.06)]">
        <div className="w-9 h-9 rounded-xl bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.06)] flex items-center justify-center flex-shrink-0">
          <Icon icon="mdi:calendar-clock" width={16} color="rgba(0,0,0,0.5)" />
        </div>
        <div className="min-w-[200px]">
          <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Trial Management</h2>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] leading-tight">
            Extending or converting applies to the whole tenant — every user under it.
          </p>
        </div>
        <button
          type="button"
          onClick={() => { void refresh(); }}
          disabled={loading}
          aria-label="Refresh trial tenants"
          className="ml-auto flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-widest border border-[rgba(0,0,0,0.08)] bg-white text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-50 transition-colors"
        >
          <Icon icon="mdi:refresh" width={11} className={loading ? 'animate-spin' : undefined} />
          Refresh
        </button>
      </div>

      {/* Inline error — the section degrades, the rest of the page keeps working. */}
      {error && (
        <div className="flex items-center gap-3 m-4 p-3 bg-rose-50 border border-rose-100 rounded-xl text-xs text-rose-600">
          <Icon icon="mdi:alert-circle-outline" width={15} className="flex-shrink-0" />
          {error}
        </div>
      )}

      {loading ? (
        <div className="flex flex-col items-center justify-center py-12 gap-3">
          <Icon icon="mdi:loading" width={24} color="rgba(0,0,0,0.25)" className="animate-spin" />
          <p className="text-xs text-[rgba(0,0,0,0.4)]">Loading trial tenants…</p>
        </div>
      ) : rows.length === 0 && !error ? (
        <div className="flex flex-col items-center justify-center py-12 gap-2">
          <Icon icon="mdi:information-outline" width={24} color="rgba(0,0,0,0.2)" />
          <p className="text-sm font-semibold text-[rgba(0,0,0,0.5)]">No Trial Tenants</p>
          <p className="text-xs text-[rgba(0,0,0,0.35)]">Nothing is on a trial, and nothing has been converted from one.</p>
        </div>
      ) : rows.length > 0 ? (
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="border-b border-[rgba(0,0,0,0.06)]">
                {['Tenant', 'Plan', 'Trial status', 'Trial ends', 'Verdict', 'Actions'].map(h => (
                  <th key={h} className="px-4 py-2.5 text-[10px] font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wider whitespace-nowrap">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map(row => {
                const verdict = deriveTrialVerdict(row);
                const meta = VERDICT_META[verdict.state];
                const isMutating = mutatingClientId === row.clientId;
                return (
                  <tr
                    key={row.clientId}
                    aria-busy={isMutating}
                    className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)] transition-colors"
                  >
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-bold text-[rgba(0,0,0,0.88)]">{row.companyName ?? 'Unnamed tenant'}</span>
                        <span className="text-[10px] font-semibold text-[rgba(0,0,0,0.35)]">#{row.clientId}</span>
                      </div>
                      <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{row.email ?? 'No billing email'}</p>
                    </td>
                    <td className="px-4 py-3">
                      <Pill>{row.planType ?? 'UNSET'}</Pill>
                    </td>
                    <td className="px-4 py-3 text-xs text-[rgba(0,0,0,0.6)]">{row.trialStatus ?? '—'}</td>
                    <td className="px-4 py-3 text-xs text-[rgba(0,0,0,0.6)] whitespace-nowrap">
                      {formatDate(row.trialEndAt)}
                    </td>
                    <td className="px-4 py-3">
                      {/* Icon AND text, so the verdict never depends on colour alone. */}
                      <Pill className={meta.className}>
                        <Icon icon={meta.icon} width={11} />
                        {verdict.label}
                      </Pill>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          onClick={() => openExtend(row)}
                          disabled={isMutating}
                          className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-widest border border-[rgba(0,0,0,0.08)] bg-white text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-50 transition-colors"
                        >
                          <Icon icon="mdi:clock-outline" width={11} />
                          Extend trial
                        </button>
                        <button
                          type="button"
                          onClick={() => openConvert(row)}
                          disabled={isMutating || verdict.state === 'NONE'}
                          className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-[10px] font-bold uppercase tracking-widest bg-[rgba(0,0,0,0.88)] hover:bg-black text-white disabled:opacity-40 transition-colors"
                        >
                          <Icon icon="mdi:shield-check-outline" width={11} />
                          Convert to paid
                        </button>
                        {isMutating && (
                          <Icon icon="mdi:loading" width={13} color="rgba(0,0,0,0.3)" className="animate-spin" aria-hidden="true" />
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}

      {/* ── Extend dialog ── */}
      <DialogRoot isOpen={!!extendTarget} onClose={closeExtend}>
        <DialogPanel
          title="Extend trial"
          onClose={closeExtend}
          footer={
            <>
              <button
                type="button"
                onClick={closeExtend}
                className="px-4 py-2 rounded-xl text-xs font-bold text-[rgba(0,0,0,0.6)] bg-white border border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.03)] transition-colors"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => { void submitExtend(); }}
                disabled={!!extendTarget && mutatingClientId === extendTarget.clientId}
                className="px-4 py-2 rounded-xl text-xs font-bold bg-[rgba(0,0,0,0.88)] hover:bg-black text-white disabled:opacity-50 transition-colors"
              >
                Extend trial
              </button>
            </>
          }
        >
          {extendTarget && (
            <div className="space-y-4">
              <p className="text-xs text-[rgba(0,0,0,0.6)]">
                <strong className="text-[rgba(0,0,0,0.88)]">{extendTarget.companyName ?? 'Unnamed tenant'}</strong>
                {' '}(client #{extendTarget.clientId}) — applies to the whole tenant, every user under it.
              </p>
              <p className="text-xs text-[rgba(0,0,0,0.6)]">
                Current trial end: <strong className="text-[rgba(0,0,0,0.88)]">{formatDate(extendTarget.trialEndAt)}</strong>.
                The extension runs from the later of today and the current end date, so an already
                lapsed trial restarts from today.
              </p>

              <div>
                <p className="text-[10px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wider mb-1.5">Extend by</p>
                <div className="flex flex-wrap gap-2">
                  {DAY_PRESETS.map(days => (
                    <button
                      key={days}
                      type="button"
                      onClick={() => { setSelectedDays(days); setCustomDays(''); setFormError(null); }}
                      aria-pressed={!customDays.trim() && selectedDays === days}
                      className={`px-3 py-1.5 rounded-lg text-xs font-semibold border transition-colors ${
                        !customDays.trim() && selectedDays === days
                          ? 'bg-[rgba(0,0,0,0.88)] border-[rgba(0,0,0,0.88)] text-white'
                          : 'bg-white border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)]'
                      }`}
                    >
                      {days} days
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label htmlFor="trial-custom-days" className="block text-[10px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wider mb-1.5">
                  Or a custom number of days ({MIN_DAYS}–{MAX_DAYS})
                </label>
                <input
                  id="trial-custom-days"
                  type="number"
                  min={MIN_DAYS}
                  max={MAX_DAYS}
                  value={customDays}
                  onChange={e => { setCustomDays(e.target.value); setFormError(null); }}
                  placeholder="e.g. 45"
                  className="w-full px-3 py-2 text-xs bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)] transition-colors"
                />
              </div>

              {(formError || mutationError) && (
                <div className="flex items-start gap-2 p-3 bg-rose-50 border border-rose-100 rounded-lg text-xs text-rose-600">
                  <Icon icon="mdi:alert-circle-outline" width={14} className="flex-shrink-0 mt-0.5" />
                  <span>{formError ?? mutationError}</span>
                </div>
              )}
            </div>
          )}
        </DialogPanel>
      </DialogRoot>

      {/* ── Convert confirmation ── */}
      <AlertDialog
        isOpen={!!convertTarget}
        title="Convert this tenant to paid?"
        variant="danger"
        confirmLabel={convertInFlight ? 'Converting…' : 'Convert to paid'}
        confirmDisabled={convertInFlight}
        onConfirm={() => { void submitConvert(); }}
        onCancel={() => setConvertTarget(null)}
        description={convertTarget ? (
          <span className="block space-y-2">
            <span className="block">
              <strong>{convertTarget.companyName ?? 'Unnamed tenant'}</strong> (client #{convertTarget.clientId})
              will be marked PAID. This permanently removes trial gating for the whole tenant —
              every user under it.
            </span>
            <span className="block">
              It writes no subscription, no invoice and no entitlement record, so the tenant will
              look like a paying customer to anyone reconciling paid tenants against invoices.
              Billing has to be set up separately.
            </span>
            <span className="block">
              This cannot be undone from this screen.
            </span>
          </span>
        ) : undefined}
      />

      {/* Convert failures have no dialog of their own to land in. */}
      <NotificationModal
        isOpen={!!mutationError && !extendTarget}
        onClose={clearMutationState}
        type="error"
        title="Change not saved"
        message={mutationError ?? ''}
      />

      {/* Gated on both dialogs being closed: two react-aria overlays at once means two focus
          traps, and ariaHideOutside would hide the still-visible dialog from assistive tech. */}
      <NotificationModal
        isOpen={!!lastResult && !extendTarget && !convertTarget}
        onClose={clearMutationState}
        type="success"
        title={lastResult?.kind === 'convert' ? 'Converted to paid' : 'Trial extended'}
        message={successMessage()}
      />
    </div>
  );
};

export default TrialManagementSection;
