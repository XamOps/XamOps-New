import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  confirmRevertAll,
  createRevertAll,
  getRevertAll,
  newIdempotencyKey,
  type RevertAllBatch as Batch,
  type RevertAllItem,
} from '../../../api/autospottingApi';
import AsDialog from './AsDialog';
import { warningText } from './reasons';
import { BTN_DANGER, BTN_SECONDARY, CHECKBOX, MUTED, Notice } from './theme';

interface RevertAllBatchProps {
  accountId: number;
  onClose: () => void;
  /** Called once every group of the batch is REVERTED. */
  onAllReverted?: () => void;
  pollMs?: number;
  retryDelayMs?: number;
}

function warningIdsOf(item: RevertAllItem): string[] {
  const raw = item.plan?.warningIds;
  if (!raw) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

/**
 * "Revert all" for one account (design-core.md §7.5, PO-21): one revert plan per converted group, one
 * confirmation for the batch, then a live "n of m reverted" count. A group that cannot be confirmed
 * shows its own error and the others still run.
 */
export const RevertAllBatch: React.FC<RevertAllBatchProps> = ({
  accountId,
  onClose,
  onAllReverted,
  pollMs = 5000,
  retryDelayMs,
}) => {
  const [batch, setBatch] = useState<Batch | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [acked, setAcked] = useState<string[]>([]);
  const createKey = useRef(newIdempotencyKey());
  const confirmKey = useRef(newIdempotencyKey());
  const doneNotified = useRef(false);

  useEffect(() => {
    let live = true;
    createRevertAll(accountId, createKey.current, { retryDelayMs })
      .then(b => live && setBatch(b))
      .catch(e => live && setError(e instanceof Error ? e.message : 'Could not prepare the revert plans.'));
    return () => {
      live = false;
    };
  }, [accountId, retryDelayMs]);

  const finished = !!batch && batch.total > 0 && batch.reverted + batch.failed >= batch.total;

  useEffect(() => {
    if (!confirmed || !batch || finished) return undefined;
    const timer = setInterval(() => {
      getRevertAll(accountId, batch.batchId)
        .then(setBatch)
        .catch(() => undefined);
    }, pollMs);
    return () => clearInterval(timer);
  }, [confirmed, batch, finished, accountId, pollMs]);

  useEffect(() => {
    if (batch && batch.total > 0 && batch.reverted === batch.total && !doneNotified.current) {
      doneNotified.current = true;
      onAllReverted?.();
    }
  }, [batch, onAllReverted]);

  const warnings = useMemo(() => {
    const ids = new Set<string>();
    batch?.items.forEach(i => warningIdsOf(i).forEach(id => ids.add(id)));
    return Array.from(ids);
  }, [batch]);
  const ready = (batch?.items ?? []).filter(i => i.plan && !i.errorCode);
  const allAcked = warnings.every(w => acked.includes(w));

  const onConfirm = async () => {
    if (!batch) return;
    setConfirming(true);
    setError(null);
    try {
      const next = await confirmRevertAll(accountId, batch.batchId, { acknowledgedWarningIds: acked }, confirmKey.current, {
        retryDelayMs,
      });
      setBatch(next);
      setConfirmed(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not start the revert.');
    } finally {
      setConfirming(false);
    }
  };

  const footer = (
    <>
      <button type="button" onClick={onClose} disabled={confirming} className={BTN_SECONDARY}>
        {confirmed ? 'Close' : 'Cancel'}
      </button>
      {!confirmed && (
        <button
          type="button"
          onClick={onConfirm}
          disabled={!batch || ready.length === 0 || !allAcked || confirming}
          className={BTN_DANGER}
        >
          {confirming ? 'Reverting…' : `Revert ${ready.length} group${ready.length === 1 ? '' : 's'}`}
        </button>
      )}
    </>
  );

  return (
    <AsDialog
      title="Revert all groups"
      summary="Return every converted group in this account to its original configuration."
      onClose={onClose}
      busy={confirming}
      footer={footer}
    >
      {!batch && !error && <p className={`flex items-center gap-2 text-[13px] ${MUTED}`}><span aria-hidden="true" className="h-2 w-2 animate-pulse rounded-full bg-indigo-500" />Preparing revert plans…</p>}
      {error && (
        <Notice tone="danger" role="alert" className="mb-4">
          {error}
        </Notice>
      )}
      {batch && batch.total === 0 && <p className={`text-[13px] ${MUTED}`}>No groups in this account need a revert.</p>}
      {batch && batch.total > 0 && (
        <>
          <p role="status" className="mb-2 text-[13px] font-semibold">
            {batch.reverted} of {batch.total} reverted
          </p>
          <div aria-hidden="true" className="mb-4 h-2 w-full overflow-hidden rounded-full bg-[rgba(0,0,0,0.06)]">
            <div className="h-2 rounded-full bg-indigo-600 transition-all" style={{ width: `${Math.round((batch.reverted / batch.total) * 100)}%` }} />
          </div>
          <ul className="divide-y divide-[rgba(0,0,0,0.06)] overflow-hidden rounded-xl border border-[rgba(0,0,0,0.12)]">
            {batch.items.map(item => (
              <li key={item.groupId} className="flex items-center justify-between gap-3 px-4 py-2.5 text-[13px]">
                <span className="truncate font-mono text-[12px]">{item.groupKey ?? item.groupId}</span>
                <span className={`shrink-0 text-[12px] ${item.errorCode ? 'font-medium text-red-700' : item.groupState === 'REVERTED' ? 'font-medium text-emerald-700' : MUTED}`}>
                  {item.errorCode
                    ? `Not started: ${item.errorDetail ?? item.errorCode}`
                    : item.groupState === 'REVERTED'
                      ? 'Reverted'
                      : item.operationStatus
                        ? `Revert ${item.operationStatus.toLowerCase()}`
                        : `Currently ${(item.groupState ?? 'unknown').toLowerCase()}`}
                </span>
              </li>
            ))}
          </ul>
          {batch.alreadyReverting.length > 0 && (
            <p className={`mt-3 text-[13px] ${MUTED}`}>
              {batch.alreadyReverting.length} more group{batch.alreadyReverting.length === 1 ? ' is' : 's are'} already
              reverting and not part of this batch.
            </p>
          )}
          {!confirmed && warnings.length > 0 && (
            <fieldset className="mt-4 space-y-2 rounded-xl border border-amber-200 bg-amber-50 p-3 text-amber-900">
              <legend className="px-1 text-[13px] font-semibold">Acknowledge before reverting</legend>
              {warnings.map(id => (
                <label key={id} className="flex cursor-pointer items-start gap-2 text-[13px]">
                  <input
                    type="checkbox"
                    className={CHECKBOX}
                    checked={acked.includes(id)}
                    onChange={e => setAcked(prev => (e.target.checked ? [...prev, id] : prev.filter(x => x !== id)))}
                  />
                  <span>{warningText(id)}</span>
                </label>
              ))}
            </fieldset>
          )}
        </>
      )}
    </AsDialog>
  );
};

export default RevertAllBatch;
