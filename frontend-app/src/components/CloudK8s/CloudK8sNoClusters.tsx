import React from 'react';
import { Icon } from '@iconify/react';
import { Link } from 'react-router-dom';
import { BUTTON, LINK, INK, MUTED } from '../EksDetails/sections/automation/automationUi';

interface CloudK8sNoClustersProps {
  /** Force a fresh scan of the account's active regions. Absent means the Re-scan button is hidden. */
  onRescan?: () => void;
}

/**
 * The fleet's honest empty state (FEAT-003). It is NOT the shared single-action EmptyState: an empty
 * fleet has three real next steps (re-scan, add an account, manage accounts), and the copy is honest
 * about what XamOps actually looked at. No tinted coin, no role='alert' (this is not an error).
 */
const CloudK8sNoClusters: React.FC<CloudK8sNoClustersProps> = ({ onRescan }) => {
  return (
    <div
      data-testid="fleet-empty"
      className="flex flex-col items-center gap-3 px-6 py-12 text-center"
    >
      <Icon icon="mdi:kubernetes" width={28} aria-hidden="true" className={MUTED} />
      <h2 className={`text-[15px] font-semibold ${INK}`}>No clusters found in this account</h2>
      <div className={`max-w-[48ch] space-y-1 text-[13px] ${MUTED}`}>
        <p>
          XamOps looked in the account's active regions and found no EKS clusters. If you expected one,
          check the region it runs in or re-scan.
        </p>
        <p>If the cluster is in a different account, add or switch the account.</p>
      </div>
      <div className="mt-2 flex flex-wrap items-center justify-center gap-2.5">
        {onRescan && (
          <button type="button" onClick={onRescan} className={`inline-flex items-center gap-1.5 ${BUTTON}`}>
            <Icon icon="mdi:refresh" width={14} aria-hidden="true" />
            Re-scan
          </button>
        )}
        <Link to="/add-account" className={`inline-flex items-center gap-1.5 ${BUTTON}`}>
          <Icon icon="mdi:plus" width={14} aria-hidden="true" />
          Add an account
        </Link>
        <Link to="/account-manager" className={`text-[13px] ${LINK}`}>
          Manage accounts
        </Link>
      </div>
    </div>
  );
};

export default CloudK8sNoClusters;
