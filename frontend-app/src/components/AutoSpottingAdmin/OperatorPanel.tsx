/**
 * Operator views on `/autospotting-admin` (plan-phase-14 task 14.5, design-ux-ops.md section 2.1):
 * Switches (engage and release), Stuck operations, and Pause or Resume per group, all on
 * `/api/xamops/autospotting-admin` through `autospottingApi`. Every change carries a reason, the
 * switch's `expectedVersion`, the intent header and an idempotency key. The server allows super admins
 * and XamOps admins only; any other caller sees the refusal.
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AutoSpottingApiError,
  listOperatorOperations,
  listSwitches,
  newIdempotencyKey,
  pauseGroup,
  resumeGroup,
  setSwitch,
  type Operation,
  type OperatorSwitch,
} from '../../api/autospottingApi';
import { BTN_PRIMARY, BTN_SECONDARY, INPUT, TABLE, TD_SM, TH_SM, TR } from '../../pages/autospotting/components/theme';

const MAX_REASON = 500;

const when = (iso: string | null): string => (iso ? iso.replace('T', ' ').replace(/\.\d+Z$|Z$/, ' UTC') : '');

function messageFor(e: unknown): string {
  if (e instanceof AutoSpottingApiError) {
    if (e.status === 403) return 'You are not allowed to change this.';
    if (e.code === 'SWITCH_VERSION_MISMATCH' || e.status === 409) return 'Someone else changed it. Reload and try again.';
    return e.detail || 'The change failed. Try again.';
  }
  return 'The change failed. Try again.';
}

type Pending =
  | { type: 'switch'; scopeKey: string; engaged: boolean; version: number }
  | { type: 'group'; groupId: string; action: 'pause' | 'resume' };

const ReasonForm: React.FC<{
  label: string;
  busy: boolean;
  error: string | null;
  onSubmit: (reason: string) => void;
  onCancel: () => void;
}> = ({ label, busy, error, onSubmit, onCancel }) => {
  const [reason, setReason] = useState('');
  const valid = reason.trim().length > 0 && reason.length <= MAX_REASON;
  return (
    <div className="mt-3 flex flex-wrap items-center gap-2 text-[13px]">
      <input
        aria-label="Reason"
        value={reason}
        maxLength={MAX_REASON}
        onChange={e => setReason(e.target.value)}
        placeholder="Reason"
        className={`w-72 ${INPUT}`}
      />
      <button
        type="button"
        disabled={!valid || busy}
        onClick={() => onSubmit(reason.trim())}
        className={BTN_PRIMARY}
      >
        {label}
      </button>
      <button type="button" onClick={onCancel} className={BTN_SECONDARY}>
        Cancel
      </button>
      {error && (
        <span role="alert" className="text-red-700">
          {error}
        </span>
      )}
    </div>
  );
};

const OperatorPanel: React.FC = () => {
  const [switches, setSwitches] = useState<OperatorSwitch[] | null>(null);
  const [ops, setOps] = useState<Operation[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  // One idempotency key per user action, reused if the same action is retried.
  const keyRef = useRef<string | null>(null);

  const load = useCallback(async () => {
    setLoadError(null);
    const [sw, op] = await Promise.allSettled([listSwitches(), listOperatorOperations()]);
    if (sw.status === 'fulfilled') setSwitches(sw.value);
    if (op.status === 'fulfilled') setOps(op.value.operations);
    if (sw.status === 'rejected' || op.status === 'rejected') {
      setLoadError(messageFor(sw.status === 'rejected' ? sw.reason : (op as PromiseRejectedResult).reason).replace('change', 'read'));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const start = (next: Pending) => {
    keyRef.current = newIdempotencyKey();
    setPending(next);
    setFormError(null);
    setNotice(null);
  };

  const submit = async (reason: string) => {
    if (!pending) return;
    setBusy(true);
    setFormError(null);
    try {
      const key = keyRef.current ?? newIdempotencyKey();
      if (pending.type === 'switch') {
        await setSwitch(
          pending.scopeKey,
          { engaged: pending.engaged, expectedVersion: pending.version, reason },
          key,
        );
        setNotice(`${pending.scopeKey} ${pending.engaged ? 'engaged' : 'released'}.`);
      } else if (pending.action === 'pause') {
        await pauseGroup(pending.groupId, reason, key);
        setNotice('Group paused.');
      } else {
        await resumeGroup(pending.groupId, reason, key);
        setNotice('Group resumed.');
      }
      setPending(null);
      await load();
    } catch (e) {
      setFormError(messageFor(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-label="Operator views" className="space-y-5">
      {notice && (
        <p role="status" className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-[13px] text-emerald-900">
          {notice}
        </p>
      )}
      {loadError && (
        <p role="alert" className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-[13px] text-red-900">
          {loadError}
        </p>
      )}

      <div className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white p-5 shadow-sm">
        <h3 className="mb-3 text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Switches</h3>
        {switches === null ? (
          <p className="text-[13px] text-[rgba(0,0,0,0.55)]">Loading switches.</p>
        ) : switches.length === 0 ? (
          <p className="text-[13px] text-[rgba(0,0,0,0.55)]">No switches.</p>
        ) : (
          <table className={TABLE}>
            <thead className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
              <tr>
                <th className={TH_SM}>Scope</th>
                <th className={TH_SM}>Property</th>
                <th className={TH_SM}>State</th>
                <th className={TH_SM}>Reason</th>
                <th className={TH_SM}>Changed</th>
                <th className={TH_SM} />
              </tr>
            </thead>
            <tbody>
              {switches.map(s => (
                <tr key={s.scopeKey} className={TR}>
                  <td className={`${TD_SM} font-medium`}>{s.scopeKey}</td>
                  <td className={TD_SM}>{s.propertyValue === null ? 'Not set' : s.propertyValue ? 'On' : 'Off'}</td>
                  <td className={TD_SM}>
                    <span
                      className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-semibold ${
                        s.engaged ? 'border-red-200 bg-red-50 text-red-700' : 'border-emerald-200 bg-emerald-50 text-emerald-700'
                      }`}
                    >
                      <span aria-hidden="true" className={`h-1.5 w-1.5 rounded-full ${s.engaged ? 'bg-red-600' : 'bg-emerald-600'}`} />
                      {s.engaged ? 'Engaged' : 'Released'}
                    </span>
                  </td>
                  <td className={`${TD_SM} text-[rgba(0,0,0,0.55)]`}>{s.reason ?? ''}</td>
                  <td className={`${TD_SM} text-[rgba(0,0,0,0.55)]`}>{s.changedAt ? `${when(s.changedAt)}${s.changedBy ? ` by ${s.changedBy}` : ''}` : ''}</td>
                  <td className={TD_SM}>
                    <button
                      type="button"
                      onClick={() => start({ type: 'switch', scopeKey: s.scopeKey, engaged: !s.engaged, version: s.version })}
                      className={BTN_SECONDARY}
                    >
                      {s.engaged ? 'Release' : 'Engage'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {pending?.type === 'switch' && (
          <ReasonForm
            label={pending.engaged ? `Engage ${pending.scopeKey}` : `Release ${pending.scopeKey}`}
            busy={busy}
            error={formError}
            onSubmit={r => void submit(r)}
            onCancel={() => setPending(null)}
          />
        )}
      </div>

      <div className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white p-5 shadow-sm">
        <h3 className="mb-3 text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Stuck operations</h3>
        {ops === null ? (
          <p className="text-[13px] text-[rgba(0,0,0,0.55)]">Loading operations.</p>
        ) : ops.length === 0 ? (
          <p className="text-[13px] text-[rgba(0,0,0,0.55)]">No stuck operations.</p>
        ) : (
          <table className={TABLE}>
            <thead className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
              <tr>
                <th className={TH_SM}>Operation</th>
                <th className={TH_SM}>Status</th>
                <th className={TH_SM}>Waiting on</th>
                <th className={TH_SM}>Deadline</th>
                <th className={TH_SM}>Last error</th>
                <th className={TH_SM} />
              </tr>
            </thead>
            <tbody>
              {ops.map(o => (
                <tr key={o.operationId} className={TR}>
                  <td className={TD_SM}>
                    <span className="font-medium">{o.kind}</span>
                    <span className="ml-2 font-mono text-[11px] text-[rgba(0,0,0,0.55)]">{o.operationId}</span>
                  </td>
                  <td className={TD_SM}>{o.status}</td>
                  <td className={TD_SM}>{o.waitReason ?? ''}</td>
                  <td className={`${TD_SM} whitespace-nowrap`}>{when(o.deadlineAt)}</td>
                  <td className={TD_SM}>
                    {o.errorCode && <span className="font-medium">{o.errorCode}</span>}
                    {o.errorMessage && <span className="ml-2 text-[rgba(0,0,0,0.55)]">{o.errorMessage}</span>}
                  </td>
                  <td className={`${TD_SM} whitespace-nowrap`}>
                    {o.groupId && (
                      <>
                        <button
                          type="button"
                          onClick={() => start({ type: 'group', groupId: o.groupId as string, action: 'pause' })}
                          className={`mr-2 ${BTN_SECONDARY}`}
                        >
                          Pause
                        </button>
                        <button
                          type="button"
                          onClick={() => start({ type: 'group', groupId: o.groupId as string, action: 'resume' })}
                          className={BTN_SECONDARY}
                        >
                          Resume
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {pending?.type === 'group' && (
          <ReasonForm
            label={pending.action === 'pause' ? 'Pause group' : 'Resume group'}
            busy={busy}
            error={formError}
            onSubmit={r => void submit(r)}
            onCancel={() => setPending(null)}
          />
        )}
      </div>
    </section>
  );
};

export default OperatorPanel;
