import React from 'react';
import type { FailedLocation } from '../../../api/autospottingApi';
import { AutoSpottingApiError } from '../../../api/autospottingApi';
import type { GuidanceLink } from '../adapters/types';

const Box: React.FC<{ children: React.ReactNode; role?: string }> = ({ children, role }) => (
  <div role={role} className="rounded border border-gray-300 bg-white p-6 text-center text-sm text-gray-600">
    {children}
  </div>
);

/** First load: a table skeleton and no figures. */
export const LoadingPanel: React.FC = () => (
  <div role="status" aria-label="Loading groups" className="space-y-2">
    {[0, 1, 2, 3].map(i => (
      <div key={i} className="h-10 animate-pulse rounded bg-gray-200" />
    ))}
  </div>
);

/** 200 with zero items and partial=false. */
export const EmptyPanel: React.FC<{ groupNoun: string }> = ({ groupNoun }) => (
  <Box>
    <p className="font-medium text-black">{`No ${groupNoun}s in this account`}</p>
    <p className="mt-1">
      <a className="underline" href="/account-manager">
        Check the account's regions
      </a>
    </p>
  </Box>
);

/** A permission error is never rendered as an empty list (AC-UI-03). */
export const PermissionPanel: React.FC<{ guidance: GuidanceLink; groupNoun: string }> = ({ guidance, groupNoun }) => (
  <Box role="alert">
    <p className="font-medium text-black">{`XamOps can't read ${groupNoun.replace(/ Group$/, '')} in this account.`}</p>
    <p className="mt-1">{guidance.text}</p>
    {guidance.href && (
      <a className="mt-2 inline-block rounded border border-black px-3 py-1 text-black" href={guidance.href}>
        {guidance.label}
      </a>
    )}
  </Box>
);

/** 5xx or network. */
export const LoadErrorPanel: React.FC<{ error: unknown; onRetry: () => void }> = ({ error, onRetry }) => {
  const requestId = error instanceof AutoSpottingApiError ? error.requestId : null;
  return (
    <Box role="alert">
      <p className="font-medium text-black">Couldn't load groups.</p>
      {requestId && <p className="mt-1">{`Request id ${requestId}`}</p>}
      <button type="button" onClick={onRetry} className="mt-2 rounded border border-black px-3 py-1 text-black">
        Retry
      </button>
    </Box>
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
    <div role="status" className="flex items-center justify-between rounded border border-gray-400 bg-gray-100 px-4 py-2 text-sm text-black">
      <span>{`Partial results: ${failed.length} ${noun} could not be read (${failed.map(f => f.location).join(', ')}).`}</span>
      <button type="button" onClick={onRetry} className="ml-4 rounded border border-black px-3 py-1">
        Retry
      </button>
    </div>
  );
};

/** True when every failed location failed for permission and nothing loaded. */
export function isPermissionFailure(items: unknown[], failed: FailedLocation[]): boolean {
  return items.length === 0 && failed.length > 0 && failed.every(f => f.code === 'PERMISSION');
}
