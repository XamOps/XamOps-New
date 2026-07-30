/**
 * The in-call surface: a floating panel wired to a LiveKit room.
 *
 * Rendered as an overlay rather than a route so a call survives navigation — an
 * agent needs to browse the ticket, the account, and the invoice while talking,
 * and dropping the call on every route change would make it useless.
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

/**
 * One participant's media. Attaching happens through refs because LiveKit hands
 * back MediaStreamTracks that must be bound to real DOM elements, which React
 * cannot express declaratively.
 */
const ParticipantTile: React.FC<{ participant: Participant; isLocal: boolean }> = ({
  participant,
  isLocal,
}) => {
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

  return (
    <div
      className={`relative rounded-lg overflow-hidden bg-slate-800 aspect-video flex items-center justify-center ring-2 transition-colors ${
        speaking ? 'ring-emerald-400' : 'ring-transparent'
      }`}
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
        <div className="flex flex-col items-center gap-1.5 text-slate-300">
          <div className="w-11 h-11 rounded-full bg-slate-600 flex items-center justify-center text-base font-semibold text-white">
            {initial}
          </div>
        </div>
      )}

      <div className="absolute bottom-1.5 left-1.5 right-1.5 flex items-center gap-1 text-[11px] text-white">
        <span className="truncate bg-black/50 rounded px-1.5 py-0.5">
          {label}
          {isLocal ? ' (you)' : ''}
        </span>
        {muted && (
          <span className="bg-black/50 rounded px-1 py-0.5 flex items-center">
            <Icon icon="mdi:microphone-off" width={12} className="text-red-400" />
          </span>
        )}
        {QUALITY_WARNING[quality] && (
          <span
            className="bg-black/50 rounded px-1 py-0.5 flex items-center"
            title={QUALITY_WARNING[quality]!.label}
          >
            <Icon
              icon={QUALITY_WARNING[quality]!.icon}
              width={12}
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
    <div className="relative w-full h-full bg-black rounded-lg overflow-hidden flex items-center justify-center">
      <video ref={videoRef} autoPlay playsInline muted className="w-full h-full object-contain" />
      <div className="absolute top-1.5 left-1.5 flex items-center gap-1 bg-black/60 rounded px-1.5 py-0.5 text-[11px] text-white">
        <Icon icon="mdi:monitor-share" width={12} className="text-emerald-400" />
        {label}
      </div>
    </div>
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
      <div className="fixed bottom-4 right-4 z-[70] flex items-center gap-2 rounded-full bg-slate-900/90 backdrop-blur-xl text-white border border-indigo-400/20 shadow-[0_16px_40px_-4px_rgba(99,102,241,0.3)] px-3 py-2">
        <span className="relative flex h-2 w-2">
          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75" />
          <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500" />
        </span>
        <span className="text-xs font-medium">{statusLabel}</span>
        <button
          onClick={() => setSize('docked')}
          className="p-1 rounded-full hover:bg-white/10"
          aria-label="Expand call"
        >
          <Icon icon="mdi:arrow-expand" width={14} />
        </button>
        <button
          onClick={onHangUp}
          className="p-1 rounded-full bg-gradient-to-r from-rose-500 to-red-600 shadow-md shadow-rose-500/30 hover:brightness-110 transition-all"
          aria-label="Hang up"
        >
          <Icon icon="mdi:phone-hangup" width={14} />
        </button>
      </div>
    );
  }

  const isFull = size === 'full';

  return (
    <div
      className={
        isFull
          ? 'fixed inset-0 z-[70] flex flex-col bg-slate-950 text-white'
          : 'fixed bottom-4 right-4 z-[70] w-[min(94vw,380px)] rounded-2xl bg-slate-900/90 backdrop-blur-xl text-white shadow-[0_24px_48px_-8px_rgba(99,102,241,0.35)] overflow-hidden border border-indigo-400/20'
      }
    >
      <div
        className={`flex items-center justify-between border-b border-slate-700 ${
          isFull ? 'px-5 py-3' : 'px-3 py-2'
        }`}
      >
        <div className="min-w-0">
          <div className={`font-semibold truncate ${isFull ? 'text-sm' : 'text-xs'}`}>{title}</div>
          <div className={`text-slate-400 truncate ${isFull ? 'text-xs' : 'text-[11px]'}`}>
            {statusLabel}
            {call?.initiatorName ? ` · ${call.initiatorName}` : ''}
            {call?.ticketSubject && isFull ? ` · ${call.ticketSubject}` : ''}
          </div>
        </div>
        <div className="flex items-center gap-1 flex-shrink-0">
          <button
            onClick={() => setSize(isFull ? 'docked' : 'full')}
            className="p-1.5 rounded hover:bg-white/10"
            aria-label={isFull ? 'Exit full screen' : 'Maximise call'}
            title={isFull ? 'Exit full screen (Esc)' : 'Maximise'}
          >
            <Icon icon={isFull ? 'mdi:fullscreen-exit' : 'mdi:fullscreen'} width={isFull ? 20 : 17} />
          </button>
          <button
            onClick={() => setSize('pill')}
            className="p-1.5 rounded hover:bg-white/10"
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

      {sharer ? (
        // Someone is sharing: the screen is what matters, so it takes the space
        // and the faces shrink to a strip beside it.
        <div className={`flex-1 min-h-0 flex flex-col gap-2 ${isFull ? 'p-4' : 'p-2'}`}>
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
      ) : (
        <div
          className={
            isFull
              // Capped and centred: on an ultrawide monitor a single uncapped
              // tile becomes a wall of face.
              ? `flex-1 min-h-0 overflow-y-auto p-4 grid gap-3 auto-rows-min content-center mx-auto w-full max-w-[1200px] ${fullGridCols}`
              : 'p-2 grid grid-cols-2 gap-2 max-h-[40vh] overflow-y-auto'
          }
        >
          {localParticipant && (
            <ParticipantTile participant={localParticipant} isLocal />
          )}
          {remotes.map(p => (
            <ParticipantTile key={p.sid} participant={p} isLocal={false} />
          ))}
          {remotes.length === 0 && (
            <div
              className={`aspect-video rounded-lg bg-slate-800 flex items-center justify-center text-slate-400 px-2 text-center ${
                isFull ? 'text-sm' : 'text-[11px]'
              }`}
            >
              {ringing ? 'Waiting for an answer' : 'Nobody else here yet'}
            </div>
          )}
        </div>
      )}

      <div
        className={`flex items-center justify-center border-t border-white/10 bg-white/[0.02] ${
          isFull ? 'gap-4 px-5 py-4 flex-shrink-0' : 'gap-2 px-3 py-2.5'
        }`}
      >
        <button
          onClick={toggleMic}
          className={`rounded-full transition-colors ${isFull ? 'p-4' : 'p-2.5'} ${
            micOn ? 'bg-slate-700 hover:bg-slate-600' : 'bg-red-600 hover:bg-red-700'
          }`}
          aria-label={micOn ? 'Mute microphone' : 'Unmute microphone'}
        >
          <Icon icon={micOn ? 'mdi:microphone' : 'mdi:microphone-off'} width={isFull ? 24 : 18} />
        </button>
        <button
          onClick={toggleCam}
          className={`rounded-full transition-colors ${isFull ? 'p-4' : 'p-2.5'} ${
            camOn ? 'bg-slate-700 hover:bg-slate-600' : 'bg-slate-800 hover:bg-slate-700'
          }`}
          aria-label={camOn ? 'Turn camera off' : 'Turn camera on'}
        >
          <Icon icon={camOn ? 'mdi:video' : 'mdi:video-off'} width={isFull ? 24 : 18} />
        </button>
        {canScreenShare && (
          <button
            onClick={toggleScreenShare}
            // Only blocked when someone *else* holds the stage — taking over
            // mid-share would yank the screen out from under them.
            disabled={!!sharer && !sharingLocally}
            className={`rounded-full transition-colors disabled:opacity-40 disabled:cursor-not-allowed ${
              isFull ? 'p-4' : 'p-2.5'
            } ${
              sharingLocally
                ? 'bg-emerald-600 hover:bg-emerald-700'
                : 'bg-slate-700 hover:bg-slate-600'
            }`}
            aria-label={sharingLocally ? 'Stop sharing your screen' : 'Share your screen'}
            title={
              sharer && !sharingLocally
                ? `${sharer.name || sharer.identity} is already sharing`
                : sharingLocally
                  ? 'Stop sharing'
                  : 'Share your screen'
            }
          >
            <Icon
              icon={sharingLocally ? 'mdi:monitor-off' : 'mdi:monitor-share'}
              width={isFull ? 24 : 18}
            />
          </button>
        )}
        <button
          onClick={() => setShowInvite(true)}
          className={`rounded-full transition-colors bg-slate-700 hover:bg-slate-600 ${
            isFull ? 'p-4' : 'p-2.5'
          }`}
          aria-label="Add participant"
          title="Add participant"
        >
          <Icon icon="mdi:account-plus" width={isFull ? 24 : 18} />
        </button>
        <CallDeviceMenu room={roomRef.current} compact={!isFull} />
        <button
          onClick={onHangUp}
          className={`rounded-full bg-gradient-to-r from-rose-500 to-red-600 shadow-lg shadow-rose-500/30 hover:brightness-110 transition-all ${
            isFull ? 'px-6 py-4' : 'p-2.5'
          }`}
          aria-label="Hang up"
        >
          <Icon icon="mdi:phone-hangup" width={isFull ? 24 : 18} />
        </button>
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
