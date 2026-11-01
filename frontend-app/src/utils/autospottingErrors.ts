/**
 * Error helpers for the AutoSpotting pages (AWS and GCP).
 *
 * apiClient throws `Error("HTTP <status>: <raw body>")`. The AutoSpotting controllers answer
 * typed refusals with `{ success: false, code, error }` (for example 409
 * AUTOSPOTTING_CONVERSIONS_PAUSED). Showing the raw message leaked the HTTP envelope and a
 * JSON blob into toasts, so the envelope is unwrapped here, the same way extractApiError in
 * hooks/useObservabilityData.ts does it.
 */

export const AUTOSPOTTING_CONVERSIONS_PAUSED = 'AUTOSPOTTING_CONVERSIONS_PAUSED';

/**
 * The B3 refusal the legacy AWS mutating endpoints now return (enable, apply config with apply=true,
 * register, update config): a conversion runs only through a confirmed v2 plan (design-core.md §7.4,
 * AC-PLN-07; plan-phase-11.md task 11.5).
 */
export const PLAN_REQUIRED = 'PLAN_REQUIRED';

export const PAUSED_MESSAGE =
  'New Spot conversions are paused while AutoSpotting is being upgraded. Groups already on Spot keep running, and you can still switch them back to On-Demand.';

/**
 * What a PLAN_REQUIRED refusal shows on the legacy pages once the AutoSpotting shell is on
 * (autospotting.ui.shell-enabled true, Phase 14): point the user at the Review-plan flow. While the
 * shell is off, PLAN_REQUIRED shows the same paused notice as before, so users see no new message.
 */
export const REVIEW_PLAN_MESSAGE = 'Use Review plan in AutoSpotting to make this change.';

const SESSION_EXPIRED_MESSAGE = 'Your session has expired. Sign in again.';
const NO_ACCESS_MESSAGE = "You don't have access to this account, or it isn't connected to XamOps.";
const DEFAULT_FALLBACK = 'Request failed. Try again.';

export interface AutoSpottingError {
  status?: number;
  code?: string;
  message: string;
}

export interface ParseOptions {
  /** The fallback message when nothing else matches. */
  fallback?: string;
  /** Whether the AutoSpotting shell is on (autospotting.ui.shell-enabled). Changes PLAN_REQUIRED copy. */
  shellEnabled?: boolean;
}

export function parseAutoSpottingError(
  e: unknown,
  fallbackOrOptions: string | ParseOptions = DEFAULT_FALLBACK,
): AutoSpottingError {
  const options: ParseOptions =
    typeof fallbackOrOptions === 'string' ? { fallback: fallbackOrOptions } : fallbackOrOptions;
  const fallback = options.fallback ?? DEFAULT_FALLBACK;
  const shellEnabled = options.shellEnabled ?? false;

  if (!(e instanceof Error)) return { message: fallback };

  const match = /^HTTP (\d+): ([\s\S]*)$/.exec(e.message);
  if (!match) {
    // Client-side failure (timeout, network, abort): the message is already readable.
    return { message: e.message || fallback };
  }

  const status = Number(match[1]);
  const body = match[2].trim();
  let code: string | undefined;
  let message = '';
  let isJson = false;

  if (body.startsWith('{')) {
    try {
      const parsed = JSON.parse(body) as { code?: unknown; error?: unknown; message?: unknown };
      isJson = true;
      if (typeof parsed.code === 'string' && parsed.code) code = parsed.code;
      if (typeof parsed.error === 'string' && parsed.error) message = parsed.error;
      else if (typeof parsed.message === 'string') message = parsed.message;
    } catch {
      // Not JSON after all: fall through to the plain-text handling.
    }
  }

  if (code === AUTOSPOTTING_CONVERSIONS_PAUSED) return { status, code, message: PAUSED_MESSAGE };
  if (code === PLAN_REQUIRED) {
    // While the shell is off, show the same paused notice as before (no new message); once the shell
    // is on, point the user at the Review-plan flow (plan-phase-11.md task 11.5).
    return { status, code, message: shellEnabled ? REVIEW_PLAN_MESSAGE : PAUSED_MESSAGE };
  }
  if (status === 401) return { status, code, message: SESSION_EXPIRED_MESSAGE };
  if (status === 403) return { status, code, message: NO_ACCESS_MESSAGE };

  if (!message && !isJson) {
    const looksLikeHtml = /^\s*<(!doctype|html|head|body)\b/i.test(body);
    if (!looksLikeHtml && body.length > 0 && body.length <= 2000) message = body;
  }
  if (!message) message = `${fallback} (HTTP ${status})`;

  return { status, code, message };
}

export function isConversionsPausedError(e: unknown): boolean {
  return parseAutoSpottingError(e).code === AUTOSPOTTING_CONVERSIONS_PAUSED;
}
