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
    <div className="mt-2 flex flex-wrap items-center gap-2 text-sm">
      <input
        aria-label="Reason"
        value={reason}
        maxLength={MAX_REASON}
        onChange={e => setReason(e.target.value)}
        placeholder="Reason"
        className="w-72 rounded border border-gray-400 px-2 py-1 text-black"
      />
      <button
        type="button"
        disabled={!valid || busy}
        onClick={() => onSubmit(reason.trim())}
        className="rounded border border-black bg-black px-3 py-1 text-white disabled:border-gray-300 disabled:bg-gray-300"
      >
        {label}
      </button>
      <button type="button" onClick={onCancel} className="rounded border border-gray-400 px-3 py-1 text-black">
        Cancel
      </button>
      {error && (
        <span role="alert" className="text-black">
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
        <p role="status" className="rounded border border-gray-400 bg-gray-100 px-3 py-2 text-sm text-black">
          {notice}
        </p>
      )}
      {loadError && (
        <p role="alert" className="rounded border border-gray-400 px-3 py-2 text-sm text-black">
          {loadError}
        </p>
      )}

      <div className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white p-5">
        <h3 className="mb-3 text-[14px] font-bold text-black">Switches</h3>
        {switches === null ? (
          <p className="text-sm text-gray-600">Loading switches.</p>
        ) : switches.length === 0 ? (
          <p className="text-sm text-gray-600">No switches.</p>
        ) : (
          <table className="w-full text-left text-sm text-black">
            <thead className="text-xs text-gray-600">
              <tr>
                <th className="py-1 pr-3">Scope</th>
                <th className="py-1 pr-3">Property</th>
                <th className="py-1 pr-3">State</th>
                <th className="py-1 pr-3">Reason</th>
                <th className="py-1 pr-3">Changed</th>
                <th className="py-1" />
              </tr>
            </thead>
            <tbody>
              {switches.map(s => (
                <tr key={s.scopeKey} className="border-t border-gray-200">
                  <td className="py-2 pr-3 font-medium">{s.scopeKey}</td>
                  <td className="py-2 pr-3">{s.propertyValue === null ? 'Not set' : s.propertyValue ? 'On' : 'Off'}</td>
                  <td className="py-2 pr-3">{s.engaged ? 'Engaged' : 'Released'}</td>
                  <td className="py-2 pr-3 text-gray-600">{s.reason ?? ''}</td>
                  <td className="py-2 pr-3 text-gray-600">{s.changedAt ? `${when(s.changedAt)}${s.changedBy ? ` by ${s.changedBy}` : ''}` : ''}</td>
                  <td className="py-2">
                    <button
                      type="button"
                      onClick={() => start({ type: 'switch', scopeKey: s.scopeKey, engaged: !s.engaged, version: s.version })}
                      className="rounded border border-black px-3 py-1"
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

      <div className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white p-5">
        <h3 className="mb-3 text-[14px] font-bold text-black">Stuck operations</h3>
        {ops === null ? (
          <p className="text-sm text-gray-600">Loading operations.</p>
        ) : ops.length === 0 ? (
          <p className="text-sm text-gray-600">No stuck operations.</p>
        ) : (
          <table className="w-full text-left text-sm text-black">
            <thead className="text-xs text-gray-600">
              <tr>
                <th className="py-1 pr-3">Operation</th>
                <th className="py-1 pr-3">Status</th>
                <th className="py-1 pr-3">Waiting on</th>
                <th className="py-1 pr-3">Deadline</th>
                <th className="py-1 pr-3">Last error</th>
                <th className="py-1" />
              </tr>
            </thead>
            <tbody>
              {ops.map(o => (
                <tr key={o.operationId} className="border-t border-gray-200">
                  <td className="py-2 pr-3">
                    <span className="font-medium">{o.kind}</span>
                    <span className="ml-2 font-mono text-xs text-gray-600">{o.operationId}</span>
                  </td>
                  <td className="py-2 pr-3">{o.status}</td>
                  <td className="py-2 pr-3">{o.waitReason ?? ''}</td>
                  <td className="whitespace-nowrap py-2 pr-3">{when(o.deadlineAt)}</td>
                  <td className="py-2 pr-3">
                    {o.errorCode && <span className="font-medium">{o.errorCode}</span>}
                    {o.errorMessage && <span className="ml-2 text-gray-600">{o.errorMessage}</span>}
                  </td>
                  <td className="whitespace-nowrap py-2">
                    {o.groupId && (
                      <>
                        <button
                          type="button"
                          onClick={() => start({ type: 'group', groupId: o.groupId as string, action: 'pause' })}
                          className="mr-2 rounded border border-black px-3 py-1"
                        >
                          Pause
                        </button>
                        <button
                          type="button"
                          onClick={() => start({ type: 'group', groupId: o.groupId as string, action: 'resume' })}
                          className="rounded border border-gray-400 px-3 py-1"
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
