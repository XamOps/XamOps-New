/**
 * DLQ tab on `/autospotting-admin` (plan-phase-22 task 22.5, design-aws.md section 9.8). Shows the depth of the
 * AWS events dead-letter queue and a summary of its messages, never their bodies, and replays them into the
 * events queue with a required reason. A replay is safe: events already applied are skipped. One replay at a
 * time. Super admins and XamOps admins only; the server refuses everyone else.
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AutoSpottingApiError,
  getEventsDlq,
  newIdempotencyKey,
  redriveEventsDlq,
  type DlqSummary,
} from '../../api/autospottingApi';
import { BTN_PRIMARY, BTN_SECONDARY, INPUT, TABLE, TD_SM, TH_SM, TR } from '../../pages/autospotting/components/theme';

const MAX_REASON = 500;
const when = (iso: string | null): string => (iso ? iso.replace('T', ' ').replace(/\.\d+Z$|Z$/, ' UTC') : '');

function messageFor(e: unknown, fallback: string): string {
  if (e instanceof AutoSpottingApiError) {
    if (e.status === 403) return 'You are not allowed to do this.';
    if (e.code === 'REDRIVE_IN_PROGRESS') return 'A replay is already running.';
    return e.detail || fallback;
  }
  return fallback;
}

const DlqPanel: React.FC = () => {
  const [summary, setSummary] = useState<DlqSummary | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const keyRef = useRef<string>(newIdempotencyKey());

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      setSummary(await getEventsDlq());
    } catch (e) {
      setLoadError(messageFor(e, 'The queue could not be read.'));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const reasonOk = reason.trim().length > 0 && reason.length <= MAX_REASON;
  const canReplay = !!summary && summary.configured && summary.depth > 0 && !summary.redriveRunning && reasonOk && !busy;

  const replay = async () => {
    setBusy(true);
    setFormError(null);
    setNotice(null);
    try {
      const r = await redriveEventsDlq(reason.trim(), keyRef.current);
      setNotice(r.mode === 'RESEND' ? `Replayed ${r.moved} messages.` : 'Replay started.');
      setReason('');
      keyRef.current = newIdempotencyKey();
      await load();
    } catch (e) {
      setFormError(messageFor(e, 'The replay failed. Try again.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-label="Event DLQ" className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white p-5 shadow-sm">
      <div className="flex items-center justify-between">
        <h2 className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Event DLQ</h2>
        <button type="button" onClick={() => void load()} className={BTN_SECONDARY}>
          Refresh
        </button>
      </div>
      {loadError && <p role="alert" className="mt-2 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-[13px] text-red-900">{loadError}</p>}
      {summary && !summary.configured && (
        <p className="mt-2 text-[13px] text-[rgba(0,0,0,0.55)]">No events queue in this environment.</p>
      )}
      {summary && summary.configured && (
        <>
          <p className="mt-2 text-[13px] text-[rgba(0,0,0,0.7)]">
            Queue {summary.queue}: <span data-testid="dlq-depth">{summary.depth}</span> messages
            {summary.redriveRunning ? ' (replay running)' : ''}
          </p>
          {summary.messages.length > 0 && (
            <table className={`mt-3 border-collapse ${TABLE}`}>
              <thead>
                <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
                  <th className={TH_SM}>Type</th>
                  <th className={TH_SM}>Account</th>
                  <th className={TH_SM}>Sent</th>
                  <th className={TH_SM}>Receives</th>
                </tr>
              </thead>
              <tbody>
                {summary.messages.map(m => (
                  <tr key={m.messageId} className={TR}>
                    <td className={TD_SM}>{m.detailType ?? 'Unreadable'}</td>
                    <td className={TD_SM}>{m.account ?? '-'}</td>
                    <td className={TD_SM}>{when(m.sentAt)}</td>
                    <td className={TD_SM}>{m.receiveCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <div className="mt-4 flex items-end gap-2">
            <label className="flex flex-1 flex-col text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.55)]">
              Reason
              <input
                type="text"
                value={reason}
                maxLength={MAX_REASON}
                onChange={e => setReason(e.target.value)}
                className={`mt-1 ${INPUT}`}
              />
            </label>
            <button
              type="button"
              disabled={!canReplay}
              onClick={() => void replay()}
              className={BTN_PRIMARY}
            >
              Replay
            </button>
          </div>
          {notice && <p role="status" className="mt-2 text-[13px] text-[rgba(0,0,0,0.7)]">{notice}</p>}
          {formError && <p role="alert" className="mt-2 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-[13px] text-red-900">{formError}</p>}
        </>
      )}
    </section>
  );
};

export default DlqPanel;
