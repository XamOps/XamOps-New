/**
 * What the Datastores page and drawer say when a request fails, by the backend's error `code`.
 *
 * Branches on the code only; the backend's message is prose and is never shown. `retry` says whether a
 * Retry action makes sense: it does for transient failures, not for a missing datastore or a
 * configuration problem that only our team can fix.
 */

import type { DspmError } from '../../../types/dspm';

export interface DatastoreErrorCopy {
  text: string;
  retry: boolean;
}

const UPSTREAM: DatastoreErrorCopy = { text: 'Matters is not responding right now. Try again shortly.', retry: true };

const CONNECTION: DatastoreErrorCopy = {
  text: 'Your data security connection needs attention. Our team has been notified.',
  retry: false,
};

/** Key and configuration faults: real problems, but not the customer's to fix. */
const CONNECTION_CODES: ReadonlySet<string> = new Set([
  'NOT_ENTITLED', 'NOT_CONFIGURED', 'DISABLED',
  'KEY_EXPIRED', 'INVALID_KEY', 'WRONG_ENVIRONMENT', 'DECRYPT_FAILED', 'MALFORMED_KEY',
]);

export function datastoreErrorCopy(error: Pick<DspmError, 'code' | 'retryAfterSeconds'>): DatastoreErrorCopy {
  switch (error.code) {
    case 'ASSET_NOT_FOUND':
      return { text: 'This datastore could not be found. It may have been removed.', retry: false };
    case 'FORBIDDEN_SCOPE':
      return { text: 'This Matters key needs the PLATFORM scope.', retry: false };
    case 'RATE_LIMITED':
      return {
        text: error.retryAfterSeconds != null
          ? `Too many requests. Try again in ${error.retryAfterSeconds} seconds.`
          : 'Too many requests. Try again in a minute.',
        retry: true,
      };
    case 'UPSTREAM_UNAVAILABLE':
    case 'BAD_UPSTREAM_RESPONSE':
      return UPSTREAM;
    case 'TAB_ACCESS_DENIED':
      return { text: "You don't have access to XamLens.", retry: false };
    case 'INVALID_REQUEST':
      return { text: 'This request could not be processed.', retry: false };
    default:
      // An unknown code is treated like an outage: retrying is harmless and the message stays true.
      return CONNECTION_CODES.has(error.code) ? CONNECTION : UPSTREAM;
  }
}
