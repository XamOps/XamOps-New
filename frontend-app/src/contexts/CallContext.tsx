/**
 * Live calling on support tickets.
 *
 * Owns the signalling socket and the client-side call state. Mounted once for the
 * whole authenticated app rather than per page, because a call has to be able to
 * reach an admin wherever they happen to be — ringing only on the tickets page
 * would mean most calls are never seen.
 *
 * Media itself is LiveKit's problem; nothing here touches WebRTC. This layer
 * decides who is ringing, who answered, and which room to attach to.
 */

import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  ReactNode,
} from 'react';
import { gooeyToast } from 'goey-toast';
import { useAuth } from './AuthContext';
import { ringtone } from '../utils/ringtone';
import { showNotification, initNotificationService } from '../utils/notificationService';
import {
  acceptCall,
  beaconLeaveCall,
  declineCall,
  fetchActiveCall,
  fetchCallQueue,
  fetchMissedCalls,
  fetchWsToken,
  initiateCall,
  joinCall,
  leaveCall,
  readableCallError,
  refreshCallToken,
  sendHeartbeat,
} from '../utils/callsApi';
import type {
  CallMediaKind,
  CallSignal,
  CallTokenResponse,
  LocalCallPhase,
  MissedCallEntry,
} from '../types/calls';

/**
 * Presence refresh, kept well inside the server's TTL (default 300s).
 *
 * The server TTL was reduced from 900s because a hard browser kill produces no
 * clean disconnect, and until it lapses a dead tab still looks ringable — a
 * customer could wait out an entire ring window on a ghost while real agents were
 * free. 120s here leaves room for one dropped ping plus a reconnect.
 */
const PRESENCE_PING_MS = 2 * 60 * 1000;

/**
 * Liveness while in a call.
 *
 * This is what lets the server tell an ongoing conversation from a browser that
 * died. It must stay far below the server's staleness window (default 120s) so
 * that a socket reconnect or a slow request never reads as an abandoned room.
 */
const HEARTBEAT_MS = 15 * 1000;

const RECONNECT_BASE_MS = 3000;
const RECONNECT_MAX_MS = 30000;

/**
 * How long a "your call went unanswered" toast stays up.
 *
 * The library gives a described toast 4s, which is fine for an acknowledgement
 * and not fine here: this is the only place the caller is told what became of a
 * call they waited out, and it lands right as they look away from the queue
 * panel that just vanished. Long enough to read twice, still short enough to
 * expire on its own.
 */
const NO_ANSWER_TOAST_MS = 12000;

/**
 * Missed calls the user has already seen and cleared. Persisted locally because
 * the server has no per-user "read" state — a missed call stays in the record
 * forever, so without this the badge would only ever grow. Dismissal is a
 * client-side convenience, not a state that needs to survive across devices.
 */
const DISMISSED_MISSED_KEY = 'xamops.calls.dismissedMissed';

/**
 * Cross-tab channel for "this user is on a call".
 *
 * Each tab mounts its own provider with its own state, so a call answered in one
 * tab was invisible to every other tab. A second ring would then arrive at a tab
 * that believed the user was free and interrupt at full volume — and answering it
 * put the same person into two rooms with both audio streams live.
 *
 * The server now refuses the second call outright, so this is not the safety
 * mechanism; it is what makes the *experience* right, by letting the other tabs
 * show a waiting card instead of a ring they must not act on.
 */
const TAB_SYNC_CHANNEL = 'xamops.calls.tabSync';

type TabSyncMessage =
  | { kind: 'in-call'; callId: number }
  | { kind: 'free' }
  /** Sent by a newly opened tab so an existing in-call tab re-announces itself. */
  | { kind: 'who-is-on-a-call' };

const loadDismissedMissed = (): number[] => {
  try {
    const raw = localStorage.getItem(DISMISSED_MISSED_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((n): n is number => typeof n === 'number') : [];
  } catch {
    return [];
  }
};

const persistDismissedMissed = (ids: number[]) => {
  try {
    // Cap the stored set so it cannot grow without bound as calls accumulate.
    localStorage.setItem(DISMISSED_MISSED_KEY, JSON.stringify(ids.slice(-500)));
  } catch {
    /* storage full or unavailable — dismissal simply won't persist this session */
  }
};

interface CallContextValue {
  phase: LocalCallPhase;
  /** A call ringing at us right now. */
  incoming: CallSignal | null;
  /** A call we started that has not been answered yet. */
  outgoing: CallSignal | null;
  /** Live calls someone else answered that we are still welcome to join. */
  joinable: CallSignal[];
  /**
   * Calls ringing at us right now that we cannot take because we are already on
   * one. Distinct from `joinable`: nobody has answered these, they are still
   * counting down, and if we ignore them they become missed calls.
   */
  waitingCalls: CallSignal[];
  activeToken: CallTokenResponse | null;
  activeCall: CallSignal | null;
  /**
   * Media mode fixed at the moment we entered the room. Deliberately not derived
   * from `activeCall`, which arrives later and would otherwise flip the room's
   * video setting mid-call and force a reconnect.
   */
  activeMediaKind: CallMediaKind;
  socketConnected: boolean;
  /** False when the environment has no LiveKit — call controls should be disabled. */
  callingEnabled: boolean;

  /** Missed calls that reached us and have not been cleared yet, newest first. */
  missedCalls: MissedCallEntry[];

  startCall: (ticketId: number, mediaKind?: CallMediaKind) => Promise<void>;
  acceptIncoming: () => Promise<void>;
  declineIncoming: () => Promise<void>;
  joinExisting: (callId: number) => Promise<void>;
  hangUp: () => Promise<void>;
  dismissJoinable: (callId: number) => void;
  /**
   * Takes a waiting call. If we are still on another one, that call is ended first
   * — a person can only be in one conversation, and the alternative (two live
   * rooms) is the bug this replaced.
   */
  answerWaiting: (callId: number) => Promise<void>;
  /** Declines a waiting call, so its caller is told rather than left ringing. */
  declineWaiting: (callId: number) => Promise<void>;
  refreshMissedCalls: () => Promise<void>;
  /** Marks one missed call as seen; it drops off the badge. */
  dismissMissedCall: (participantId: number) => void;
  /** Clears every currently visible missed call at once. */
  clearMissedCalls: () => void;
}

const CallContext = createContext<CallContextValue | undefined>(undefined);

export const CallProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const { authReady, userRoles } = useAuth();

  const [phase, setPhase] = useState<LocalCallPhase>('idle');
  const [incoming, setIncoming] = useState<CallSignal | null>(null);
  const [outgoing, setOutgoing] = useState<CallSignal | null>(null);
  const [joinable, setJoinable] = useState<CallSignal[]>([]);
  const [waitingCalls, setWaitingCalls] = useState<CallSignal[]>([]);
  const [activeToken, setActiveToken] = useState<CallTokenResponse | null>(null);
  const [activeCall, setActiveCall] = useState<CallSignal | null>(null);
  const [activeMediaKind, setActiveMediaKind] = useState<CallMediaKind>('AUDIO');
  const [socketConnected, setSocketConnected] = useState(false);
  // Assume available until the server says otherwise, so buttons do not flicker
  // disabled on first paint before the token request returns.
  const [callingEnabled, setCallingEnabled] = useState(true);
  const [missedRaw, setMissedRaw] = useState<MissedCallEntry[]>([]);
  const [dismissedMissed, setDismissedMissed] = useState<number[]>(() => loadDismissedMissed());

  const stompRef = useRef<any>(null);
  // stompRef is only set once the handshake completes, so it cannot by itself
  // stop a second attempt starting during the async token fetch — which React
  // Strict Mode's double effect invocation reliably triggers.
  const connectingRef = useRef(false);
  const pingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const reconnectRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const attemptRef = useRef(0);
  const mountedRef = useRef(true);
  // Signals arrive on a socket callback that closes over stale state, so the
  // current call id is mirrored into a ref for the handler to read.
  const activeCallIdRef = useRef<number | null>(null);
  const incomingIdRef = useRef<number | null>(null);
  // A MISSED signal fires from a socket callback that closed over an early
  // render; the refresh function is defined later, so it is reached through a
  // ref rather than captured directly.
  const refreshMissedRef = useRef<() => void>(() => undefined);
  // The server pushes RINGING from inside `initiate`, so it reaches this tab
  // before the HTTP response tells us our own call id. Parked here and applied
  // when the room opens — it carries the ticket context and the count of agents
  // actually being rung, which is what the waiting UI has to show.
  const pendingRingingRef = useRef<CallSignal | null>(null);
  // Which call we placed and are still waiting on. `phase` cannot answer this
  // from inside a socket callback, and an unanswered outgoing call needs a
  // different ending than a call that rang at us and we missed.
  const outgoingIdRef = useRef<number | null>(null);
  const heartbeatRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // Reload recovery runs once per session; without this guard a re-render that
  // changes the auth object would re-enter a call the user had just hung up.
  const recoveryAttemptedRef = useRef(false);
  // Reload recovery is declared above `enterRoom` so it can run as early as
  // possible after auth settles, so it reaches the function through a ref.
  const enterRoomRef = useRef<
    (token: CallTokenResponse, signal: CallSignal | null, mediaKind: CallMediaKind) => void
  >(() => undefined);
  /**
   * The call another tab of ours is on, if any.
   *
   * Read when a ring arrives to decide between interrupting and showing a waiting
   * card. A ref rather than state because the socket callback that needs it closes
   * over stale renders.
   */
  const otherTabCallIdRef = useRef<number | null>(null);
  const tabSyncRef = useRef<BroadcastChannel | null>(null);
  /**
   * Loads the standing queue. Reached through a ref because the socket's connect
   * callback is created before the loader is defined.
   */
  const loadQueueRef = useRef<() => Promise<void>>(async () => undefined);

  const isAuthenticated = authReady && userRoles.length > 0;

  const stopRinging = useCallback(() => {
    ringtone.stop();
  }, []);

  const clearCall = useCallback(() => {
    stopRinging();
    setPhase('idle');
    setIncoming(null);
    setOutgoing(null);
    setActiveToken(null);
    setActiveCall(null);
    setActiveMediaKind('AUDIO');
    activeCallIdRef.current = null;
    incomingIdRef.current = null;
    outgoingIdRef.current = null;
    pendingRingingRef.current = null;
  }, [stopRinging]);

  // ─── Signal handling ───────────────────────────────────────────────────────

  const handleSignal = useCallback(
    (signal: CallSignal) => {
      if (!mountedRef.current) return;

      switch (signal.type) {
        case 'INCOMING': {
          /**
           * A ring we cannot take right now becomes a *waiting* call, not a
           * "joinable" one.
           *
           * It used to be pushed onto `joinable`, which is for calls somebody else
           * already answered — so it rendered as "Call in progress" with a disabled
           * Join button, no caller name and no sound. A real customer was ringing and
           * the agent had no way to tell.
           *
           * Four things can make us unavailable, and the server knows about only one
           * of them, so all four are checked:
           *  - `signal.waiting`: the server sees us in a live call (covers other tabs
           *    and other devices, and is authoritative);
           *  - we are in a call in *this* tab;
           *  - we are already ringing for a different call;
           *  - another tab told us it is on a call.
           */
          const unavailable =
            signal.waiting === true ||
            activeCallIdRef.current !== null ||
            incomingIdRef.current !== null ||
            otherTabCallIdRef.current !== null;

          if (unavailable) {
            setWaitingCalls(prev =>
              prev.some(c => c.callId === signal.callId) ? prev : [...prev, signal]
            );
            // Quiet double-beep rather than the looping ring: loud enough to notice,
            // quiet enough not to talk over the call already in progress.
            ringtone.chime();
            showNotification(`Call waiting from ${signal.initiatorName ?? 'support'}`, {
              body: signal.ticketId != null ? `Ticket #${signal.ticketId}` : undefined,
              tag: `call-waiting-${signal.callId}`,
              playSound: false,
            });
            return;
          }
          incomingIdRef.current = signal.callId;
          setIncoming(signal);
          setPhase('incoming');
          ringtone.start();
          showNotification(`Incoming call from ${signal.initiatorName ?? 'support'}`, {
            body: signal.ticketSubject
              ? `Ticket #${signal.ticketId} — ${signal.ticketSubject}`
              : `Ticket #${signal.ticketId}`,
            tag: `call-${signal.callId}`,
            playSound: false, // the ringtone is already playing
          });
          break;
        }

        case 'RINGING':
          setOutgoing(signal);
          // Usually arrives before we know our own call id, in which case
          // `enterRoom` picks it up out of the ref a moment later.
          if (activeCallIdRef.current === signal.callId) setActiveCall(signal);
          else pendingRingingRef.current = signal;
          break;

        case 'ANSWERED':
          // Reaches the caller and every session belonging to the answerer.
          if (activeCallIdRef.current === signal.callId) {
            setPhase('in-call');
            setOutgoing(null);
            outgoingIdRef.current = null;
            setActiveCall(signal);
            gooeyToast.success(`${signal.answeredByName ?? 'An agent'} joined the call`);
          } else if (incomingIdRef.current === signal.callId) {
            // This tab is still ringing for a call that was answered elsewhere —
            // most often another tab of our own. Stand down silently.
            incomingIdRef.current = null;
            setIncoming(null);
            setPhase('idle');
            stopRinging();
          }
          break;

        case 'QUEUED': {
          // The ring window elapsed. Nothing has failed — the call is holding.
          if (activeCallIdRef.current === signal.callId) {
            // We are the caller. Stay in the room waiting; just refresh the description
            // so the panel can say "holding in the queue" instead of "ringing".
            setActiveCall(signal);
            setOutgoing(signal);
            break;
          }

          // We were being rung. Stop the ringtone, but keep the call in the waiting
          // list — it is still answerable for the whole queue window, and dropping it
          // here is exactly how a caller ends up ignored.
          if (incomingIdRef.current === signal.callId) {
            incomingIdRef.current = null;
            stopRinging();
            setIncoming(null);
            setPhase(activeCallIdRef.current ? 'in-call' : 'idle');
          }
          setWaitingCalls(prev =>
            prev.some(c => c.callId === signal.callId)
              ? prev.map(c => (c.callId === signal.callId ? { ...signal, waiting: true } : c))
              : [...prev, { ...signal, waiting: true }]
          );
          break;
        }

        case 'JOINABLE':
          // Someone else picked up. Stop ringing but keep a way in.
          if (incomingIdRef.current === signal.callId) {
            incomingIdRef.current = null;
            setIncoming(null);
            setPhase(activeCallIdRef.current ? 'in-call' : 'idle');
            stopRinging();
          }
          // A colleague answering it settles the question for us too: it is no longer
          // a call we are keeping someone waiting on, just a room we may drop into.
          setWaitingCalls(prev => prev.filter(c => c.callId !== signal.callId));
          setJoinable(prev =>
            prev.some(c => c.callId === signal.callId)
              ? prev.map(c => (c.callId === signal.callId ? signal : c))
              : [...prev, signal]
          );
          break;

        case 'PARTICIPANT_CHANGED':
          if (activeCallIdRef.current === signal.callId) {
            setActiveCall(signal);
          }
          break;

        case 'ENDED':
        case 'MISSED':
        case 'CANCELLED':
        case 'DECLINED':
        case 'NO_ANSWER_AVAILABLE': {
          setJoinable(prev => prev.filter(c => c.callId !== signal.callId));
          // A waiting call that ended stops waiting, whatever ended it. Left in place
          // it would be a card offering to answer a call that no longer exists.
          setWaitingCalls(prev => prev.filter(c => c.callId !== signal.callId));

          // A missed ring becomes a durable record for everyone it rang, not just
          // whichever tab happened to be showing the call — pull the fresh list so
          // the badge updates even if we were mid-navigation when it expired.
          if (signal.type === 'MISSED') {
            refreshMissedRef.current();
          }

          // A finished call leaves a summary note on its ticket. Nudge any open
          // ticket page for that ticket to reload so the note shows without a
          // manual refresh; a page that does not care simply ignores the event.
          if (signal.type === 'ENDED' && signal.ticketId != null) {
            window.dispatchEvent(
              new CustomEvent('call:ended', { detail: { ticketId: signal.ticketId } })
            );
          }

          const wasOurs =
            activeCallIdRef.current === signal.callId || incomingIdRef.current === signal.callId;
          // Read before clearCall wipes it: a call *we* placed that nobody
          // answered is not a call we missed, and saying so to a customer who
          // sat in the queue for the whole ring window reads as nonsense.
          const wasOurUnansweredOutgoing = outgoingIdRef.current === signal.callId;
          if (!wasOurs) return;

          clearCall();

          if (signal.type === 'MISSED') {
            if (wasOurUnansweredOutgoing) {
              gooeyToast.warning('Nobody picked up', {
                description:
                  'The support team has been emailed about the call and will get back to you.',
                duration: NO_ANSWER_TOAST_MS,
              });
            } else {
              gooeyToast.warning('Missed call', {
                description: signal.ticketId ? `Ticket #${signal.ticketId}` : undefined,
              });
            }
          } else if (signal.type === 'DECLINED') {
            gooeyToast.error('Call declined');
          } else if (signal.type === 'NO_ANSWER_AVAILABLE') {
            gooeyToast.warning('Nobody is available right now', {
              description: 'Try again shortly, or reply on the ticket.',
              duration: NO_ANSWER_TOAST_MS,
            });
          } else if (signal.type === 'CANCELLED') {
            gooeyToast.info('Call cancelled');
          }
          break;
        }

        default:
          break;
      }
    },
    [clearCall, stopRinging]
  );

  // ─── Socket lifecycle ──────────────────────────────────────────────────────

  const disconnect = useCallback(() => {
    connectingRef.current = false;
    if (pingRef.current) {
      clearInterval(pingRef.current);
      pingRef.current = null;
    }
    if (reconnectRef.current) {
      clearTimeout(reconnectRef.current);
      reconnectRef.current = null;
    }
    if (stompRef.current) {
      try {
        stompRef.current.disconnect(() => undefined);
      } catch {
        /* already gone */
      }
      stompRef.current = null;
    }
    setSocketConnected(false);
  }, []);

  const connect = useCallback(async () => {
    if (!mountedRef.current || stompRef.current || connectingRef.current) return;
    connectingRef.current = true;

    let token: string;
    try {
      // Fetched fresh on every attempt: the token is short-lived, so a socket
      // that drops for longer than its TTL must not retry with a stale one.
      const issued = await fetchWsToken();
      // No token means the environment cannot ring anyone — nothing to retry, so
      // stand down instead of entering the reconnect loop below.
      if (!issued?.token) {
        setCallingEnabled(false);
        connectingRef.current = false;
        return;
      }
      token = issued.token;
      // Older builds of the service omit the flag; assume calling works rather
      // than hiding the feature on a version skew during a rolling deploy.
      setCallingEnabled(issued.callingEnabled !== false);
    } catch {
      connectingRef.current = false;
      scheduleReconnect();
      return;
    }
    if (!mountedRef.current) {
      connectingRef.current = false;
      return;
    }

    try {
      const SockJS = (await import('sockjs-client')).default;
      const Stomp = (await import('stompjs')).default;

      const socket = new SockJS('/xamops-ws');
      const client = Stomp.over(socket);
      client.debug = () => undefined;

      client.connect(
        { 'X-Call-Token': token },
        () => {
          connectingRef.current = false;
          if (!mountedRef.current) {
            try {
              client.disconnect(() => undefined);
            } catch {
              /* noop */
            }
            return;
          }
          stompRef.current = client;
          attemptRef.current = 0;
          setSocketConnected(true);

          client.subscribe('/user/queue/calls', (message: any) => {
            try {
              handleSignal(JSON.parse(message.body) as CallSignal);
            } catch (err) {
              console.error('[calls] Unreadable signal', err);
            }
          });

          // Catch up on callers already holding. A push only reaches whoever was
          // connected when it was sent, so without this an agent arriving three
          // minutes into a ten-minute queue would never know anyone was waiting.
          void loadQueueRef.current();

          // Refreshes the server-side presence TTL. Without it a long-idle tab
          // silently stops being ringable.
          const ping = () => {
            try {
              client.send('/app/calls/ping', {}, '');
            } catch {
              /* the error callback will handle a dead socket */
            }
          };
          ping();
          pingRef.current = setInterval(ping, PRESENCE_PING_MS);
        },
        () => {
          connectingRef.current = false;
          stompRef.current = null;
          setSocketConnected(false);
          scheduleReconnect();
        }
      );
    } catch (err) {
      connectingRef.current = false;
      console.error('[calls] Failed to open signalling socket', err);
      scheduleReconnect();
    }
    // scheduleReconnect is stable via useRef indirection below
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [handleSignal]);

  // Declared after connect so both can reference each other without a cycle.
  const connectRef = useRef(connect);
  connectRef.current = connect;

  function scheduleReconnect() {
    if (!mountedRef.current || reconnectRef.current) return;
    attemptRef.current += 1;
    const delay = Math.min(RECONNECT_BASE_MS * attemptRef.current, RECONNECT_MAX_MS);
    reconnectRef.current = setTimeout(() => {
      reconnectRef.current = null;
      void connectRef.current();
    }, delay);
  }

  useEffect(() => {
    mountedRef.current = true;
    if (isAuthenticated) {
      void initNotificationService();
      void connect();
    } else {
      disconnect();
    }
    return () => {
      mountedRef.current = false;
      disconnect();
      ringtone.stop();
    };
  }, [isAuthenticated, connect, disconnect]);

  // ─── Standing queue ────────────────────────────────────────────────────────

  /**
   * Pulls in callers who are already holding.
   *
   * Merged rather than replaced: a call we are actively ringing for must not be
   * demoted into the quiet waiting list just because it also appears in the queue
   * snapshot.
   */
  const loadQueue = useCallback(async () => {
    try {
      const queue = await fetchCallQueue();
      if (!mountedRef.current || !Array.isArray(queue)) return;

      setWaitingCalls(prev => {
        const known = new Set(prev.map(c => c.callId));
        const additions: CallSignal[] = queue
          .filter(
            session =>
              !known.has(session.id) &&
              session.id !== activeCallIdRef.current &&
              session.id !== incomingIdRef.current
          )
          .map(session => ({
            type: 'QUEUED' as const,
            callId: session.id,
            ticketId: session.ticketId,
            direction: session.direction,
            mediaKind: session.mediaKind,
            initiatorUserId: session.initiatorUserId,
            initiatorName: session.initiatorName,
            ringExpiresInSeconds: session.queueExpiresInSeconds,
            waiting: true,
          }));
        return additions.length === 0 ? prev : [...prev, ...additions];
      });
    } catch {
      // The queue list is supplementary; a failed fetch just means we rely on pushes.
    }
  }, []);
  loadQueueRef.current = loadQueue;

  // ─── In-call liveness ──────────────────────────────────────────────────────

  /**
   * Reports that we are still in the room, for as long as we are in one.
   *
   * The server has no other way to tell a live conversation from a browser that
   * stopped existing. Without this signal a call whose participants vanished stayed
   * ACTIVE indefinitely, and because a new call always defers to a live session on
   * the same ticket, that ticket could never host another call again.
   *
   * Deliberately over REST rather than the already-open socket, even though the
   * socket would be cheaper. The reply is the point: REST answers 409 once the
   * server no longer considers us present, and that is the only thing that
   * recovers a client which missed the ENDED signal — a call that closed while our
   * socket happened to be down would otherwise leave the UI showing a
   * conversation that no longer exists, with no event coming to correct it. It
   * also keeps working while the socket is reconnecting, which is exactly when a
   * live call must not be mistaken for an abandoned room.
   *
   * The cost is four small requests a minute per person actually in a call, which
   * for support-call volumes is nothing next to the failure it rules out.
   */
  const activeCallId = activeToken?.callId ?? null;
  useEffect(() => {
    if (activeCallId == null) return;

    let stopped = false;

    const beat = async () => {
      if (stopped || !mountedRef.current) return;

      const stillIn = await sendHeartbeat(activeCallId);
      if (stopped || !mountedRef.current || stillIn) return;

      // The server no longer counts us as present, so there is nothing left to
      // beat for. Reconcile rather than keep reporting into a closed call.
      if (activeCallIdRef.current === activeCallId) {
        clearCall();
        gooeyToast.info('The call has ended');
      }
    };

    void beat();
    heartbeatRef.current = setInterval(() => void beat(), HEARTBEAT_MS);

    return () => {
      stopped = true;
      if (heartbeatRef.current) {
        clearInterval(heartbeatRef.current);
        heartbeatRef.current = null;
      }
    };
  }, [activeCallId, clearCall]);

  /**
   * Keeps sibling tabs in step about whether this user is on a call.
   *
   * Without it, each tab had its own private answer to "am I busy?", so a ring
   * arriving while tab 1 was mid-call would interrupt tab 2 at full volume — and
   * answering there produced two live rooms at once. The server now rejects that
   * outright; this is what stops the user being offered the invalid action in the
   * first place.
   */
  useEffect(() => {
    if (typeof BroadcastChannel === 'undefined') return;

    const channel = new BroadcastChannel(TAB_SYNC_CHANNEL);
    tabSyncRef.current = channel;

    channel.onmessage = (event: MessageEvent<TabSyncMessage>) => {
      const message = event.data;
      if (!message || !mountedRef.current) return;

      if (message.kind === 'who-is-on-a-call') {
        // A tab just opened and is asking. Only answer if we are actually on a call.
        if (activeCallIdRef.current !== null) {
          channel.postMessage({ kind: 'in-call', callId: activeCallIdRef.current });
        }
        return;
      }

      if (message.kind === 'free') {
        otherTabCallIdRef.current = null;
        return;
      }

      // Another tab is on a call. Ignore its own echo of the call we are both aware
      // of, then stand down from anything we are currently ringing for.
      if (message.callId === activeCallIdRef.current) return;
      otherTabCallIdRef.current = message.callId;

      const ringingFor = incomingIdRef.current;
      if (ringingFor !== null && ringingFor !== message.callId) {
        // We were ringing for a call this user cannot take. Convert it rather than
        // drop it: it is still a live ring that becomes a missed call if ignored.
        incomingIdRef.current = null;
        stopRinging();
        setIncoming(prev => {
          if (prev && prev.callId === ringingFor) {
            setWaitingCalls(w =>
              w.some(c => c.callId === prev.callId) ? w : [...w, { ...prev, waiting: true }]
            );
          }
          return null;
        });
        setPhase('idle');
      }
    };

    // Ask whoever is already open, so a freshly opened tab is not briefly convinced
    // the user is free.
    channel.postMessage({ kind: 'who-is-on-a-call' });

    return () => {
      channel.onmessage = null;
      channel.close();
      tabSyncRef.current = null;
    };
  }, [stopRinging]);

  /** Announces our own transitions so the handler above has something to react to. */
  useEffect(() => {
    const channel = tabSyncRef.current;
    if (!channel) return;
    try {
      channel.postMessage(
        activeCallId != null ? { kind: 'in-call', callId: activeCallId } : { kind: 'free' }
      );
    } catch {
      /* a closed channel during teardown is not worth reporting */
    }
  }, [activeCallId]);

  /**
   * Leaves the call when the page goes away.
   *
   * A normal request is abandoned the moment the document unloads, so closing a
   * tab mid-call told the server nothing and left the room occupied. `pagehide`
   * rather than `beforeunload` alone: iOS Safari frequently skips `beforeunload`
   * entirely, and mobile is exactly where a tab gets discarded without warning.
   *
   * The heartbeat sweeper remains the authority — this just makes the common case
   * instant instead of taking a couple of minutes, so the ticket is immediately
   * free to be called again.
   */
  useEffect(() => {
    const teardown = () => {
      const callId = activeCallIdRef.current;
      if (callId == null) return;
      beaconLeaveCall(callId);
    };

    window.addEventListener('pagehide', teardown);
    window.addEventListener('beforeunload', teardown);
    return () => {
      window.removeEventListener('pagehide', teardown);
      window.removeEventListener('beforeunload', teardown);
    };
  }, []);

  /**
   * Puts the user back into a call they were in before the page reloaded.
   *
   * Refreshing used to strand someone: the browser held the only record of being
   * on a call, so the UI vanished while the server still had them occupying the
   * room — present, inaudible, and with no control to leave. Recovery reads the
   * live call from the server instead, which is the copy that actually decides.
   */
  useEffect(() => {
    if (!isAuthenticated || recoveryAttemptedRef.current) return;
    recoveryAttemptedRef.current = true;

    (async () => {
      try {
        const session = await fetchActiveCall();
        // Only recover a call that is genuinely still up, and never trample a call
        // the user has already started in the meantime.
        if (!session || session.state !== 'ACTIVE') return;
        if (!mountedRef.current || activeCallIdRef.current !== null) return;

        const token = await refreshCallToken(session.id);
        if (!mountedRef.current || activeCallIdRef.current !== null) return;

        enterRoomRef.current(token, null, session.mediaKind ?? 'AUDIO');
        setPhase('in-call');
        gooeyToast.info('Rejoined your call in progress');
      } catch {
        // A call that ended while we were reloading is the expected case, not a
        // failure worth reporting.
      }
    })();
  }, [isAuthenticated]);

  // ─── Missed calls ────────────────────────────────────────────────────────

  const refreshMissedCalls = useCallback(async () => {
    try {
      const list = await fetchMissedCalls();
      // This provider wraps the whole app and sits above its error boundary, so
      // a non-array body here would blank the page rather than drop a badge.
      if (mountedRef.current) setMissedRaw(Array.isArray(list) ? list : []);
    } catch {
      // The badge is supplementary; a failed fetch just leaves the last known set.
    }
  }, []);
  refreshMissedRef.current = () => {
    void refreshMissedCalls();
  };

  const dismissMissedCall = useCallback((participantId: number) => {
    setDismissedMissed(prev => {
      if (prev.includes(participantId)) return prev;
      const next = [...prev, participantId];
      persistDismissedMissed(next);
      return next;
    });
  }, []);

  const clearMissedCalls = useCallback(() => {
    setDismissedMissed(prev => {
      const next = Array.from(new Set([...prev, ...missedRaw.map(m => m.participantId)]));
      persistDismissedMissed(next);
      return next;
    });
  }, [missedRaw]);

  // Load the standing missed list once identity is known, and refresh it when a
  // call ends so a miss that happened while we were away is waiting for us.
  useEffect(() => {
    if (isAuthenticated) void refreshMissedCalls();
    else setMissedRaw([]);
  }, [isAuthenticated, refreshMissedCalls]);

  const missedCalls = useMemo(() => {
    const seen = new Set(dismissedMissed);
    return missedRaw.filter(m => !seen.has(m.participantId));
  }, [missedRaw, dismissedMissed]);

  // ─── Actions ───────────────────────────────────────────────────────────────

  const enterRoom = useCallback(
    (token: CallTokenResponse, signal: CallSignal | null, mediaKind: CallMediaKind) => {
      setActiveToken(token);
      setActiveMediaKind(mediaKind);
      activeCallIdRef.current = token.callId;

      // Our own outgoing call has no signal yet — the RINGING push that raced
      // the initiate response is the only description of it we have, and the
      // panel needs it to say which ticket and how many agents are being rung.
      const ringing =
        pendingRingingRef.current?.callId === token.callId ? pendingRingingRef.current : null;
      pendingRingingRef.current = null;

      const described = signal ?? ringing;
      if (described) setActiveCall(described);
    },
    []
  );
  enterRoomRef.current = enterRoom;

  const startCall = useCallback(
    async (ticketId: number, mediaKind: CallMediaKind = 'AUDIO') => {
      if (activeCallIdRef.current !== null) {
        gooeyToast.warning('You are already on a call');
        return;
      }
      setPhase('connecting');
      try {
        const session = await initiateCall(ticketId, mediaKind);

        // A call with nobody online now *holds in the queue* rather than closing, so
        // reaching here with a terminal state means something genuinely ended it.
        if (session.state !== 'RINGING' && session.state !== 'ACTIVE') {
          setPhase('idle');
          return;
        }

        const token = await refreshCallToken(session.id);
        if (!mountedRef.current) return;

        // Uses the kind we asked for, not the session echo: the caller's camera
        // has to come up now, before any signal describing the call arrives.
        enterRoom(token, null, mediaKind);
        const stillRinging = session.state !== 'ACTIVE';
        outgoingIdRef.current = stillRinging ? session.id : null;
        setPhase(stillRinging ? 'outgoing' : 'in-call');
      } catch (err) {
        setPhase('idle');
        gooeyToast.error('Call failed', {
          description: readableCallError(err, 'Could not start the call'),
        });
      }
    },
    [enterRoom]
  );

  const acceptIncoming = useCallback(async () => {
    const call = incoming;
    if (!call) return;

    stopRinging();
    setPhase('connecting');
    // Cleared before the round trip, not after: the server's ANSWERED broadcast
    // can beat our own accept response back to this tab, and a stale marker
    // would make us treat our own success as "answered elsewhere".
    incomingIdRef.current = null;
    try {
      const outcome = await acceptCall(call.callId);
      if (!mountedRef.current) return;

      if (outcome.won) {
        setIncoming(null);
        enterRoom(outcome.token, call, call.mediaKind ?? 'AUDIO');
        setPhase('in-call');
        return;
      }

      setIncoming(null);
      incomingIdRef.current = null;

      // Not a lost race: this user is genuinely on another call, most likely in a
      // different tab. Keep the ring as a waiting card so it is not silently lost,
      // and say plainly why it could not be answered.
      if (outcome.alreadyOnCall) {
        setPhase(activeCallIdRef.current ? 'in-call' : 'idle');
        setWaitingCalls(prev =>
          prev.some(c => c.callId === call.callId) ? prev : [...prev, { ...call, waiting: true }]
        );
        gooeyToast.warning('You are already on another call', {
          description: 'Finish that call, then answer this one from the waiting list.',
        });
        return;
      }

      // Lost the race. Joining is the right move, not retrying.
      if (outcome.joinable) {
        const token = await joinCall(call.callId);
        if (!mountedRef.current) return;
        enterRoom(token, call, call.mediaKind ?? 'AUDIO');
        setPhase('in-call');
        gooeyToast.info('Another agent answered first — you joined the call');
      } else {
        setPhase('idle');
        gooeyToast.info('That call was already answered');
      }
    } catch (err) {
      setPhase('idle');
      setIncoming(null);
      incomingIdRef.current = null;
      gooeyToast.error('Could not answer', {
        description: readableCallError(err, 'Could not join the call'),
      });
    }
  }, [incoming, stopRinging, enterRoom]);

  const declineIncoming = useCallback(async () => {
    const call = incoming;
    if (!call) return;
    stopRinging();
    setIncoming(null);
    incomingIdRef.current = null;
    setPhase(activeCallIdRef.current ? 'in-call' : 'idle');
    try {
      await declineCall(call.callId);
    } catch {
      /* the ring is already dismissed locally; a failed decline is not worth a toast */
    }
  }, [incoming, stopRinging]);

  const joinExisting = useCallback(
    async (callId: number) => {
      if (activeCallIdRef.current !== null) {
        gooeyToast.warning('You are already on a call');
        return;
      }
      const signal = joinable.find(c => c.callId === callId) ?? null;
      setPhase('connecting');
      try {
        const token = await joinCall(callId);
        if (!mountedRef.current) return;
        setJoinable(prev => prev.filter(c => c.callId !== callId));
        enterRoom(token, signal, signal?.mediaKind ?? 'AUDIO');
        setPhase('in-call');
      } catch (err) {
        setPhase('idle');
        gooeyToast.error('Could not join', {
          description: readableCallError(err, 'Could not join the call'),
        });
      }
    },
    [joinable, enterRoom]
  );

  const hangUp = useCallback(async () => {
    const callId = activeCallIdRef.current;
    clearCall();
    if (callId == null) return;
    try {
      // Always /leave. The server decides whether that means cancelling an
      // unanswered call for everyone or just removing us, because only it knows both
      // the session state and who placed the call. Choosing here from the local
      // phase was wrong whenever we had joined a call that was still ringing: the
      // client would call /cancel without being the initiator, take a 403, and leave
      // itself recorded as still in the room.
      await leaveCall(callId);
    } catch {
      /* the local UI is already reset; the server sweeper will reconcile */
    }
  }, [clearCall]);

  const dismissJoinable = useCallback((callId: number) => {
    setJoinable(prev => prev.filter(c => c.callId !== callId));
  }, []);

  /**
   * Takes a call that was waiting.
   *
   * If we are still on another call it is ended first, because one person cannot hold
   * two conversations — that was the defect this replaced, not a feature. Ending
   * first also means the server-side one-call-at-a-time guard is satisfied by the
   * time we accept, so this cannot fail for that reason.
   */
  const answerWaiting = useCallback(
    async (callId: number) => {
      const call = waitingCalls.find(c => c.callId === callId);
      if (!call) return;

      if (activeCallIdRef.current !== null) {
        await hangUp();
      }
      setWaitingCalls(prev => prev.filter(c => c.callId !== callId));
      setPhase('connecting');

      try {
        const outcome = await acceptCall(callId);
        if (!mountedRef.current) return;

        if (outcome.won) {
          enterRoom(outcome.token, call, call.mediaKind ?? 'AUDIO');
          setPhase('in-call');
          return;
        }

        if (outcome.joinable) {
          const token = await joinCall(callId);
          if (!mountedRef.current) return;
          enterRoom(token, call, call.mediaKind ?? 'AUDIO');
          setPhase('in-call');
          gooeyToast.info('Another agent answered first — you joined the call');
          return;
        }

        setPhase('idle');
        gooeyToast.info('That call has already ended');
      } catch (err) {
        setPhase('idle');
        gooeyToast.error('Could not answer', {
          description: readableCallError(err, 'Could not join the call'),
        });
      }
    },
    [waitingCalls, hangUp, enterRoom]
  );

  /**
   * Declines a waiting call properly.
   *
   * Removing the card locally is not enough — the caller would keep ringing until the
   * deadline. Telling the server lets it close the call as soon as the last recipient
   * has said no, which is the difference between "declined" and "nobody answered".
   */
  const declineWaiting = useCallback(async (callId: number) => {
    setWaitingCalls(prev => prev.filter(c => c.callId !== callId));
    try {
      await declineCall(callId);
    } catch {
      /* the card is already gone locally; the ring deadline reconciles the rest */
    }
  }, []);

  const value = useMemo<CallContextValue>(
    () => ({
      phase,
      incoming,
      outgoing,
      joinable,
      waitingCalls,
      activeToken,
      activeCall,
      activeMediaKind,
      socketConnected,
      callingEnabled,
      missedCalls,
      startCall,
      acceptIncoming,
      declineIncoming,
      joinExisting,
      hangUp,
      dismissJoinable,
      answerWaiting,
      declineWaiting,
      refreshMissedCalls,
      dismissMissedCall,
      clearMissedCalls,
    }),
    [
      phase,
      incoming,
      outgoing,
      joinable,
      waitingCalls,
      activeToken,
      activeCall,
      activeMediaKind,
      socketConnected,
      callingEnabled,
      missedCalls,
      startCall,
      acceptIncoming,
      declineIncoming,
      joinExisting,
      hangUp,
      dismissJoinable,
      answerWaiting,
      declineWaiting,
      refreshMissedCalls,
      dismissMissedCall,
      clearMissedCalls,
    ]
  );

  return <CallContext.Provider value={value}>{children}</CallContext.Provider>;
};

export const useCalls = (): CallContextValue => {
  const ctx = useContext(CallContext);
  if (!ctx) {
    throw new Error('useCalls must be used within a CallProvider');
  }
  return ctx;
};
