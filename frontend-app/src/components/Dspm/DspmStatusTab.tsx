import React from 'react';
import { Icon } from '@iconify/react';
import type { DspmStatus } from '../../types/dspm';
import DspmNotReady from './DspmNotReady';

/**
 * Connection state for the DSPM add-on, customer-facing.
 *
 * Deliberately shows the project **name** and never the project id. The id is an internal vendor
 * identifier a customer has no use for, and surfacing it would leak an implementation detail into a
 * surface that may be white-labelled.
 */
const DspmStatusTab: React.FC<{ status: DspmStatus; onRetry: () => void }> = ({ status, onRetry }) => {
  if (!status.ready) return <DspmNotReady status={status} onRetry={onRetry} />;

  return (
    <div className="max-w-xl">
      <div className="rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white p-5">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-emerald-50">
            <Icon icon="mdi:check-circle-outline" width={18} className="text-emerald-600" />
          </div>
          <div>
            <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Connected</h2>
            <p className="text-xs text-[rgba(0,0,0,0.45)]">
              Your data security workspace is reporting normally.
            </p>
          </div>
        </div>

        <dl className="mt-4 space-y-0 border-t border-[rgba(0,0,0,0.06)] pt-2">
          {status.projectName && (
            <div className="flex items-start gap-3 border-b border-[rgba(0,0,0,0.04)] py-2">
              <dt className="w-36 flex-shrink-0 text-[11px] text-[rgba(0,0,0,0.45)]">Workspace</dt>
              <dd className="text-xs text-[rgba(0,0,0,0.8)]">{status.projectName}</dd>
            </div>
          )}
          <div className="flex items-start gap-3 py-2">
            <dt className="w-36 flex-shrink-0 text-[11px] text-[rgba(0,0,0,0.45)]">Last verified</dt>
            <dd className="text-xs text-[rgba(0,0,0,0.8)]">
              {status.lastVerifiedAt
                ? new Date(status.lastVerifiedAt).toLocaleString()
                : 'Not yet verified'}
            </dd>
          </div>
        </dl>

        <p className="mt-3 text-[11px] leading-relaxed text-[rgba(0,0,0,0.4)]">
          Scans run on their own schedule. Assets appear here as they are discovered, and record counts
          appear once each scan completes — an asset showing no count has not finished scanning rather than
          being confirmed clean.
        </p>
      </div>
    </div>
  );
};

export default DspmStatusTab;
