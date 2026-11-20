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
      <button type="button" onClick={onClose} disabled={confirming} className="rounded border border-gray-400 px-3 py-1.5 text-sm">
        {confirmed ? 'Close' : 'Cancel'}
      </button>
      {!confirmed && (
        <button
          type="button"
          onClick={onConfirm}
          disabled={!batch || ready.length === 0 || !allAcked || confirming}
          className="rounded border border-black bg-black px-3 py-1.5 text-sm text-white disabled:border-gray-300 disabled:bg-gray-300"
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
      {!batch && !error && <p className="text-sm text-gray-600">Preparing revert plans…</p>}
      {error && (
        <p role="alert" className="mb-3 text-sm text-black">
          {error}
        </p>
      )}
      {batch && batch.total === 0 && <p className="text-sm text-gray-600">No groups in this account need a revert.</p>}
      {batch && batch.total > 0 && (
        <>
          <p role="status" className="mb-3 text-sm font-semibold">
            {batch.reverted} of {batch.total} reverted
          </p>
          <ul className="divide-y divide-gray-200 border border-gray-200">
            {batch.items.map(item => (
              <li key={item.groupId} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
                <span className="truncate font-mono">{item.groupKey ?? item.groupId}</span>
                <span className="shrink-0 text-gray-600">
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
            <p className="mt-3 text-sm text-gray-600">
              {batch.alreadyReverting.length} more group{batch.alreadyReverting.length === 1 ? ' is' : 's are'} already
              reverting and not part of this batch.
            </p>
          )}
          {!confirmed && warnings.length > 0 && (
            <fieldset className="mt-4 space-y-2">
              <legend className="text-sm font-semibold">Acknowledge before reverting</legend>
              {warnings.map(id => (
                <label key={id} className="flex items-start gap-2 text-sm">
                  <input
                    type="checkbox"
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
