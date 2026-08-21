/**
 * REST surface for ticket calls.
 *
 * Call control deliberately runs over REST rather than the socket: these
 * operations need real status codes (notably 409 when someone else answered
 * first) and they return join tokens. The socket only carries the ring.
 */

import apiClient from './apiClient';
import type {
  AcceptOutcome,
  CallMediaKind,
  CallSessionDto,
  CallTokenResponse,
  InvitableUser,
  MissedCallEntry,
} from '../types/calls';

const BASE = '/api/xamops/calls';

/**
 * Pulls the human-readable part out of an apiClient error.
 *
 * apiClient flattens failures into `HTTP 409: {"message":"..."}`, which is fine
 * in a console and useless in a toast. Falls back to the raw text when the body
 * is not our JSON error shape.
 */
export const readableCallError = (err: unknown, fallback: string): string => {
  if (!(err instanceof Error)) return fallback;

  const jsonStart = err.message.indexOf('{');
  if (jsonStart !== -1) {
    try {
      const parsed = JSON.parse(err.message.slice(jsonStart));
      if (parsed && typeof parsed.message === 'string' && parsed.message.trim()) {
        return parsed.message;
      }
    } catch {
      /* not JSON after all */
    }
  }
  return err.message || fallback;
};

/** Short-lived token the browser presents when opening the call socket. */
export const fetchWsToken = async (): Promise<{
  token: string;
  ttlSeconds: number;
  userId: number;
  /**
   * False on environments with no LiveKit configured. Lets the UI disable the
   * call controls instead of offering a button that fails on click.
   */
  callingEnabled?: boolean;
}> => apiClient.get(`${BASE}/ws-token`);

export const initiateCall = async (
  ticketId: number,
  mediaKind: CallMediaKind = 'AUDIO'
): Promise<CallSessionDto> => apiClient.post(`${BASE}/initiate`, { ticketId, mediaKind });

/**
 * Accepts a ringing call.
 *
 * A 409 is an expected outcome, not a failure: it means another agent answered
 * first, and the correct next move is to join rather than retry. Uses the raw
 * fetch wrapper so that status is visible instead of being flattened into a
 * thrown Error.
 */
export const acceptCall = async (callId: number): Promise<AcceptOutcome> => {
  const response = await apiClient.fetch(`${BASE}/${callId}/accept`, { method: 'POST' });

  if (response.ok) {
    return { won: true, token: (await response.json()) as CallTokenResponse };
  }

  if (response.status === 409) {
    let joinable = true;
    try {
      const body = await response.json();
      joinable = body?.joinable !== false;
    } catch {
      /* body is optional; assume joinable */
    }
    return { won: false, joinable };
  }

  // 410 means the ring expired or was cancelled between the sheet appearing and the
  // button being pressed. Not an error to report as a failure — the call is simply
  // gone, so there is nothing to join either.
  if (response.status === 410) {
    return { won: false, joinable: false };
  }

  throw new Error(`Failed to accept call (HTTP ${response.status})`);
};

export const joinCall = async (callId: number): Promise<CallTokenResponse> =>
  apiClient.post(`${BASE}/${callId}/join`);

/** Re-issues a token, for a reload mid-call or an expired one. */
export const refreshCallToken = async (callId: number): Promise<CallTokenResponse> =>
  apiClient.post(`${BASE}/${callId}/token`);

export const declineCall = async (callId: number): Promise<void> => {
  await apiClient.post(`${BASE}/${callId}/decline`);
};

export const cancelCall = async (callId: number): Promise<void> => {
  await apiClient.post(`${BASE}/${callId}/cancel`);
};

export const leaveCall = async (callId: number): Promise<void> => {
  await apiClient.post(`${BASE}/${callId}/leave`);
};

/** Users the caller may pull into a live call — role-scoped by the server. */
export const fetchInvitableUsers = async (callId: number): Promise<InvitableUser[]> =>
  apiClient.get(`${BASE}/${callId}/invitable-users`);

/** Rings the chosen users into a live call and emails them a join invite. */
export const inviteToCall = async (callId: number, userIds: number[]): Promise<void> => {
  await apiClient.post(`${BASE}/${callId}/invite`, { userIds });
};

export const fetchTicketCallHistory = async (ticketId: number): Promise<CallSessionDto[]> =>
  apiClient.get(`${BASE}/ticket/${ticketId}`);

export const fetchMissedCalls = async (): Promise<MissedCallEntry[]> =>
  apiClient.get(`${BASE}/missed`);

/**
 * The call this user is currently in, if any.
 *
 * Lets a reload put someone back where they were. The browser used to hold the
 * only record of "I am on a call", so refreshing mid-conversation dropped the UI
 * while the server still counted the user as occupying the room — they were
 * present, silent, and unable to see or leave it.
 *
 * 204 means "not on a call", which is the common case and not an error.
 */
export const fetchActiveCall = async (): Promise<CallSessionDto | null> => {
  const response = await apiClient.fetch(`${BASE}/active`, { method: 'GET' });
  if (response.status === 204 || !response.ok) return null;
  try {
    return (await response.json()) as CallSessionDto;
  } catch {
    return null;
  }
};

/**
 * Tells the server we are still in the room.
 *
 * The server cannot otherwise distinguish a live conversation from a browser that
 * died, and without that distinction an abandoned call stays open forever and
 * blocks every future call on the same ticket.
 *
 * @returns false once the server no longer considers us present — the client's cue
 *          to stop beating and reconcile, rather than pinning a call open
 */
export const sendHeartbeat = async (callId: number): Promise<boolean> => {
  try {
    const response = await apiClient.fetch(`${BASE}/${callId}/heartbeat`, { method: 'POST' });
    // Only an explicit 409 means "you are not in this call". A network blip or a 5xx
    // must not be read as eviction, or a momentary outage would end live calls.
    return response.status !== 409;
  } catch {
    return true;
  }
};

/**
 * Best-effort "I'm gone" as the page unloads.
 *
 * A normal fetch is abandoned when the document goes away, so closing a tab left
 * the server believing the user was still in the room. sendBeacon survives
 * unload — the browser hands the request to the network stack and keeps it alive
 * independently of the page.
 *
 * Cookies ride along on same-origin beacons and CSRF is disabled on this service,
 * so no headers are needed — which is just as well, since sendBeacon cannot set
 * any. The heartbeat sweeper is still the authority; this only makes teardown
 * immediate in the common case instead of taking a couple of minutes.
 */
export const beaconLeaveCall = (callId: number): void => {
  // Always /leave; the server works out whether that cancels an unanswered call or
  // simply removes us, which it is better placed to decide than an unloading page.
  const path = `${BASE}/${callId}/leave`;
  try {
    if (navigator.sendBeacon) {
      navigator.sendBeacon(path);
      return;
    }
  } catch {
    /* fall through to keepalive fetch */
  }
  try {
    // Older Safari has no sendBeacon; keepalive gives fetch the same survive-unload
    // property, and failing silently is correct here either way.
    void fetch(path, { method: 'POST', credentials: 'include', keepalive: true });
  } catch {
    /* nothing further we can do as the page dies */
  }
};
