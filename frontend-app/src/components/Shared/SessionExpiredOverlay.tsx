/**
 * An honest interstitial for a mid-flow session expiry (BUG-3).
 *
 * When a data call returns 401 mid-session, `apiClient` dispatches a `session-expired` window event
 * (utils/apiClient.ts) and `AuthContext` redirects to `/login` (contexts/AuthContext.tsx) — that
 * redirect stays the source of truth. The defect this overlay fixes is the near-blank frame a page
 * (e.g. the EKS / Automation page) shows for the beat between the 401 settling and the full-document
 * navigation painting: the section's own reads reject and the page has nothing left to show.
 *
 * Mounted in a page shell, this listens for the same `session-expired` event and, once it fires,
 * covers the page with a centered, honest card ("Your session expired — taking you to sign in…") with
 * an explicit "Sign in again" link to `/login`. It is additive and visual only: it never changes the
 * 401 handling or the redirect, it only guarantees the beat before redirect is never blank.
 */
import { useEffect, useState } from 'react';

/** The window event `apiClient` dispatches on a mid-session 401. */
export const SESSION_EXPIRED_EVENT = 'session-expired';

export const SESSION_EXPIRED_HEADING = 'Your session expired';
export const SESSION_EXPIRED_BODY = 'Taking you to sign in…';
export const SESSION_EXPIRED_LINK = 'Sign in again';

export function SessionExpiredOverlay() {
  const [expired, setExpired] = useState(false);

  useEffect(() => {
    const onExpired = () => setExpired(true);
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired);
  }, []);

  if (!expired) return null;

  return (
    <div
      role="alert"
      aria-live="assertive"
      className="fixed inset-0 z-50 flex items-center justify-center bg-[rgba(15,23,42,0.45)] p-6"
      data-session-expired-overlay=""
    >
      <div className="w-full max-w-md rounded-xl bg-white p-8 text-center shadow-xl">
        <h1 className="text-xl font-semibold text-gray-900">{SESSION_EXPIRED_HEADING}</h1>
        <p className="mt-2 text-sm text-gray-600">{SESSION_EXPIRED_BODY}</p>
        <a
          href="/login"
          className="mt-6 inline-flex items-center justify-center rounded-lg bg-indigo-600 px-5 py-2 text-sm font-semibold text-white hover:bg-indigo-700"
        >
          {SESSION_EXPIRED_LINK}
        </a>
      </div>
    </div>
  );
}

export default SessionExpiredOverlay;
