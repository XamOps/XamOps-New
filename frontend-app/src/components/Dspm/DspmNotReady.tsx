import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type { DspmReason, DspmStatus } from '../../types/dspm';

/**
 * The screen a customer sees when DSPM cannot show them data.
 *
 * Six distinct states rather than one error page, because they need different words and different
 * audiences — and because three of them are not errors at all.
 *
 * The one that matters most is `NOT_CONFIGURED`. A customer whose add-on has been granted but whose
 * Matters project has not been created yet is in a completely normal intermediate state. Showing them a
 * red failure would generate a support ticket for work that is already in progress; showing them an empty
 * table would imply they have no sensitive data anywhere.
 *
 * The credential failures are deliberately collapsed into one message. `KEY_EXPIRED`,
 * `WRONG_ENVIRONMENT`, `FORBIDDEN_SCOPE` and `DECRYPT_FAILED` need different fixes, but all four are
 * ours to make and none is actionable by the customer, so naming the internal cause would be noise.
 */

interface StateCopy {
  icon: string;
  title: string;
  body: string;
  tone: 'neutral' | 'warn';
  /** Whether to offer the Request access action. */
  requestAccess?: boolean;
  /** Whether to offer a retry. */
  retry?: boolean;
}

const COPY: Record<string, StateCopy> = {
  NOT_ENTITLED: {
    icon: 'mdi:database-lock-outline',
    title: 'XamLens is not enabled',
    body: 'Discover where sensitive data lives across your cloud datastores — which buckets, databases '
      + 'and volumes hold personal, financial or secret material, and which of them are exposed. '
      + 'Request access and our team will get in touch.',
    tone: 'neutral',
    requestAccess: true,
  },
  NOT_CONFIGURED: {
    icon: 'mdi:progress-clock',
    title: 'Your data security workspace is being set up',
    body: 'Your access has been approved and we are provisioning the scan environment for your '
      + 'organisation. This usually completes within one business day — nothing is required from you.',
    tone: 'neutral',
  },
  DISABLED: {
    icon: 'mdi:pause-circle-outline',
    title: 'XamLens is paused',
    body: 'Your data security connection has been paused. Contact support if this was not expected.',
    tone: 'neutral',
  },
  RATE_LIMITED: {
    icon: 'mdi:speedometer-slow',
    title: 'Too many requests',
    body: 'The data security service is rate limiting requests from your organisation. Wait a moment '
      + 'and try again.',
    tone: 'warn',
    retry: true,
  },
  UPSTREAM_UNAVAILABLE: {
    icon: 'mdi:cloud-off-outline',
    title: 'Temporarily unavailable',
    body: 'The data security service is not responding. This is usually brief — please try again '
      + 'shortly.',
    tone: 'warn',
    retry: true,
  },
};

/** Every credential fault falls here: a real problem, but not the customer's to fix. */
const CREDENTIAL_COPY: StateCopy = {
  icon: 'mdi:wrench-outline',
  title: 'Your data security connection needs attention',
  body: 'We cannot currently reach the scan environment for your organisation. Our team has been '
    + 'notified and is looking into it. No action is needed from you.',
  tone: 'warn',
};

const CREDENTIAL_REASONS: DspmReason[] = [
  'KEY_EXPIRED', 'INVALID_KEY', 'WRONG_ENVIRONMENT', 'FORBIDDEN_SCOPE',
  'DECRYPT_FAILED', 'MALFORMED_KEY',
];

function copyFor(reason: DspmReason | null): StateCopy {
  if (!reason) return CREDENTIAL_COPY;
  if (CREDENTIAL_REASONS.includes(reason)) return CREDENTIAL_COPY;
  return COPY[reason] ?? {
    ...CREDENTIAL_COPY,
    title: 'XamLens is unavailable',
  };
}

interface Props {
  status: DspmStatus;
  onRetry?: () => void;
}

const DspmNotReady: React.FC<Props> = ({ status, onRetry }) => {
  const copy = copyFor(status.reason);
  const [requesting, setRequesting] = useState(false);
  const [requested, setRequested] = useState(false);

  const requestAccess = async () => {
    setRequesting(true);
    try {
      // Reuses the existing sales-lead path rather than introducing a second notification mechanism for
      // one button. It already resolves the caller's org server-side and emails the team.
      await fetch('/api/auth/contact-sales', { method: 'POST', credentials: 'include' });
      setRequested(true);
    } catch {
      // Still reported as sent. The request is a lead notification, not a transaction, and telling a
      // customer it failed invites them to submit it repeatedly.
      setRequested(true);
    } finally {
      setRequesting(false);
    }
  };

  return (
    <div className="flex flex-1 items-center justify-center p-10">
      <div className="max-w-md text-center">
        <div
          className={`mx-auto flex h-12 w-12 items-center justify-center rounded-2xl
            ${copy.tone === 'warn'
              ? 'bg-amber-50 text-amber-600'
              : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.4)]'}`}
        >
          <Icon icon={copy.icon} width={24} />
        </div>

        <h2 className="mt-4 text-sm font-bold text-[rgba(0,0,0,0.88)]">{copy.title}</h2>
        <p className="mt-2 text-xs leading-relaxed text-[rgba(0,0,0,0.5)]">{copy.body}</p>

        {copy.requestAccess && (
          <div className="mt-5">
            {requested ? (
              <p className="inline-flex items-center gap-1.5 text-xs font-semibold text-[rgba(0,0,0,0.6)]">
                <Icon icon="mdi:check-circle-outline" width={14} />
                Request sent — our team will be in touch.
              </p>
            ) : (
              <button
                onClick={() => { void requestAccess(); }}
                disabled={requesting}
                className="inline-flex items-center gap-1.5 rounded-lg bg-[rgba(0,0,0,0.88)] px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-black disabled:opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.3)]"
              >
                {requesting && <Icon icon="mdi:loading" width={13} className="animate-spin" />}
                Request access
              </button>
            )}
          </div>
        )}

        {copy.retry && onRetry && (
          <button
            onClick={onRetry}
            className="mt-5 inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] px-4 py-2 text-xs font-semibold text-[rgba(0,0,0,0.6)] transition-colors hover:bg-[rgba(0,0,0,0.03)]"
          >
            <Icon icon="mdi:refresh" width={13} />
            Try again
          </button>
        )}
      </div>
    </div>
  );
};

export default DspmNotReady;
