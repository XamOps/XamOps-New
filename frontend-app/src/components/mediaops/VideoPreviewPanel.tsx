import { useEffect, useRef, useState, useCallback } from 'react';
import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

interface VideoPreviewPanelProps {
  sourceFile: File | null;
  sourceUrl: string;
  sourceMode: 'upload' | 'url';
  watermarkType: 'image' | 'text' | null;
  watermarkImage: File | null;
  watermarkText: string;
  watermarkX: number;
  watermarkY: number;
  watermarkOpacity: number;
  watermarkScale: number;   // % of video width, 3–60
  onPositionChange?: (x: number, y: number) => void;
  onScaleChange?: (scale: number) => void;
  // Visual trim — start/end in seconds (null = not set). onTrimChange enables the trim track.
  trimStartSec?: number | null;
  trimEndSec?: number | null;
  onTrimChange?: (startSec: number, endSec: number) => void;
}

function formatTime(s: number): string {
  const m = Math.floor(s / 60);
  const sec = Math.floor(s % 60);
  return `${m}:${sec.toString().padStart(2, '0')}`;
}

export function VideoPreviewPanel({
  sourceFile, sourceUrl, sourceMode,
  watermarkType, watermarkImage, watermarkText,
  watermarkX, watermarkY, watermarkOpacity, watermarkScale,
  onPositionChange, onScaleChange,
  trimStartSec, trimEndSec, onTrimChange,
}: VideoPreviewPanelProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';
  const videoRef = useRef<HTMLVideoElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const [videoSrc, setVideoSrc] = useState<string | null>(null);
  const [wmImageUrl, setWmImageUrl] = useState<string | null>(null);
  const [duration, setDuration] = useState(0);
  const [currentTime, setCurrentTime] = useState(0);
  const [ready, setReady] = useState(false);
  const [isPlaying, setIsPlaying] = useState(false);
  const [isDragging, setIsDragging] = useState(false);
  const [isResizing, setIsResizing] = useState(false);

  const dragState = useRef({ startClientX: 0, startClientY: 0, startX: 0, startY: 0 });
  const resizeState = useRef({ startClientX: 0, startClientY: 0, startScale: 0 });

  // ── Source ───────────────────────────────────────────────────────────────────

  useEffect(() => {
    if (sourceMode === 'upload' && sourceFile) {
      const url = URL.createObjectURL(sourceFile);
      setVideoSrc(url);
      setReady(false);
      setCurrentTime(0);
      return () => URL.revokeObjectURL(url);
    } else if (sourceMode === 'url' && sourceUrl.trim()) {
      setVideoSrc(sourceUrl.trim());
      setReady(false);
      setCurrentTime(0);
    } else {
      setVideoSrc(null);
      setReady(false);
    }
  }, [sourceFile, sourceUrl, sourceMode]);

  useEffect(() => {
    if (watermarkImage) {
      const url = URL.createObjectURL(watermarkImage);
      setWmImageUrl(url);
      return () => URL.revokeObjectURL(url);
    }
    setWmImageUrl(null);
  }, [watermarkImage]);

  // ── Video events ─────────────────────────────────────────────────────────────

  const handleMetadata = useCallback(() => {
    const v = videoRef.current;
    if (!v) return;
    setDuration(v.duration);
    v.currentTime = Math.min(v.duration * 0.1, 5);
    setReady(true);
  }, []);

  const handleTimeUpdate = useCallback(() => {
    if (videoRef.current) setCurrentTime(videoRef.current.currentTime);
  }, []);

  const handleSeek = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const t = Number(e.target.value);
    setCurrentTime(t);
    if (videoRef.current) videoRef.current.currentTime = t;
  }, []);

  const togglePlay = useCallback(() => {
    const v = videoRef.current;
    if (!v) return;
    if (v.paused) { v.play(); setIsPlaying(true); }
    else { v.pause(); setIsPlaying(false); }
  }, []);

  // ── Drag (move) ──────────────────────────────────────────────────────────────

  const startDrag = useCallback((clientX: number, clientY: number) => {
    if (!onPositionChange) return;
    setIsDragging(true);
    dragState.current = { startClientX: clientX, startClientY: clientY, startX: watermarkX, startY: watermarkY };
  }, [onPositionChange, watermarkX, watermarkY]);

  const moveDrag = useCallback((clientX: number, clientY: number) => {
    if (!isDragging || !containerRef.current || !onPositionChange) return;
    const rect = containerRef.current.getBoundingClientRect();
    const dx = ((clientX - dragState.current.startClientX) / rect.width) * 100;
    const dy = ((clientY - dragState.current.startClientY) / rect.height) * 100;
    // Clamp so watermark center stays inside the frame (account for half its width/height)
    const halfW = watermarkScale / 2;
    const halfH = watermarkScale * (9 / 16) / 2;
    const newX = Math.max(halfW, Math.min(100 - halfW, dragState.current.startX + dx));
    const newY = Math.max(halfH, Math.min(100 - halfH, dragState.current.startY + dy));
    onPositionChange(Math.round(newX * 4) / 4, Math.round(newY * 4) / 4);
  }, [isDragging, onPositionChange, watermarkScale]);

  const endDrag = useCallback(() => setIsDragging(false), []);

  useEffect(() => {
    if (!isDragging) return;
    const onMM = (e: MouseEvent) => moveDrag(e.clientX, e.clientY);
    const onTM = (e: TouchEvent) => { e.preventDefault(); moveDrag(e.touches[0].clientX, e.touches[0].clientY); };
    window.addEventListener('mousemove', onMM);
    window.addEventListener('mouseup', endDrag);
    window.addEventListener('touchmove', onTM, { passive: false });
    window.addEventListener('touchend', endDrag);
    return () => {
      window.removeEventListener('mousemove', onMM);
      window.removeEventListener('mouseup', endDrag);
      window.removeEventListener('touchmove', onTM);
      window.removeEventListener('touchend', endDrag);
    };
  }, [isDragging, moveDrag, endDrag]);

  // ── Resize ───────────────────────────────────────────────────────────────────

  const startResize = useCallback((clientX: number, clientY: number) => {
    if (!onScaleChange) return;
    setIsResizing(true);
    resizeState.current = { startClientX: clientX, startClientY: clientY, startScale: watermarkScale };
  }, [onScaleChange, watermarkScale]);

  const moveResize = useCallback((clientX: number, clientY: number) => {
    if (!isResizing || !containerRef.current || !onScaleChange) return;
    const rect = containerRef.current.getBoundingClientRect();
    // Diagonal: drag right/down = bigger, left/up = smaller
    const dx = ((clientX - resizeState.current.startClientX) / rect.width) * 100;
    const dy = ((clientY - resizeState.current.startClientY) / rect.height) * 100;
    const delta = (dx + dy) / 2;
    const newScale = Math.max(3, Math.min(60, resizeState.current.startScale + delta * 2));
    onScaleChange(Math.round(newScale * 4) / 4);
  }, [isResizing, onScaleChange]);

  const endResize = useCallback(() => setIsResizing(false), []);

  useEffect(() => {
    if (!isResizing) return;
    const onMM = (e: MouseEvent) => moveResize(e.clientX, e.clientY);
    const onTM = (e: TouchEvent) => { e.preventDefault(); moveResize(e.touches[0].clientX, e.touches[0].clientY); };
    window.addEventListener('mousemove', onMM);
    window.addEventListener('mouseup', endResize);
    window.addEventListener('touchmove', onTM, { passive: false });
    window.addEventListener('touchend', endResize);
    return () => {
      window.removeEventListener('mousemove', onMM);
      window.removeEventListener('mouseup', endResize);
      window.removeEventListener('touchmove', onTM);
      window.removeEventListener('touchend', endResize);
    };
  }, [isResizing, moveResize, endResize]);

  // ── Visual trim ──────────────────────────────────────────────────────────────

  const [trimDragging, setTrimDragging] = useState<null | 'start' | 'end'>(null);
  const trimTrackRef = useRef<HTMLDivElement>(null);

  const trimEnabled = !!onTrimChange && ready && duration > 0;
  const tStart = Math.max(0, Math.min(trimStartSec ?? 0, duration));
  const tEnd = (trimEndSec != null && trimEndSec > 0) ? Math.min(trimEndSec, duration) : duration;

  const timeFromClientX = useCallback((clientX: number) => {
    const track = trimTrackRef.current;
    if (!track || duration <= 0) return 0;
    const rect = track.getBoundingClientRect();
    const frac = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
    return frac * duration;
  }, [duration]);

  const moveTrim = useCallback((clientX: number) => {
    if (!trimDragging || !onTrimChange) return;
    const t = timeFromClientX(clientX);
    const minGap = Math.max(0.2, Math.min(0.5, duration * 0.01));
    if (trimDragging === 'start') {
      const ns = Math.max(0, Math.min(t, tEnd - minGap));
      onTrimChange(ns, tEnd);
      if (videoRef.current) { videoRef.current.currentTime = ns; setCurrentTime(ns); }
    } else {
      const ne = Math.min(duration, Math.max(t, tStart + minGap));
      onTrimChange(tStart, ne);
      if (videoRef.current) { videoRef.current.currentTime = ne; setCurrentTime(ne); }
    }
  }, [trimDragging, onTrimChange, timeFromClientX, duration, tStart, tEnd]);

  const endTrim = useCallback(() => setTrimDragging(null), []);

  useEffect(() => {
    if (!trimDragging) return;
    const onMM = (e: MouseEvent) => moveTrim(e.clientX);
    const onTM = (e: TouchEvent) => { e.preventDefault(); moveTrim(e.touches[0].clientX); };
    window.addEventListener('mousemove', onMM);
    window.addEventListener('mouseup', endTrim);
    window.addEventListener('touchmove', onTM, { passive: false });
    window.addEventListener('touchend', endTrim);
    return () => {
      window.removeEventListener('mousemove', onMM);
      window.removeEventListener('mouseup', endTrim);
      window.removeEventListener('touchmove', onTM);
      window.removeEventListener('touchend', endTrim);
    };
  }, [trimDragging, moveTrim, endTrim]);

  // ── Render ───────────────────────────────────────────────────────────────────

  if (!videoSrc) return null;

  const wmOpacity = watermarkOpacity / 100;
  const progress = duration > 0 ? (currentTime / duration) * 100 : 0;
  const showWatermark = watermarkType && ready && (
    (watermarkType === 'image' && wmImageUrl) ||
    (watermarkType === 'text' && watermarkText.trim())
  );
  const canDrag   = !!onPositionChange && !!showWatermark;
  const canResize = !!onScaleChange    && !!showWatermark;
  const isInteracting = isDragging || isResizing;

  let containerCursor = 'default';
  if (isDragging)  containerCursor = 'grabbing';
  if (isResizing)  containerCursor = 'se-resize';

  return (
    <div className={`rounded-2xl overflow-hidden border shadow-lg ${
      dark ? 'border-[rgba(255,255,255,0.08)] bg-[#0d0d0d]' : 'border-[rgba(0,0,0,0.08)] bg-[#0a0a0a]'
    }`} style={{ maxWidth: '520px' }}>
      {/* Header */}
      <div className="flex items-center gap-2 px-4 py-2 border-b border-[rgba(255,255,255,0.06)] bg-[rgba(255,255,255,0.03)]">
        <div className="flex items-center gap-1.5">
          <span className="w-2 h-2 rounded-full bg-red-500/80" />
          <span className="w-2 h-2 rounded-full bg-yellow-500/80" />
          <span className="w-2 h-2 rounded-full bg-green-500/80" />
        </div>
        <span className="mx-2 text-[rgba(255,255,255,0.35)] text-[10px] font-bold uppercase tracking-widest">Live Preview</span>
        {!ready && <Icon icon="mdi:loading" className="animate-spin text-xs text-blue-400" />}
        {(canDrag || canResize) && !isInteracting && (
          <span className="ml-auto flex items-center gap-1 px-2 py-0.5 rounded-full bg-[rgba(255,255,255,0.07)] text-[rgba(255,255,255,0.45)] text-[9px] font-semibold">
            <Icon icon="mdi:cursor-move" className="text-[10px]" />
            drag to move · corner handle to resize
          </span>
        )}
        {isDragging && (
          <span className="ml-auto flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-blue-500/25 border border-blue-500/40 text-blue-300 text-[9px] font-bold">
            <Icon icon="mdi:cursor-move" className="text-[10px]" />
            {watermarkX.toFixed(1)}%&nbsp;·&nbsp;{watermarkY.toFixed(1)}%
          </span>
        )}
        {isResizing && (
          <span className="ml-auto flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-violet-500/25 border border-violet-500/40 text-violet-300 text-[9px] font-bold">
            <Icon icon="mdi:resize-bottom-right" className="text-[10px]" />
            {watermarkScale.toFixed(1)}% wide
          </span>
        )}
      </div>

      {/* Video frame */}
      <div
        ref={containerRef}
        className="relative bg-black select-none"
        style={{ aspectRatio: '16/9', cursor: containerCursor, overflow: 'visible' }}
      >
        <video
          ref={videoRef}
          src={videoSrc}
          className="w-full h-full object-contain"
          onLoadedMetadata={handleMetadata}
          onTimeUpdate={handleTimeUpdate}
          onEnded={() => setIsPlaying(false)}
          muted
          playsInline
          preload="metadata"
        />

        {/* ── Watermark overlay ─────────────────────────────────────────────── */}
        {showWatermark && (
          /*
           * The wrapper is absolutely positioned with width = watermarkScale% of the
           * video container. This gives a reliable pixel size regardless of nesting.
           * The image inside uses width:100% of this wrapper.
           */
          <div
            style={{
              position: 'absolute',
              left:  `${watermarkX}%`,
              top:   `${watermarkY}%`,
              transform: 'translate(-50%, -50%)',
              // Width set here so img's 100% resolves against the video container
              width: watermarkType === 'image' ? `${watermarkScale}%` : 'auto',
              cursor: isDragging ? 'grabbing' : (canDrag ? 'grab' : 'default'),
              pointerEvents: canDrag ? 'auto' : 'none',
              userSelect: 'none',
              WebkitUserSelect: 'none',
              zIndex: 20,
            }}
            onMouseDown={(e) => { e.preventDefault(); e.stopPropagation(); startDrag(e.clientX, e.clientY); }}
            onTouchStart={(e) => { e.preventDefault(); e.stopPropagation(); startDrag(e.touches[0].clientX, e.touches[0].clientY); }}
          >
            {/* Watermark content */}
            {watermarkType === 'image' && wmImageUrl ? (
              <img
                src={wmImageUrl}
                alt="watermark"
                // width:100% fills the wrapper (which is watermarkScale% of container)
                style={{ width: '100%', opacity: wmOpacity, display: 'block' }}
                draggable={false}
              />
            ) : (
              <span
                style={{
                  opacity: wmOpacity,
                  // Font size tied to container width via scale — approximate for preview
                  fontSize: `${Math.max(10, watermarkScale * 1.1)}px`,
                  textShadow: '0 1px 6px rgba(0,0,0,0.95), 0 0 12px rgba(0,0,0,0.7)',
                  letterSpacing: '0.025em',
                  display: 'block',
                  whiteSpace: 'nowrap',
                }}
                className="text-white font-bold"
              >
                {watermarkText}
              </span>
            )}

            {/* Selection outline — visible while hovering or interacting */}
            {(canDrag || canResize) && (
              <div
                className={`absolute pointer-events-none rounded transition-all duration-100 ${
                  isInteracting
                    ? 'inset-[-4px] border-2 border-blue-400/90'
                    : 'inset-[-4px] border border-dashed border-white/0 hover:border-white/50'
                }`}
                style={{ zIndex: 1 }}
              />
            )}

            {/* ── Resize handle ─────────────────────────────────────────────── */}
            {canResize && (
              /*
               * Large transparent hit-area (p-3 = 24px padding all around) wrapping a
               * small visible square. This gives a ~40px clickable zone while keeping
               * the visual indicator small and non-intrusive.
               */
              <div
                className="absolute -bottom-5 -right-5"
                style={{ pointerEvents: 'auto', zIndex: 30 }}
              >
                <div
                  className="p-3 cursor-se-resize"
                  onMouseDown={(e) => { e.preventDefault(); e.stopPropagation(); startResize(e.clientX, e.clientY); }}
                  onTouchStart={(e) => { e.preventDefault(); e.stopPropagation(); startResize(e.touches[0].clientX, e.touches[0].clientY); }}
                  title="Drag to resize"
                >
                  <div className={`w-3.5 h-3.5 rounded-sm border-2 border-white shadow-lg flex items-center justify-center transition-all duration-100 ${
                    isResizing ? 'bg-violet-400 scale-125' : 'bg-blue-500 hover:bg-blue-400 hover:scale-110'
                  }`}>
                    <Icon icon="mdi:resize-bottom-right" className="text-white" style={{ fontSize: '7px' }} />
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {/* Loading */}
        {!ready && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 pointer-events-none">
            <Icon icon="mdi:loading" className="animate-spin text-3xl text-white/20" />
            <p className="text-[11px] text-white/30 font-medium">Loading video…</p>
          </div>
        )}

        {/* Play/pause — hidden while interacting with watermark */}
        {ready && !isInteracting && (
          <button
            onClick={togglePlay}
            className="absolute inset-0 flex items-center justify-center opacity-0 hover:opacity-100 transition-opacity"
            style={{ pointerEvents: canDrag ? 'none' : 'auto' }}
          >
            <div className="w-14 h-14 rounded-full bg-black/50 flex items-center justify-center backdrop-blur-sm border border-white/20 hover:scale-110 transition-transform">
              <Icon icon={isPlaying ? 'mdi:pause' : 'mdi:play'} className="text-white text-2xl ml-0.5" />
            </div>
          </button>
        )}
      </div>

      {/* Timeline */}
      {ready && duration > 0 && (
        <div className="px-4 py-2.5 flex items-center gap-3 bg-[rgba(255,255,255,0.02)] border-t border-[rgba(255,255,255,0.05)]">
          <button onClick={togglePlay} className="text-white/50 hover:text-white/80 transition-colors flex-shrink-0">
            <Icon icon={isPlaying ? 'mdi:pause' : 'mdi:play'} className="text-sm" />
          </button>
          <span className="text-[10px] font-mono tabular-nums text-white/35 min-w-[28px]">{formatTime(currentTime)}</span>
          <div className="flex-1 relative h-4 flex items-center">
            <div className="w-full h-1 rounded-full bg-white/10 overflow-hidden">
              <div
                className="h-full bg-gradient-to-r from-blue-500 to-blue-400 rounded-full transition-none"
                style={{ width: `${progress}%` }}
              />
            </div>
            <input
              type="range" min={0} max={duration} step={0.033} value={currentTime}
              onChange={handleSeek}
              className="absolute inset-0 w-full opacity-0 cursor-pointer"
            />
          </div>
          <span className="text-[10px] font-mono tabular-nums text-white/35 min-w-[28px] text-right">{formatTime(duration)}</span>
        </div>
      )}

      {/* ── Visual trim track — drag the handles to crop the clip ──────────────── */}
      {trimEnabled && (
        <div className="px-4 py-3 bg-[rgba(255,255,255,0.02)] border-t border-[rgba(255,255,255,0.05)]">
          <div className="flex items-center justify-between mb-2">
            <span className="flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-widest text-white/40">
              <Icon icon="mdi:content-cut" className="text-[11px]" />
              Trim
            </span>
            <div className="flex items-center gap-2">
              <span className="text-[10px] font-mono tabular-nums text-white/45">
                {formatTime(tStart)} → {formatTime(tEnd)}
              </span>
              <span className="px-1.5 py-0.5 rounded bg-blue-500/15 text-blue-300 text-[9px] font-bold tabular-nums">
                {formatTime(Math.max(0, tEnd - tStart))}
              </span>
              {(tStart > 0 || tEnd < duration) && onTrimChange && (
                <button onClick={() => onTrimChange(0, duration)}
                  className="text-white/35 hover:text-white/70 transition-colors" title="Reset trim">
                  <Icon icon="mdi:backup-restore" className="text-[13px]" />
                </button>
              )}
            </div>
          </div>

          {/* Track */}
          <div ref={trimTrackRef} className="relative h-10 rounded-lg bg-white/[0.04] select-none" style={{ touchAction: 'none' }}>
            {/* Dimmed regions outside the selection */}
            <div className="absolute inset-y-0 left-0 bg-black/40 rounded-l-lg" style={{ width: `${(tStart / duration) * 100}%` }} />
            <div className="absolute inset-y-0 right-0 bg-black/40 rounded-r-lg" style={{ width: `${(1 - tEnd / duration) * 100}%` }} />
            {/* Selected region */}
            <div className="absolute inset-y-0 bg-blue-500/10 border-y-2 border-blue-400/70"
              style={{ left: `${(tStart / duration) * 100}%`, right: `${(1 - tEnd / duration) * 100}%` }} />
            {/* Playhead */}
            <div className="absolute top-0 bottom-0 w-px bg-white/70 pointer-events-none" style={{ left: `${(currentTime / duration) * 100}%` }} />

            {/* Start handle */}
            <div className="absolute top-0 bottom-0" style={{ left: `${(tStart / duration) * 100}%`, transform: 'translateX(-50%)' }}>
              <div
                className="h-full px-1.5 cursor-ew-resize flex items-center"
                style={{ pointerEvents: 'auto' }}
                onMouseDown={(e) => { e.preventDefault(); e.stopPropagation(); setTrimDragging('start'); }}
                onTouchStart={(e) => { e.preventDefault(); e.stopPropagation(); setTrimDragging('start'); }}
                title="Drag to set start"
              >
                <div className={`w-2 h-full rounded-md flex items-center justify-center shadow-lg transition-colors ${trimDragging === 'start' ? 'bg-blue-300' : 'bg-blue-400 hover:bg-blue-300'}`}>
                  <div className="w-px h-3.5 bg-white/85" />
                </div>
              </div>
            </div>

            {/* End handle */}
            <div className="absolute top-0 bottom-0" style={{ left: `${(tEnd / duration) * 100}%`, transform: 'translateX(-50%)' }}>
              <div
                className="h-full px-1.5 cursor-ew-resize flex items-center"
                style={{ pointerEvents: 'auto' }}
                onMouseDown={(e) => { e.preventDefault(); e.stopPropagation(); setTrimDragging('end'); }}
                onTouchStart={(e) => { e.preventDefault(); e.stopPropagation(); setTrimDragging('end'); }}
                title="Drag to set end"
              >
                <div className={`w-2 h-full rounded-md flex items-center justify-center shadow-lg transition-colors ${trimDragging === 'end' ? 'bg-blue-300' : 'bg-blue-400 hover:bg-blue-300'}`}>
                  <div className="w-px h-3.5 bg-white/85" />
                </div>
              </div>
            </div>
          </div>

          <p className="text-[9px] text-white/30 mt-1.5 flex items-center gap-1">
            <Icon icon="mdi:gesture-tap-hold" className="text-[10px]" />
            Drag the blue handles to set where the clip starts &amp; ends
          </p>
        </div>
      )}
    </div>
  );
}
