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
  ConnectionState,
  LocalParticipant,
  Participant,
  RemoteParticipant,
  Room,
  RoomEvent,
  Track,
} from 'livekit-client';
import { gooeyToast } from 'goey-toast';
import type { CallSignal, CallTokenResponse } from '../../types/calls';

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
  const [minimised, setMinimised] = useState(false);

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
    };

    room
      .on(RoomEvent.ParticipantConnected, syncParticipants)
      .on(RoomEvent.ParticipantDisconnected, syncParticipants)
      .on(RoomEvent.TrackSubscribed, syncParticipants)
      .on(RoomEvent.TrackUnsubscribed, syncParticipants)
      .on(RoomEvent.LocalTrackPublished, syncParticipants)
      .on(RoomEvent.LocalTrackUnpublished, syncParticipants)
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
        // Mic first: an audio call must work even if the camera is refused.
        await room.localParticipant.setMicrophoneEnabled(true);
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

  if (minimised) {
    return (
      <div className="fixed bottom-4 right-4 z-[70] flex items-center gap-2 rounded-full bg-slate-900 text-white shadow-2xl px-3 py-2">
        <span className="relative flex h-2 w-2">
          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75" />
          <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500" />
        </span>
        <span className="text-xs font-medium">{statusLabel}</span>
        <button
          onClick={() => setMinimised(false)}
          className="p-1 rounded-full hover:bg-white/10"
          aria-label="Expand call"
        >
          <Icon icon="mdi:arrow-expand" width={14} />
        </button>
        <button
          onClick={onHangUp}
          className="p-1 rounded-full bg-red-600 hover:bg-red-700"
          aria-label="Hang up"
        >
          <Icon icon="mdi:phone-hangup" width={14} />
        </button>
      </div>
    );
  }

  return (
    <div className="fixed bottom-4 right-4 z-[70] w-[min(94vw,380px)] rounded-xl bg-slate-900 text-white shadow-2xl overflow-hidden border border-slate-700">
      <div className="flex items-center justify-between px-3 py-2 border-b border-slate-700">
        <div className="min-w-0">
          <div className="text-xs font-semibold truncate">{title}</div>
          <div className="text-[11px] text-slate-400 truncate">
            {statusLabel}
            {call?.initiatorName ? ` · ${call.initiatorName}` : ''}
          </div>
        </div>
        <button
          onClick={() => setMinimised(true)}
          className="p-1 rounded hover:bg-white/10 flex-shrink-0"
          aria-label="Minimise call"
        >
          <Icon icon="mdi:minus" width={16} />
        </button>
      </div>

      <div className="p-2 grid grid-cols-2 gap-2 max-h-[40vh] overflow-y-auto">
        {localParticipant && (
          <ParticipantTile participant={localParticipant} isLocal />
        )}
        {remotes.map(p => (
          <ParticipantTile key={p.sid} participant={p} isLocal={false} />
        ))}
        {remotes.length === 0 && (
          <div className="aspect-video rounded-lg bg-slate-800 flex items-center justify-center text-[11px] text-slate-400 px-2 text-center">
            {ringing ? 'Waiting for an answer' : 'Nobody else here yet'}
          </div>
        )}
      </div>

      <div className="flex items-center justify-center gap-2 px-3 py-2.5 border-t border-slate-700">
        <button
          onClick={toggleMic}
          className={`p-2.5 rounded-full transition-colors ${
            micOn ? 'bg-slate-700 hover:bg-slate-600' : 'bg-red-600 hover:bg-red-700'
          }`}
          aria-label={micOn ? 'Mute microphone' : 'Unmute microphone'}
        >
          <Icon icon={micOn ? 'mdi:microphone' : 'mdi:microphone-off'} width={18} />
        </button>
        <button
          onClick={toggleCam}
          className={`p-2.5 rounded-full transition-colors ${
            camOn ? 'bg-slate-700 hover:bg-slate-600' : 'bg-slate-800 hover:bg-slate-700'
          }`}
          aria-label={camOn ? 'Turn camera off' : 'Turn camera on'}
        >
          <Icon icon={camOn ? 'mdi:video' : 'mdi:video-off'} width={18} />
        </button>
        <button
          onClick={onHangUp}
          className="p-2.5 rounded-full bg-red-600 hover:bg-red-700 transition-colors"
          aria-label="Hang up"
        >
          <Icon icon="mdi:phone-hangup" width={18} />
        </button>
      </div>
    </div>
  );
};

export default CallRoom;
