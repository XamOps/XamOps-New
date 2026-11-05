import React from 'react';
import { Link } from 'react-router-dom';
import { XpApiError } from '../../../api/xampulseErrors';
import { EmptyState, ErrorState } from './ui';
import { useViewContext } from '../../../hooks/xampulse/useViewContext';

interface QueryLike<T> { isLoading: boolean; isFetching?: boolean; error: unknown; data: T | undefined; refetch: () => unknown }

/** Renders loading, error (with request id + retry), the not-set-up call to action, or the data. */
export function QueryBoundary<T>({ query, skeleton, children }: { query: QueryLike<T>; skeleton: React.ReactNode; children: (data: T) => React.ReactNode }): React.ReactElement {
  const ctx = useViewContext();
  if (query.isLoading) return <>{skeleton}</>;
  if (query.error) {
    if (query.error instanceof XpApiError && query.error.code === 'XP_NOT_SET_UP') {
      return (
        <EmptyState title="Connect this account to XamPulse" body="This account has not been set up yet. It takes one step and a few minutes."
          action={<Link className="xp-btn xp-btn--primary" to={ctx.link('/xampulse/setup')}>Start setup</Link>} />
      );
    }
    return <ErrorState error={query.error} onRetry={() => { void query.refetch(); }} />;
  }
  if (query.data === undefined) return <>{skeleton}</>;
  return <>{children(query.data)}</>;
}
