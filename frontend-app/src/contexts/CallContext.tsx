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
  cancelCall,
  declineCall,
  fetchMissedCalls,
  fetchWsToken,
  initiateCall,
  joinCall,
  leaveCall,
  readableCallError,
  refreshCallToken,
} from '../utils/callsApi';
import type {
  CallMediaKind,
  CallSignal,
  CallTokenResponse,
  LocalCallPhase,
  MissedCallEntry,
} from '../types/calls';

/** Keep well inside the server's presence TTL (default 900s) so we never lapse. */
const PRESENCE_PING_MS = 4 * 60 * 1000;
const RECONNECT_BASE_MS = 3000;
const RECONNECT_MAX_MS = 30000;

/**
 * Missed calls the user has already seen and cleared. Persisted locally because
 * the server has no per-user "read" state — a missed call stays in the record
 * forever, so without this the badge would only ever grow. Dismissal is a
 * client-side convenience, not a state that needs to survive across devices.
 */
const DISMISSED_MISSED_KEY = 'xamops.calls.dismissedMissed';

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
  }, [stopRinging]);

  // ─── Signal handling ───────────────────────────────────────────────────────

  const handleSignal = useCallback(
    (signal: CallSignal) => {
      if (!mountedRef.current) return;

      switch (signal.type) {
        case 'INCOMING': {
          // Never let a second ring stomp a call we are already handling.
          if (activeCallIdRef.current !== null || incomingIdRef.current !== null) {
            setJoinable(prev =>
              prev.some(c => c.callId === signal.callId) ? prev : [...prev, signal]
            );
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
          break;

        case 'ANSWERED':
          // Reaches the caller and every session belonging to the answerer.
          if (activeCallIdRef.current === signal.callId) {
            setPhase('in-call');
            setOutgoing(null);
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

        case 'JOINABLE':
          // Someone else picked up. Stop ringing but keep a way in.
          if (incomingIdRef.current === signal.callId) {
            incomingIdRef.current = null;
            setIncoming(null);
            setPhase(activeCallIdRef.current ? 'in-call' : 'idle');
            stopRinging();
          }
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
          if (!wasOurs) return;

          clearCall();

          if (signal.type === 'MISSED') {
            gooeyToast.warning('Missed call', {
              description: signal.ticketId ? `Ticket #${signal.ticketId}` : undefined,
            });
          } else if (signal.type === 'DECLINED') {
            gooeyToast.error('Call declined');
          } else if (signal.type === 'NO_ANSWER_AVAILABLE') {
            gooeyToast.warning('Nobody is available right now', {
              description: 'Try again shortly, or reply on the ticket.',
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
      if (signal) setActiveCall(signal);
    },
    []
  );

  const startCall = useCallback(
    async (ticketId: number, mediaKind: CallMediaKind = 'AUDIO') => {
      if (activeCallIdRef.current !== null) {
        gooeyToast.warning('You are already on a call');
        return;
      }
      setPhase('connecting');
      try {
        const session = await initiateCall(ticketId, mediaKind);

        // A call that found nobody online is already closed server-side; the
        // NO_ANSWER_AVAILABLE signal explains it, so just stand down here.
        if (session.state !== 'RINGING' && session.state !== 'ACTIVE') {
          setPhase('idle');
          return;
        }

        const token = await refreshCallToken(session.id);
        if (!mountedRef.current) return;

        // Uses the kind we asked for, not the session echo: the caller's camera
        // has to come up now, before any signal describing the call arrives.
        enterRoom(token, null, mediaKind);
        setPhase(session.state === 'ACTIVE' ? 'in-call' : 'outgoing');
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

      // Lost the race. Joining is the right move, not retrying.
      setIncoming(null);
      incomingIdRef.current = null;
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
    const wasRingingOut = phase === 'outgoing';
    clearCall();
    if (callId == null) return;
    try {
      // Hanging up an unanswered outgoing call cancels it for everyone; hanging
      // up a live one only removes us.
      await (wasRingingOut ? cancelCall(callId) : leaveCall(callId));
    } catch {
      /* the local UI is already reset; the server sweeper will reconcile */
    }
  }, [phase, clearCall]);

  const dismissJoinable = useCallback((callId: number) => {
    setJoinable(prev => prev.filter(c => c.callId !== callId));
  }, []);

  const value = useMemo<CallContextValue>(
    () => ({
      phase,
      incoming,
      outgoing,
      joinable,
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
      refreshMissedCalls,
      dismissMissedCall,
      clearMissedCalls,
    }),
    [
      phase,
      incoming,
      outgoing,
      joinable,
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
