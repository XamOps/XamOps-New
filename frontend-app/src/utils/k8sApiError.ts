/**
 * Classifies an error thrown by {@link apiClient} into a small, UI-facing kind so the fleet and
 * cluster pages can say what went wrong without parsing message strings themselves.
 *
 * apiClient throws plain Errors with known message shapes (src/utils/apiClient.ts):
 *   - `HTTP <code>: <text>`
 *   - `API endpoint not found (HTTP <code>). ...`
 *   - `API endpoint returned HTML instead of JSON. Status: <code>`
 *   - `Request timed out after <n>ms: <url>`
 * plus the browser's own `TypeError: Failed to fetch` for a dropped connection.
 *
 * The status code is authoritative: when a 3-digit HTTP code is present we derive the kind from it
 * FIRST, so `API endpoint not found (HTTP 500). ...` is a server error, not a notFound (Finding 4).
 * Only when there is no code do we fall back to the message wording.
 */

export type ApiErrorKind = 'unauthorized' | 'notFound' | 'server' | 'network' | 'html' | 'invalid';

export interface ClassifiedApiError {
  kind: ApiErrorKind;
  status?: number;
}

const HTTP_STATUS = /HTTP (\d{3})/;

export function classifyApiError(err: unknown): ClassifiedApiError {
  const message = err instanceof Error ? err.message : typeof err === 'string' ? err : '';

  const statusMatch = message.match(HTTP_STATUS);
  if (statusMatch) {
    const status = Number(statusMatch[1]);
    if (status === 401 || status === 403) return { kind: 'unauthorized', status };
    if (status === 404) return { kind: 'notFound', status };
    if (status >= 500 && status <= 599) return { kind: 'server', status };
    // Any other code with a status is reported with its status, treated as a server-side failure.
    return { kind: 'server', status };
  }

  if (message.startsWith('Request timed out')) return { kind: 'network' };
  if (err instanceof TypeError || message.includes('Failed to fetch')) return { kind: 'network' };
  if (message.includes('returned HTML instead of JSON')) return { kind: 'html' };

  return { kind: 'server' };
}

export default classifyApiError;
