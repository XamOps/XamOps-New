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

export const fetchTicketCallHistory = async (ticketId: number): Promise<CallSessionDto[]> =>
  apiClient.get(`${BASE}/ticket/${ticketId}`);

export const fetchMissedCalls = async (): Promise<MissedCallEntry[]> =>
  apiClient.get(`${BASE}/missed`);
