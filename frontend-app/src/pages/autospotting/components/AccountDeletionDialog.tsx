import React, { useCallback, useEffect, useState } from 'react';
import { getDeletionCheck, type DeletionCheck, type ManagedGroupRef } from '../../../api/autospottingApi';
import AsDialog from './AsDialog';
import RevertAllBatch from './RevertAllBatch';

interface AccountDeletionDialogProps {
  accountId: number;
  accountName: string;
  onClose: () => void;
  /** The customer chose to delete the account; only reachable when nothing blocks. */
  onDelete: () => void;
  pollMs?: number;
  retryDelayMs?: number;
}

const List: React.FC<{ groups: ManagedGroupRef[] }> = ({ groups }) => (
  <ul className="divide-y divide-gray-200 border border-gray-200">
    {groups.map(g => (
      <li key={g.groupId} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
        <span className="truncate font-mono">{g.groupKey}</span>
        <span className="shrink-0 text-gray-600">{g.state.toLowerCase()}</span>
      </li>
    ))}
  </ul>
);

/**
 * Account deletion with AutoSpotting groups (design-core.md §7.5, PO-21, PO-21a). Groups that are applying,
 * active, on fallback or reverting block deletion; degraded, paused and failed groups do not, but are listed
 * as still converted, because deleting the account leaves them running on Spot with no way to revert.
 */
export const AccountDeletionDialog: React.FC<AccountDeletionDialogProps> = ({
  accountId,
  accountName,
  onClose,
  onDelete,
  pollMs,
  retryDelayMs,
}) => {
  const [check, setCheck] = useState<DeletionCheck | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reverting, setReverting] = useState(false);

  const load = useCallback(() => {
    getDeletionCheck(accountId)
      .then(c => {
        setCheck(c);
        setError(null);
      })
      .catch(e => setError(e instanceof Error ? e.message : 'Could not check this account.'));
  }, [accountId]);

  useEffect(() => {
    load();
  }, [load]);

  if (reverting) {
    return (
      <RevertAllBatch
        accountId={accountId}
        pollMs={pollMs}
        retryDelayMs={retryDelayMs}
        onAllReverted={load}
        onClose={() => {
          setReverting(false);
          load();
        }}
      />
    );
  }

  const blocked = !check || check.blocking.length > 0;
  const convertedCount = (check?.blocking.length ?? 0) + (check?.stillConverted.length ?? 0);
  return (
    <AsDialog
      title={`Delete ${accountName}`}
      summary="AutoSpotting still has converted groups in this account."
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose} className="rounded border border-gray-400 px-3 py-1.5 text-sm">
            Cancel
          </button>
          {convertedCount > 0 && (
            <button
              type="button"
              onClick={() => setReverting(true)}
              className="rounded border border-black px-3 py-1.5 text-sm"
            >
              Revert all
            </button>
          )}
          <button
            type="button"
            onClick={onDelete}
            disabled={blocked}
            className="rounded border border-black bg-black px-3 py-1.5 text-sm text-white disabled:border-gray-300 disabled:bg-gray-300"
          >
            Delete account
          </button>
        </>
      }
    >
      {!check && !error && <p className="text-sm text-gray-600">Checking this account…</p>}
      {error && (
        <p role="alert" className="text-sm text-black">
          {error}
        </p>
      )}
      {check && check.blocking.length > 0 && (
        <section className="mb-4">
          <h3 className="mb-1 text-sm font-semibold">Revert these groups before deleting</h3>
          <List groups={check.blocking} />
        </section>
      )}
      {check && check.stillConverted.length > 0 && (
        <section>
          <h3 className="mb-1 text-sm font-semibold">Still converted</h3>
          <p className="mb-2 text-sm text-gray-600">
            These groups do not block deletion. If you delete the account they stay as they are and can no longer be
            reverted from XamOps. Revert all includes them.
          </p>
          <List groups={check.stillConverted} />
        </section>
      )}
    </AsDialog>
  );
};

export default AccountDeletionDialog;
