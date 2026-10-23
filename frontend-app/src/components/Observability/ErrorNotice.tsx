import React from 'react';
import { Icon } from '@iconify/react';

/**
 * The single way observability surfaces a failed request.
 *
 * Every read in this feature used to swallow its error and return an empty collection, so a
 * backend that was down, a query that was rejected, and a stream that genuinely had no
 * records all rendered as the same "nothing here" empty state. That is the difference
 * between a tool a user trusts and one they stop believing — an empty error state quietly
 * tells them everything is fine.
 *
 * `retryable` mirrors the flag the backend already sets on infrastructure failures (503), so
 * a transient outage offers a retry instead of looking like a permanent condition.
 */
const ErrorNotice: React.FC<{
  message: string;
  /** Short prefix describing what failed, e.g. "Log search failed". */
  title?: string;
  retryable?: boolean;
  onRetry?: () => void;
  /**
   * Set while a retry is in flight. Some retries do real work on the server (re-provisioning
   * an account, for one) and take long enough that an unchanged button reads as broken and
   * gets clicked again.
   */
  retrying?: boolean;
  onDismiss?: () => void;
  className?: string;
}> = ({ message, title, retryable, onRetry, retrying = false, onDismiss, className = '' }) => (
  <div
    role="alert"
    className={`rounded-xl border border-red-200 bg-red-50 px-4 py-3 flex items-start gap-3 ${className}`}
  >
    <Icon icon="mdi:alert-circle-outline" width={18} className="mt-0.5 text-red-600 flex-shrink-0" />
    <div className="min-w-0 flex-1">
      {title && <p className="text-[13px] font-semibold text-red-900">{title}</p>}
      <p className={`text-[12px] text-red-800 break-words ${title ? 'mt-0.5' : ''}`}>{message}</p>
      {(onRetry || onDismiss) && (
        <div className="mt-2 flex items-center gap-3">
          {onRetry && (
            <button
              type="button"
              onClick={onRetry}
              disabled={retrying}
              aria-busy={retrying}
              className="text-[12px] font-medium text-red-700 hover:text-red-900 inline-flex items-center gap-1 disabled:opacity-60 disabled:cursor-not-allowed"
            >
              <Icon
                icon="mdi:refresh"
                width={13}
                className={retrying ? 'animate-spin' : undefined}
              />{' '}
              {retrying ? 'Retrying…' : retryable ? 'Retry' : 'Try again'}
            </button>
          )}
          {onDismiss && (
            <button
              type="button"
              onClick={onDismiss}
              className="text-[12px] font-medium text-red-700/70 hover:text-red-900"
            >
              Dismiss
            </button>
          )}
        </div>
      )}
    </div>
  </div>
);

export default ErrorNotice;
