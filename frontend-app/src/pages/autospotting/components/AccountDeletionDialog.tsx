import React, { useCallback, useEffect, useState } from 'react';
import { getDeletionCheck, type DeletionCheck, type ManagedGroupRef } from '../../../api/autospottingApi';
import AsDialog from './AsDialog';
import RevertAllBatch from './RevertAllBatch';
import { Icon } from '@iconify/react';
import { BTN_DANGER, BTN_SECONDARY, MUTED, Notice } from './theme';

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
  <ul className="divide-y divide-[rgba(0,0,0,0.06)] overflow-hidden rounded-xl border border-[rgba(0,0,0,0.12)]">
    {groups.map(g => (
      <li key={g.groupId} className="flex items-center justify-between gap-3 px-4 py-2.5 text-[13px]">
        <span className="truncate font-mono text-[12px]">{g.groupKey}</span>
        <span className={`shrink-0 text-[12px] ${MUTED}`}>{g.state.toLowerCase()}</span>
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
          <button type="button" onClick={onClose} className={BTN_SECONDARY}>
            Cancel
          </button>
          {convertedCount > 0 && (
            <button
              type="button"
              onClick={() => setReverting(true)}
              className={BTN_SECONDARY}
            >
              Revert all
            </button>
          )}
          <button
            type="button"
            onClick={onDelete}
            disabled={blocked}
            className={BTN_DANGER}
          >
            Delete account
          </button>
        </>
      }
    >
      {!check && !error && <p className={`text-[13px] ${MUTED}`}>Checking this account…</p>}
      {error && (
        <Notice tone="danger" role="alert">
          {error}
        </Notice>
      )}
      {check && check.blocking.length > 0 && (
        <section className="mb-5">
          <h3 className="mb-2 flex items-center gap-1.5 text-[13px] font-semibold text-red-700">
            <Icon icon="mdi:block-helper" width={14} aria-hidden="true" />
            Revert these groups before deleting
          </h3>
          <List groups={check.blocking} />
        </section>
      )}
      {check && check.stillConverted.length > 0 && (
        <section>
          <h3 className="mb-1 flex items-center gap-1.5 text-[13px] font-semibold text-amber-800">
            <Icon icon="mdi:alert-outline" width={14} aria-hidden="true" />
            Still converted
          </h3>
          <p className={`mb-2 text-[13px] ${MUTED}`}>
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
