import React from 'react';
import type { FailedLocation } from '../../../api/autospottingApi';
import { AutoSpottingApiError } from '../../../api/autospottingApi';
import type { GuidanceLink } from '../adapters/types';
import { BTN_PRIMARY, BTN_SECONDARY, CARD, EmptyState, LINK, Notice } from './theme';
/** First load: a table skeleton and no figures. */
export const LoadingPanel: React.FC = () => (
  <div role="status" aria-label="Loading groups" className={`${CARD} overflow-hidden`}>
    <div className="h-9 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]" />
    {[0, 1, 2, 3].map(i => (
      <div key={i} className="flex items-center gap-4 border-t border-[rgba(0,0,0,0.04)] px-5 py-4">
        <div className="h-3 w-40 animate-pulse rounded bg-[rgba(0,0,0,0.06)]" />
        <div className="h-3 w-24 animate-pulse rounded bg-[rgba(0,0,0,0.06)]" />
        <div className="h-5 w-16 animate-pulse rounded-full bg-[rgba(0,0,0,0.06)]" />
        <div className="ml-auto h-3 w-20 animate-pulse rounded bg-[rgba(0,0,0,0.06)]" />
      </div>
    ))}
  </div>
);
/** 200 with zero items and partial=false. */
export const EmptyPanel: React.FC<{ groupNoun: string }> = ({ groupNoun }) => (
  <EmptyState icon="mdi:server-off" title={`No ${groupNoun}s in this account`}>
    <a className={LINK} href="/account-manager">
      Check the account's regions
    </a>
  </EmptyState>
);
/** A permission error is never rendered as an empty list (AC-UI-03). */
export const PermissionPanel: React.FC<{ guidance: GuidanceLink; groupNoun: string }> = ({ guidance, groupNoun }) => (
  <Notice tone="warning" icon="mdi:shield-lock-outline" role="alert" className="py-4">
    <p className="font-semibold">{`XamOps can't read ${groupNoun.replace(/ Group$/, '')} in this account.`}</p>
    <p className="mt-1">{guidance.text}</p>
    {guidance.href && (
      <a className={`${BTN_PRIMARY} mt-3`} href={guidance.href}>
        {guidance.label}
      </a>
    )}
  </Notice>
);
/** 5xx or network. */
export const LoadErrorPanel: React.FC<{ error: unknown; onRetry: () => void }> = ({ error, onRetry }) => {
  const requestId = error instanceof AutoSpottingApiError ? error.requestId : null;
  return (
    <Notice tone="danger" role="alert" className="py-4">
      <p className="font-semibold">Couldn't load groups.</p>
      {requestId && <p className="mt-1">{`Request id ${requestId}`}</p>}
      <button type="button" onClick={onRetry} className={`${BTN_PRIMARY} mt-3`}>
        Retry
      </button>
    </Notice>
  );
};
/** 200 with partial=true: the rows that loaded, plus a bar naming the failed locations. */
export const PartialBanner: React.FC<{
  failed: FailedLocation[];
  locationLabel: string;
  onRetry: () => void;
}> = ({ failed, locationLabel, onRetry }) => {
  const noun = locationLabel.toLowerCase() + (failed.length === 1 ? '' : 's');
  return (
    <Notice tone="warning" role="status" className="mt-5">
      <div className="flex items-center justify-between gap-4">
        <span>{`Partial results: ${failed.length} ${noun} could not be read (${failed.map(f => f.location).join(', ')}).`}</span>
        <button type="button" onClick={onRetry} className={BTN_SECONDARY}>
          Retry
        </button>
      </div>
    </Notice>
  );
};
/** True when every failed location failed for permission and nothing loaded. */
export function isPermissionFailure(items: unknown[], failed: FailedLocation[]): boolean {
  return items.length === 0 && failed.length > 0 && failed.every(f => f.code === 'PERMISSION');
}
