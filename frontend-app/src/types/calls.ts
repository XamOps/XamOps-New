/**
 * Live calling on support tickets.
 *
 * Mirrors the signal envelope pushed by xamops-service over STOMP. The important
 * distinction is INCOMING (ring me) versus JOINABLE (someone else answered, but
 * the room is still open to you) — that pair is what makes a group ring behave
 * like a call queue rather than a race everyone loses.
 */

export type CallSignalType =
  | 'INCOMING'
  | 'RINGING'
  /**
   * The loud ring window elapsed; the call is now holding quietly in the queue.
   * Recipients stop ringing but keep it answerable, and the caller is reassured
   * rather than cut off.
   */
  | 'QUEUED'
  | 'ANSWERED'
  | 'JOINABLE'
  | 'PARTICIPANT_CHANGED'
  | 'ENDED'
  | 'MISSED'
  | 'CANCELLED'
  | 'DECLINED'
  | 'NO_ANSWER_AVAILABLE';

export type CallDirection = 'USER_TO_ADMIN' | 'ADMIN_TO_USER';
export type CallMediaKind = 'AUDIO' | 'VIDEO';
export type CallState =
  | 'RINGING'
  | 'ACTIVE'
  | 'ENDED'
  | 'MISSED'
  | 'DECLINED'
  | 'CANCELLED';

export interface CallSignal {
  type: CallSignalType;
  callId: number;
  roomName?: string;
  ticketId?: number;
  ticketSubject?: string;
  /**
   * Ticket category and severity, so whoever is being rung can judge the call
   * before answering. Routing is unaffected — the whole pool is rung either way —
   * but a Sev-1 production incident and a billing question should not look
   * identical on the ring sheet.
   */
  ticketCategory?: string;
  ticketSeverity?: string;
  direction?: CallDirection;
  mediaKind?: CallMediaKind;
  initiatorUserId?: number;
  initiatorName?: string;
  answeredByUserId?: number;
  answeredByName?: string;
  ringExpiresInSeconds?: number;
  /**
   * On RINGING, how many agents can actually pick up right now — online *and* not
   * already on another call. Deliberately not just "tabs open": telling a customer
   * three agents are ringing when all three are mid-call promises a queue that
   * cannot deliver.
   */
  activeParticipants?: number;
  /**
   * On INCOMING, true when this ring reached someone already on another call.
   *
   * Decided server-side, because a browser only knows what *its own tab* is doing —
   * which is why a second tab used to ring at full volume for a user who was already
   * mid-conversation.
   */
  waiting?: boolean;
  reason?: string;
  at?: string;
}

export interface CallSessionDto {
  id: number;
  roomName: string;
  ticketId: number;
  direction: CallDirection;
  state: CallState;
  mediaKind: CallMediaKind;
  initiatorUserId: number;
  initiatorName: string;
  answeredByUserId?: number;
  answeredByName?: string;
  createdAt: string;
  answeredAt?: string;
  endedAt?: string;
  endReason?: string;
  ringExpiresInSeconds?: number;
  /** True once the loud ring window passed and the call is holding quietly. */
  queued?: boolean;
  /** Seconds before the caller stops holding and this becomes a missed call. */
  queueExpiresInSeconds?: number;
  /** How long the caller has already been holding. */
  waitingSeconds?: number;
}

export interface CallTokenResponse {
  livekitUrl: string;
  token: string;
  roomName: string;
  identity: string;
  callId: number;
}

/** A user the caller may pull into a live call (scoped by role, server-side). */
export interface InvitableUser {
  userId: number;
  name: string;
  email?: string;
  /** "ADMIN" for BillOps admins, "CLIENT_USER" otherwise. */
  kind: 'ADMIN' | 'CLIENT_USER';
}

export interface MissedCallEntry {
  callId: number;
  participantId: number;
  ticketId?: number;
  initiatorName?: string;
  at?: string;
  direction?: CallDirection;
}

/**
 * Result of accepting: we won the race, or we may join instead, or we are already
 * on another call.
 *
 * The last case is not a race at all and must not be retried as a join — the server
 * refuses both for the same reason, because one person cannot be in two rooms.
 */
export type AcceptOutcome =
  | { won: true; token: CallTokenResponse }
  | { won: false; joinable: boolean; alreadyOnCall?: boolean };

/** What the local UI is currently doing about a call. */
export type LocalCallPhase =
  | 'idle'
  /** We started a call and are waiting for someone to pick up. */
  | 'outgoing'
  /** Someone is ringing us. */
  | 'incoming'
  /** We are connecting to the LiveKit room. */
  | 'connecting'
  /** We are in the room. */
  | 'in-call';
