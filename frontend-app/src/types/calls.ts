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
  direction?: CallDirection;
  mediaKind?: CallMediaKind;
  initiatorUserId?: number;
  initiatorName?: string;
  answeredByUserId?: number;
  answeredByName?: string;
  ringExpiresInSeconds?: number;
  activeParticipants?: number;
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
}

export interface CallTokenResponse {
  livekitUrl: string;
  token: string;
  roomName: string;
  identity: string;
  callId: number;
}

export interface MissedCallEntry {
  callId: number;
  participantId: number;
  ticketId?: number;
  initiatorName?: string;
  at?: string;
  direction?: CallDirection;
}

/** Result of accepting: either we won the race, or we may join instead. */
export type AcceptOutcome =
  | { won: true; token: CallTokenResponse }
  | { won: false; joinable: boolean };

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
