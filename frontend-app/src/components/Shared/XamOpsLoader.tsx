import React, { useEffect, useState } from 'react';
import './XamOpsLoader.css';

interface XamOpsLoaderProps {
  /** Diameter in pixels. Default 44. */
  size?: number;
  /** Render as a full-screen centered overlay. */
  fullScreen?: boolean;
  /** A single fixed label. Ignored if `messages` is provided. */
  label?: string;
  /** Rotating messages that cycle continuously (full-screen mode). */
  messages?: string[];
  /** Milliseconds each rotating message stays before switching. Default 2600.
      The first message always holds at least 3s so short loads read calmly. */
  messageInterval?: number;
  /** Milliseconds to stay invisible before showing. Loads that finish faster
      than this never flash a spinner. Default 200. Pass 0 to show instantly. */
  delay?: number;
  /** Dark background for the full-screen overlay (defaults to light). */
  dark?: boolean;
  className?: string;
}

const DEFAULT_MESSAGES = [
  'Loading your dashboard…',
  'Fetching your cloud accounts…',
  'Crunching cost data…',
  'Checking security posture…',
  'Optimizing your view…',
  'Almost there…',
];

const FIRST_MESSAGE_HOLD_MS = 3000;

/**
 * XamOps loader — the brand chevron pair (») that draws itself inside a
 * spinning brand-green arc, with an optional rotating status message.
 *
 *   <XamOpsLoader />                                   // inline, 44px
 *   <XamOpsLoader size={20} delay={0} />               // small (buttons), instant
 *   <XamOpsLoader fullScreen />                        // rotates default messages
 *   <XamOpsLoader fullScreen messages={['One…','Two…']} />
 *   <XamOpsLoader fullScreen label="Loading…" />       // single fixed message
 */
const XamOpsLoader: React.FC<XamOpsLoaderProps> = ({
  size = 44,
  fullScreen = false,
  label,
  messages,
  messageInterval = 2600,
  delay = 200,
  dark = false,
  className = '',
}) => {
  const r = 19;
  const circumference = 2 * Math.PI * r;

  // Anti-flash: stay invisible until `delay` has passed. Space is still
  // reserved (visibility, not unmount) so inline layouts don't shift.
  const [visible, setVisible] = useState(delay <= 0);
  useEffect(() => {
    if (delay <= 0) return;
    const t = setTimeout(() => setVisible(true), delay);
    return () => clearTimeout(t);
  }, [delay]);

  // Rotating message logic — only when full-screen and no fixed label given.
  // The first message holds longer than the rest: most loads finish inside
  // it, so users aren't flashed with a mid-rotation message.
  const rotating = fullScreen && !label;
  const list = messages && messages.length > 0 ? messages : DEFAULT_MESSAGES;
  const [index, setIndex] = useState(0);

  useEffect(() => {
    if (!rotating) return;
    const hold = index === 0 ? Math.max(messageInterval, FIRST_MESSAGE_HOLD_MS) : messageInterval;
    const t = setTimeout(() => setIndex((i) => (i + 1) % list.length), hold);
    return () => clearTimeout(t);
  }, [rotating, index, list.length, messageInterval]);

  const mark = (
    <span
      className={`xo-loader ${visible ? '' : 'xo-loader--waiting'} ${className}`}
      style={{ width: size, height: size }}
      role="status"
      aria-label="Loading"
    >
      <svg viewBox="0 0 44 44" width="100%" height="100%" aria-hidden="true">
        <circle className="xo-loader__track" cx="22" cy="22" r={r} fill="none" strokeWidth="2" />
        <circle
          className="xo-loader__arc"
          cx="22" cy="22" r={r} fill="none" strokeWidth="2" strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={circumference * 0.72}
        />
        {/* Brand mark: the » chevron pair, drawn back-then-front */}
        <g className="xo-loader__mark" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
          <path className="xo-loader__chev-back" d="M13.5 14.5 L21 22 L13.5 29.5" />
          <path className="xo-loader__chev-front" d="M23 14.5 L30.5 22 L23 29.5" />
        </g>
      </svg>
    </span>
  );

  if (!fullScreen) return mark;

  return (
    <div className={`xo-loader-screen ${dark ? 'xo-dark' : ''} ${visible ? '' : 'xo-loader--waiting'}`}>
      {mark}
      {(rotating || label) && (
        // key changes on each message so the fade-in animation re-triggers
        <div key={rotating ? index : 'fixed'} className="xo-loader__label">
          {rotating ? list[index] : label}
        </div>
      )}
    </div>
  );
};

export default XamOpsLoader;
