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
    <section aria-label="Event DLQ" className="rounded border border-gray-300 bg-white p-4">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold text-black">Event DLQ</h2>
        <button type="button" onClick={() => void load()} className="rounded border border-gray-400 bg-white px-3 py-1 text-sm text-black">
          Refresh
        </button>
      </div>
      {loadError && <p role="alert" className="mt-2 text-sm text-red-700">{loadError}</p>}
      {summary && !summary.configured && (
        <p className="mt-2 text-sm text-gray-600">No events queue in this environment.</p>
      )}
      {summary && summary.configured && (
        <>
          <p className="mt-2 text-sm text-gray-700">
            Queue {summary.queue}: <span data-testid="dlq-depth">{summary.depth}</span> messages
            {summary.redriveRunning ? ' (replay running)' : ''}
          </p>
          {summary.messages.length > 0 && (
            <table className="mt-3 w-full border-collapse text-sm">
              <thead>
                <tr className="border-b border-gray-300 text-left text-gray-600">
                  <th className="py-1 pr-3 font-medium">Type</th>
                  <th className="py-1 pr-3 font-medium">Account</th>
                  <th className="py-1 pr-3 font-medium">Sent</th>
                  <th className="py-1 font-medium">Receives</th>
                </tr>
              </thead>
              <tbody>
                {summary.messages.map(m => (
                  <tr key={m.messageId} className="border-b border-gray-200">
                    <td className="py-1 pr-3">{m.detailType ?? 'Unreadable'}</td>
                    <td className="py-1 pr-3">{m.account ?? '-'}</td>
                    <td className="py-1 pr-3">{when(m.sentAt)}</td>
                    <td className="py-1">{m.receiveCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <div className="mt-4 flex items-end gap-2">
            <label className="flex flex-1 flex-col text-sm text-gray-700">
              Reason
              <input
                type="text"
                value={reason}
                maxLength={MAX_REASON}
                onChange={e => setReason(e.target.value)}
                className="mt-1 rounded border border-gray-400 px-2 py-1 text-black"
              />
            </label>
            <button
              type="button"
              disabled={!canReplay}
              onClick={() => void replay()}
              className="rounded border border-black bg-black px-3 py-1 text-sm text-white disabled:border-gray-300 disabled:bg-gray-200 disabled:text-gray-500"
            >
              Replay
            </button>
          </div>
          {notice && <p role="status" className="mt-2 text-sm text-gray-700">{notice}</p>}
          {formError && <p role="alert" className="mt-2 text-sm text-red-700">{formError}</p>}
        </>
      )}
    </section>
  );
};

export default DlqPanel;
