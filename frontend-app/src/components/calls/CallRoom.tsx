/**
 * The in-call surface: a floating panel wired to a LiveKit room.
 *
 * Rendered as an overlay rather than a route so a call survives navigation — an
 * agent needs to browse the ticket, the account, and the invoice while talking,
 * and dropping the call on every route change would make it useless.
 *
 * Visual design targets a modern video-calling feel (Meet/Zoom quality): deep
 * navy stage, card-like tiles with gradient fallbacks, a live pulsing indicator
 * while ringing, a grouped control capsule, and a centered pre-connect "ringing"
 * hero that transitions into the tile grid once someone joins.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import {
  ConnectionQuality,
  ConnectionState,
  LocalParticipant,
  Participant,
  RemoteParticipant,
  Room,
  RoomEvent,
  Track,
} from 'livekit-client';
import { gooeyToast } from 'goey-toast';
import { AnimatePresence } from 'framer-motion';
import CallDeviceMenu from './CallDeviceMenu';
import CallInvitePicker from './CallInvitePicker';
import type { CallSignal, CallTokenResponse } from '../../types/calls';

/**
 * Only bad news is worth pixels. A green "excellent" badge on every tile is
 * noise; a warning when someone's audio is about to break up is actionable.
 */
const QUALITY_WARNING: Partial<Record<ConnectionQuality, { icon: string; tone: string; label: string }>> = {
  [ConnectionQuality.Poor]: {
    icon: 'mdi:wifi-strength-1-alert',
    tone: 'text-amber-400',
    label: 'Weak connection',
  },
  [ConnectionQuality.Lost]: {
    icon: 'mdi:wifi-strength-off',
    tone: 'text-red-400',
    label: 'Connection lost',
  },
};

/**
 * How much of the screen the call takes.
 *
 * `docked` is the default because a call that seized the whole screen on answer
 * would fight the work the call is about. `full` exists because a video call in
 * a 380px corner box is not really watchable.
 */
type CallSize = 'pill' | 'docked' | 'full';

interface CallRoomProps {
  token: CallTokenResponse;
  call: CallSignal | null;
  /** True while we are the caller and nobody has picked up yet. */
  ringing: boolean;
  startWithVideo: boolean;
  onHangUp: () => void;
}

/** Deterministic gradient per identity so a face-less tile still has some life. */
const TILE_TINTS = [
  'from-cyan-500 to-blue-600',
  'from-indigo-500 to-violet-600',
  'from-emerald-500 to-teal-600',
  'from-fuchsia-500 to-pink-600',
  'from-amber-500 to-orange-600',
];

function tintFor(seed: string): string {
  let h = 0;
  for (let i = 0; i < seed.length; i += 1) h = (h * 31 + seed.charCodeAt(i)) >>> 0;
  return TILE_TINTS[h % TILE_TINTS.length];
}

/**
 * One participant's media. Attaching happens through refs because LiveKit hands
 * back MediaStreamTracks that must be bound to real DOM elements, which React
 * cannot express declaratively.
 */
const ParticipantTile: React.FC<{
  participant: Participant;
  isLocal: boolean;
  /** Soft pulsing rings around the avatar to signal a live "ringing" state. */
  pulse?: boolean;
  className?: string;
}> = ({ participant, isLocal, pulse = false, className = '' }) => {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [hasVideo, setHasVideo] = useState(false);
  const [speaking, setSpeaking] = useState(participant.isSpeaking);
  const [muted, setMuted] = useState(false);
  const [quality, setQuality] = useState<ConnectionQuality>(participant.connectionQuality);

  useEffect(() => {
    const syncTracks = () => {
      const cameraPub = participant.getTrackPublication(Track.Source.Camera);
      const micPub = participant.getTrackPublication(Track.Source.Microphone);

      const videoTrack = cameraPub?.track;
      if (videoTrack && videoRef.current && !cameraPub?.isMuted) {
        videoTrack.attach(videoRef.current);
        setHasVideo(true);
      } else {
        setHasVideo(false);
      }

      // Never attach our own mic — it would feed straight back as echo.
      const audioTrack = micPub?.track;
      if (!isLocal && audioTrack && audioRef.current) {
        audioTrack.attach(audioRef.current);
      }
      setMuted(micPub ? micPub.isMuted : true);
    };

    syncTracks();

    const onSpeaking = () => setSpeaking(participant.isSpeaking);
    const onQuality = () => setQuality(participant.connectionQuality);
    participant.on('connectionQualityChanged', onQuality);
    participant.on('trackSubscribed', syncTracks);
    participant.on('trackUnsubscribed', syncTracks);
    participant.on('trackMuted', syncTracks);
    participant.on('trackUnmuted', syncTracks);
    participant.on('trackPublished', syncTracks);
    participant.on('trackUnpublished', syncTracks);
    participant.on('isSpeakingChanged', onSpeaking);

    return () => {
      participant.off('trackSubscribed', syncTracks);
      participant.off('trackUnsubscribed', syncTracks);
      participant.off('trackMuted', syncTracks);
      participant.off('trackUnmuted', syncTracks);
      participant.off('trackPublished', syncTracks);
      participant.off('trackUnpublished', syncTracks);
      participant.off('isSpeakingChanged', onSpeaking);
      participant.off('connectionQualityChanged', onQuality);

      participant.getTrackPublication(Track.Source.Camera)?.track?.detach();
      participant.getTrackPublication(Track.Source.Microphone)?.track?.detach();
    };
  }, [participant, isLocal]);

  const label = participant.name || participant.identity;
  const initial = (label || '?').charAt(0).toUpperCase();
  const tint = tintFor(label || 'x');

  return (
    <div
      className={`relative rounded-2xl overflow-hidden aspect-video flex items-center justify-center shadow-xl shadow-black/40 transition-all duration-300 ring-2 ${
        speaking ? 'ring-teal-400/80' : 'ring-white/5'
      } ${className}`}
      // Subtle gradient so an empty video state reads as "waiting", not "broken".
      style={{ background: 'linear-gradient(150deg,#1b2438 0%,#0d1322 100%)' }}
    >
      <video
        ref={videoRef}
        autoPlay
        playsInline
        muted={isLocal}
        className={`w-full h-full object-cover ${hasVideo ? '' : 'hidden'}`}
      />
      {!isLocal && <audio ref={audioRef} autoPlay />}

      {!hasVideo && (
        <div className="flex flex-col items-center gap-3">
          <div className="relative flex items-center justify-center">
            {pulse && (
              <>
                <span
                  className={`absolute inline-flex rounded-full bg-gradient-to-br ${tint} opacity-30 animate-ping`}
                  style={{ width: '5.5rem', height: '5.5rem', animationDuration: '1.8s' }}
                />
                <span
                  className={`absolute inline-flex rounded-full bg-gradient-to-br ${tint} opacity-20 animate-ping`}
                  style={{ width: '7.5rem', height: '7.5rem', animationDuration: '2.4s' }}
                />
              </>
            )}
            <div
              className={`relative w-16 h-16 rounded-full bg-gradient-to-br ${tint} flex items-center justify-center text-2xl font-semibold text-white shadow-lg ring-1 ring-white/20`}
            >
              {initial}
            </div>
          </div>
        </div>
      )}

      {/* Name chip with live mic state */}
      <div className="absolute bottom-2 left-2 right-2 flex items-center gap-1.5">
        <span className="inline-flex items-center gap-1.5 truncate bg-black/45 backdrop-blur-md rounded-full pl-1.5 pr-2.5 py-1 text-[11px] font-medium text-white/90 ring-1 ring-white/10">
          <Icon
            icon={muted ? 'mdi:microphone-off' : 'mdi:microphone'}
            width={13}
            className={muted ? 'text-rose-400' : 'text-teal-300'}
          />
          <span className="truncate">
            {label}
            {isLocal ? ' (you)' : ''}
          </span>
        </span>
        {QUALITY_WARNING[quality] && (
          <span
            className="bg-black/45 backdrop-blur-md rounded-full px-1.5 py-1 flex items-center ring-1 ring-white/10"
            title={QUALITY_WARNING[quality]!.label}
          >
            <Icon
              icon={QUALITY_WARNING[quality]!.icon}
              width={13}
              className={QUALITY_WARNING[quality]!.tone}
            />
          </span>
        )}
      </div>
    </div>
  );
};

/**
 * The shared screen, shown as the main stage.
 *
 * Uses object-contain rather than cover: a cropped screen share defeats the
 * point, since the thing being pointed at is as likely to be in a corner as in
 * the middle.
 */
const ScreenShareView: React.FC<{ participant: Participant; label: string }> = ({
  participant,
  label,
}) => {
  const videoRef = useRef<HTMLVideoElement | null>(null);

  useEffect(() => {
    const attach = () => {
      const track = participant.getTrackPublication(Track.Source.ScreenShare)?.track;
      if (track && videoRef.current) track.attach(videoRef.current);
    };
    attach();

    participant.on('trackSubscribed', attach);
    participant.on('trackPublished', attach);
    return () => {
      participant.off('trackSubscribed', attach);
      participant.off('trackPublished', attach);
      participant.getTrackPublication(Track.Source.ScreenShare)?.track?.detach();
    };
  }, [participant]);

  return (
    <div className="relative w-full h-full bg-black rounded-2xl overflow-hidden flex items-center justify-center ring-1 ring-white/10 shadow-xl shadow-black/40">
      <video ref={videoRef} autoPlay playsInline muted className="w-full h-full object-contain" />
      <div className="absolute top-2 left-2 flex items-center gap-1.5 bg-black/55 backdrop-blur-md rounded-full px-2.5 py-1 text-[11px] font-medium text-white ring-1 ring-white/10">
        <Icon icon="mdi:monitor-share" width={13} className="text-teal-300" />
        {label}
      </div>
    </div>
  );
};

/** A round control button with consistent sizing, hover-lift and states. */
const ControlButton: React.FC<{
  onClick: () => void;
  icon: string;
  label: string;
  isFull: boolean;
  active?: boolean;
  danger?: boolean;
  emphasis?: boolean;
  disabled?: boolean;
  title?: string;
}> = ({ onClick, icon, label, isFull, active = false, danger = false, emphasis = false, disabled = false, title }) => {
  const size = isFull ? (emphasis ? 'p-4' : 'p-3.5') : 'p-2.5';
  const iconSize = isFull ? (emphasis ? 24 : 22) : 18;
  const skin = danger
    ? 'bg-gradient-to-br from-rose-500 to-red-600 text-white shadow-lg shadow-rose-500/40 hover:shadow-rose-500/60'
    : active
      ? 'bg-teal-500/90 text-white shadow-lg shadow-teal-500/30 hover:bg-teal-500'
      : 'bg-white/10 text-white hover:bg-white/20 ring-1 ring-white/10';
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      title={title ?? label}
      className={`rounded-full transition-all duration-150 hover:scale-105 active:scale-95 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:scale-100 ${size} ${skin}`}
    >
      <Icon icon={icon} width={iconSize} />
    </button>
  );
};

const CallRoom: React.FC<CallRoomProps> = ({
  token,
  call,
  ringing,
  startWithVideo,
  onHangUp,
}) => {
  const roomRef = useRef<Room | null>(null);
  const [connectionState, setConnectionState] = useState<ConnectionState>(
    ConnectionState.Connecting
  );
  const [localParticipant, setLocalParticipant] = useState<LocalParticipant | null>(null);
  const [remotes, setRemotes] = useState<RemoteParticipant[]>([]);
  const [micOn, setMicOn] = useState(true);
  const [camOn, setCamOn] = useState(startWithVideo);
  const [elapsed, setElapsed] = useState(0);
  const [size, setSize] = useState<CallSize>('docked');
  const [showInvite, setShowInvite] = useState(false);
  // LiveKit mutates the participant objects in place, so setState with the same
  // reference is a no-op and local publish/unpublish never repaints. Bumping a
  // counter alongside it forces the render that the identity check suppresses.
  const [revision, setRevision] = useState(0);

  // Connect once per token. A new token means a new room, so remounting the
  // connection is correct rather than wasteful.
  useEffect(() => {
    let cancelled = false;
    const room = new Room({ adaptiveStream: true, dynacast: true });
    roomRef.current = room;

    const syncParticipants = () => {
      if (cancelled) return;
      setRemotes(Array.from(room.remoteParticipants.values()));
      setLocalParticipant(room.localParticipant);
      setRevision(r => r + 1);
    };

    room
      .on(RoomEvent.ParticipantConnected, syncParticipants)
      .on(RoomEvent.ParticipantDisconnected, syncParticipants)
      .on(RoomEvent.TrackSubscribed, syncParticipants)
      .on(RoomEvent.TrackUnsubscribed, syncParticipants)
      .on(RoomEvent.LocalTrackPublished, syncParticipants)
      .on(RoomEvent.LocalTrackUnpublished, syncParticipants)
      // Screen shares arrive as published/muted changes rather than
      // subscriptions, so without these the stage never appears or never clears.
      .on(RoomEvent.TrackPublished, syncParticipants)
      .on(RoomEvent.TrackUnpublished, syncParticipants)
      .on(RoomEvent.TrackMuted, syncParticipants)
      .on(RoomEvent.TrackUnmuted, syncParticipants)
      .on(RoomEvent.ConnectionStateChanged, state => {
        if (!cancelled) setConnectionState(state);
      })
      .on(RoomEvent.Disconnected, () => {
        if (!cancelled) setConnectionState(ConnectionState.Disconnected);
      });

    (async () => {
      try {
        await room.connect(token.livekitUrl, token.token);
        if (cancelled) {
          await room.disconnect();
          return;
        }
        // A refused or missing microphone must not end the call. Joining muted
        // still lets you hear the other side and watch a screen share, which is
        // far more useful than being dropped — and the user can grant permission
        // and unmute without redialling.
        try {
          await room.localParticipant.setMicrophoneEnabled(true);
        } catch {
          if (!cancelled) {
            setMicOn(false);
            gooeyToast.warning('Microphone unavailable — you joined muted', {
              description: 'Check your browser permissions, then unmute.',
            });
          }
        }
        if (startWithVideo) {
          try {
            await room.localParticipant.setCameraEnabled(true);
          } catch {
            if (!cancelled) {
              setCamOn(false);
              gooeyToast.warning('Camera unavailable — continuing with audio only');
            }
          }
        }
        syncParticipants();
      } catch (err) {
        if (cancelled) return;
        console.error('[calls] Failed to join room', err);
        gooeyToast.error('Could not connect to the call', {
          description: err instanceof Error ? err.message : undefined,
        });
        onHangUp();
      }
    })();

    return () => {
      cancelled = true;
      void room.disconnect();
      roomRef.current = null;
    };
    // onHangUp is intentionally excluded: it changes identity on every parent
    // render and would tear the room down mid-call.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token.token, token.livekitUrl, startWithVideo]);

  // Call timer starts when someone else is actually present, not at connect —
  // counting while it still rings would misreport the call length.
  const hasPeer = remotes.length > 0;
  useEffect(() => {
    if (!hasPeer) return;
    const started = Date.now();
    const id = setInterval(() => setElapsed(Math.floor((Date.now() - started) / 1000)), 1000);
    return () => clearInterval(id);
  }, [hasPeer]);

  const toggleMic = useCallback(async () => {
    const room = roomRef.current;
    if (!room) return;
    const next = !micOn;
    setMicOn(next);
    try {
      await room.localParticipant.setMicrophoneEnabled(next);
    } catch {
      setMicOn(!next);
      gooeyToast.error('Could not change the microphone');
    }
  }, [micOn]);

  /**
   * Whoever is currently sharing, if anyone.
   *
   * Derived from the live publications rather than tracked in state, so that
   * Chrome's own "Stop sharing" bar — which we never hear about directly — still
   * clears the stage. `revision` is the dependency that makes this recompute.
   */
  const sharer = useMemo(() => {
    const everyone: (Participant | null)[] = [localParticipant, ...remotes];
    return (
      everyone.find(p => {
        const pub = p?.getTrackPublication(Track.Source.ScreenShare);
        return !!pub?.track && !pub.isMuted;
      }) ?? null
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [localParticipant, remotes, revision]);

  const sharingLocally = !!sharer && sharer === localParticipant;

  // Screen capture is unavailable on most mobile browsers and in insecure
  // contexts, so the control is hidden rather than offered and then failing.
  const canScreenShare =
    typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getDisplayMedia;

  const toggleScreenShare = useCallback(async () => {
    const room = roomRef.current;
    if (!room) return;
    try {
      await room.localParticipant.setScreenShareEnabled(!sharingLocally, { audio: true });
    } catch (err) {
      // Dismissing the OS picker rejects here; that is a normal user choice and
      // does not deserve an error toast.
      const aborted =
        err instanceof Error && (err.name === 'NotAllowedError' || err.name === 'AbortError');
      if (!aborted) {
        gooeyToast.error('Could not share your screen', {
          description: err instanceof Error ? err.message : undefined,
        });
      }
    }
  }, [sharingLocally]);

  const toggleCam = useCallback(async () => {
    const room = roomRef.current;
    if (!room) return;
    const next = !camOn;
    setCamOn(next);
    try {
      await room.localParticipant.setCameraEnabled(next);
    } catch {
      setCamOn(!next);
      gooeyToast.error('Could not change the camera');
    }
  }, [camOn]);

  // Escape returns to the corner rather than hanging up. Leaving a call is
  // destructive and unrecoverable, so it should never be one stray keypress away.
  useEffect(() => {
    if (size !== 'full') return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setSize('docked');
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [size]);

  // Recede the support-chat widget while full screen so it doesn't compete with
  // the call controls. Cleared on exit and unmount.
  useEffect(() => {
    const cls = 'xamops-call-fullscreen';
    if (size === 'full') document.body.classList.add(cls);
    else document.body.classList.remove(cls);
    return () => document.body.classList.remove(cls);
  }, [size]);

  const tileCount = (localParticipant ? 1 : 0) + remotes.length;
  // Keeps faces as large as the count allows instead of locking to two columns.
  const fullGridCols = useMemo(() => {
    if (tileCount <= 1) return 'grid-cols-1';
    if (tileCount <= 4) return 'grid-cols-2';
    if (tileCount <= 9) return 'grid-cols-3';
    return 'grid-cols-4';
  }, [tileCount]);

  const timeLabel = useMemo(() => {
    const mins = Math.floor(elapsed / 60)
      .toString()
      .padStart(2, '0');
    const secs = (elapsed % 60).toString().padStart(2, '0');
    return `${mins}:${secs}`;
  }, [elapsed]);

  const connecting =
    connectionState === ConnectionState.Connecting ||
    connectionState === ConnectionState.Reconnecting;
  const isRingingState = (ringing && !hasPeer) || (connecting && !hasPeer);

  const statusLabel = ringing && !hasPeer
    ? 'Ringing…'
    : connectionState === ConnectionState.Connecting
      ? 'Connecting…'
      : connectionState === ConnectionState.Reconnecting
        ? 'Reconnecting…'
        : hasPeer
          ? timeLabel
          : 'Waiting for others…';

  const title = call?.ticketId ? `Ticket #${call.ticketId}` : 'Support call';

  if (size === 'pill') {
    return (
      <div className="fixed bottom-4 right-4 z-[70] flex items-center gap-2 rounded-full bg-slate-900/90 backdrop-blur-xl text-white border border-white/10 shadow-[0_16px_40px_-4px_rgba(0,0,0,0.5)] px-3 py-2">
        <span className="relative flex h-2 w-2">
          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-teal-400 opacity-75" />
          <span className="relative inline-flex rounded-full h-2 w-2 bg-teal-400" />
        </span>
        <span className="text-xs font-medium tabular-nums">{statusLabel}</span>
        <button
          onClick={() => setSize('docked')}
          className="p-1 rounded-full hover:bg-white/10 transition-colors"
          aria-label="Expand call"
        >
          <Icon icon="mdi:arrow-expand" width={14} />
        </button>
        <button
          onClick={onHangUp}
          className="p-1 rounded-full bg-gradient-to-br from-rose-500 to-red-600 shadow-md shadow-rose-500/30 hover:brightness-110 transition-all"
          aria-label="Hang up"
        >
          <Icon icon="mdi:phone-hangup" width={14} />
        </button>
      </div>
    );
  }

  const isFull = size === 'full';

  // Live indicator: teal pulsing dot while ringing/connecting, steady while live.
  const liveDot = (
    <span className="relative flex h-2 w-2">
      {isRingingState && (
        <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-teal-400 opacity-75" />
      )}
      <span className="relative inline-flex rounded-full h-2 w-2 bg-teal-400" />
    </span>
  );

  return (
    <div
      className={
        isFull
          ? 'fixed inset-0 z-[70] flex flex-col text-white'
          : 'fixed bottom-4 right-4 z-[70] w-[min(94vw,400px)] rounded-2xl text-white shadow-[0_24px_48px_-8px_rgba(0,0,0,0.6)] overflow-hidden border border-white/10'
      }
      style={{ background: 'radial-gradient(120% 100% at 50% 0%, #131b2e 0%, #0a0e1a 60%, #070a12 100%)' }}
    >
      {/* ── Status header ─────────────────────────────────────────────── */}
      <div
        className={`flex items-center justify-between border-b border-white/5 ${
          isFull ? 'px-5 py-3.5' : 'px-3.5 py-2.5'
        }`}
      >
        <div className="min-w-0 flex items-center gap-3">
          {liveDot}
          <div className="min-w-0">
            <div className={`font-semibold truncate leading-tight ${isFull ? 'text-sm' : 'text-[13px]'}`}>
              {title}
            </div>
            <div className={`text-slate-400 truncate leading-tight flex items-center gap-1.5 ${isFull ? 'text-xs' : 'text-[11px]'}`}>
              <span className={hasPeer ? 'tabular-nums text-teal-300 font-medium' : ''}>{statusLabel}</span>
              {call?.initiatorName ? <span className="text-slate-500">· {call.initiatorName}</span> : null}
              {call?.ticketSubject && isFull ? <span className="text-slate-500 truncate">· {call.ticketSubject}</span> : null}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-1 flex-shrink-0">
          <button
            onClick={() => setSize(isFull ? 'docked' : 'full')}
            className="p-1.5 rounded-lg hover:bg-white/10 transition-colors"
            aria-label={isFull ? 'Exit full screen' : 'Maximise call'}
            title={isFull ? 'Exit full screen (Esc)' : 'Maximise'}
          >
            <Icon icon={isFull ? 'mdi:fullscreen-exit' : 'mdi:fullscreen'} width={isFull ? 20 : 17} />
          </button>
          <button
            onClick={() => setSize('pill')}
            className="p-1.5 rounded-lg hover:bg-white/10 transition-colors"
            aria-label="Minimise call"
            title="Minimise"
          >
            <Icon icon="mdi:minus" width={isFull ? 20 : 16} />
          </button>
        </div>
      </div>

      {/* Reconnection is silent otherwise — the picture freezes and people
          assume the other side went quiet rather than that the network dropped. */}
      {connectionState === ConnectionState.Reconnecting && (
        <div className="flex items-center gap-2 px-3 py-1.5 bg-amber-500/15 border-b border-amber-500/30 text-amber-300 text-[11px]">
          <Icon icon="mdi:loading" width={13} className="animate-spin flex-shrink-0" />
          Reconnecting — hold on
        </div>
      )}

      {/* ── Stage ─────────────────────────────────────────────────────── */}
      {sharer ? (
        // Someone is sharing: the screen is what matters, so it takes the space
        // and the faces shrink to a strip beside it.
        <div className={`flex-1 min-h-0 flex flex-col gap-2 ${isFull ? 'p-4' : 'p-2.5'}`}>
          <div className={isFull ? 'flex-1 min-h-0' : 'h-[30vh]'}>
            <ScreenShareView
              participant={sharer}
              label={
                sharingLocally
                  ? 'You are sharing'
                  : `${sharer.name || sharer.identity} is sharing`
              }
            />
          </div>
          <div className="flex gap-2 overflow-x-auto flex-shrink-0">
            {[localParticipant, ...remotes].filter(Boolean).map(p => (
              <div
                key={(p as Participant).sid}
                className={`flex-shrink-0 ${isFull ? 'w-44' : 'w-28'}`}
              >
                <ParticipantTile
                  participant={p as Participant}
                  isLocal={p === localParticipant}
                />
              </div>
            ))}
          </div>
        </div>
      ) : remotes.length === 0 ? (
        // Pre-connect hero: a single centered self-view / avatar with a live
        // pulsing ring, à la Meet/Zoom — transitions to the grid once someone joins.
        <div className={`flex-1 min-h-0 flex flex-col items-center justify-center gap-4 ${isFull ? 'p-6' : 'p-4'}`}>
          <div className={`w-full ${isFull ? 'max-w-2xl' : ''}`}>
            {localParticipant ? (
              <ParticipantTile participant={localParticipant} isLocal pulse={isRingingState} />
            ) : (
              <div
                className="relative rounded-2xl overflow-hidden aspect-video flex items-center justify-center shadow-xl shadow-black/40 ring-2 ring-white/5"
                style={{ background: 'linear-gradient(150deg,#1b2438 0%,#0d1322 100%)' }}
              >
                <Icon icon="mdi:loading" width={28} className="animate-spin text-slate-500" />
              </div>
            )}
          </div>
          <div className="flex items-center gap-2 text-slate-300">
            {liveDot}
            <span className={`font-medium ${isFull ? 'text-sm' : 'text-[13px]'}`}>
              {ringing ? 'Ringing…' : connecting ? 'Connecting…' : 'Waiting for others to join'}
            </span>
          </div>
        </div>
      ) : (
        <div
          className={
            isFull
              // Capped and centred: on an ultrawide monitor a single uncapped
              // tile becomes a wall of face.
              ? `flex-1 min-h-0 overflow-y-auto p-4 grid gap-3 auto-rows-min content-center mx-auto w-full max-w-[1200px] ${fullGridCols}`
              : 'p-2.5 grid grid-cols-2 gap-2.5 max-h-[42vh] overflow-y-auto'
          }
        >
          {localParticipant && <ParticipantTile participant={localParticipant} isLocal />}
          {remotes.map(p => (
            <ParticipantTile key={p.sid} participant={p} isLocal={false} />
          ))}
        </div>
      )}

      {/* ── Control capsule ───────────────────────────────────────────── */}
      <div className={`flex justify-center flex-shrink-0 ${isFull ? 'pb-6 pt-2' : 'pb-3.5 pt-1'}`}>
        <div
          className={`flex items-center rounded-full bg-slate-900/70 backdrop-blur-xl border border-white/10 shadow-2xl shadow-black/40 ${
            isFull ? 'gap-2 px-3 py-2' : 'gap-1.5 px-2 py-1.5'
          }`}
        >
          {/* Group 1 — media */}
          <ControlButton
            onClick={toggleMic}
            icon={micOn ? 'mdi:microphone' : 'mdi:microphone-off'}
            label={micOn ? 'Mute microphone' : 'Unmute microphone'}
            isFull={isFull}
            danger={!micOn}
          />
          <ControlButton
            onClick={toggleCam}
            icon={camOn ? 'mdi:video' : 'mdi:video-off'}
            label={camOn ? 'Turn camera off' : 'Turn camera on'}
            isFull={isFull}
            active={camOn}
          />
          {canScreenShare && (
            <ControlButton
              onClick={toggleScreenShare}
              icon={sharingLocally ? 'mdi:monitor-off' : 'mdi:monitor-share'}
              label={sharingLocally ? 'Stop sharing your screen' : 'Share your screen'}
              isFull={isFull}
              active={sharingLocally}
              disabled={!!sharer && !sharingLocally}
              title={
                sharer && !sharingLocally
                  ? `${sharer.name || sharer.identity} is already sharing`
                  : undefined
              }
            />
          )}

          <span className={`w-px bg-white/10 ${isFull ? 'h-7 mx-1' : 'h-6 mx-0.5'}`} />

          {/* Group 2 — people & settings */}
          <ControlButton
            onClick={() => setShowInvite(true)}
            icon="mdi:account-plus"
            label="Add participant"
            isFull={isFull}
          />
          <CallDeviceMenu room={roomRef.current} compact={!isFull} />

          <span className={`w-px bg-white/10 ${isFull ? 'h-7 mx-1' : 'h-6 mx-0.5'}`} />

          {/* Group 3 — end (dominant) */}
          <ControlButton
            onClick={onHangUp}
            icon="mdi:phone-hangup"
            label="Hang up"
            isFull={isFull}
            danger
            emphasis
          />
        </div>
      </div>

      <AnimatePresence>
        {showInvite && (
          <CallInvitePicker callId={token.callId} onClose={() => setShowInvite(false)} />
        )}
      </AnimatePresence>
    </div>
  );
};

export default CallRoom;
