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
  /** Milliseconds each rotating message stays before switching. Default 2000. */
  messageInterval?: number;
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

/**
 * XamOps loader — an animated "X" that draws itself inside a spinning arc,
 * with an optional continuously-rotating status message.
 *
 *   <XamOpsLoader />                                   // inline, 44px
 *   <XamOpsLoader size={20} />                         // small (buttons)
 *   <XamOpsLoader fullScreen />                        // rotates default messages
 *   <XamOpsLoader fullScreen messages={['One…','Two…']} />
 *   <XamOpsLoader fullScreen label="Loading…" />       // single fixed message
 */
const XamOpsLoader: React.FC<XamOpsLoaderProps> = ({
  size = 44,
  fullScreen = false,
  label,
  messages,
  messageInterval = 2000,
  dark = false,
  className = '',
}) => {
  const r = 19;
  const circumference = 2 * Math.PI * r;

  // Rotating message logic — only when full-screen and no fixed label given.
  const rotating = fullScreen && !label;
  const list = messages && messages.length > 0 ? messages : DEFAULT_MESSAGES;
  const [index, setIndex] = useState(0);

  useEffect(() => {
    if (!rotating) return;
    const id = setInterval(() => {
      setIndex((i) => (i + 1) % list.length);
    }, messageInterval);
    return () => clearInterval(id);
  }, [rotating, list.length, messageInterval]);

  const mark = (
    <span className={`xo-loader ${className}`} style={{ width: size, height: size }} role="status" aria-label="Loading">
      <svg viewBox="0 0 44 44" width="100%" height="100%" aria-hidden="true">
        <circle className="xo-loader__track" cx="22" cy="22" r={r} fill="none" strokeWidth="2" />
        <circle
          className="xo-loader__arc"
          cx="22" cy="22" r={r} fill="none" strokeWidth="2" strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={circumference * 0.72}
        />
        <g className="xo-loader__x">
          <line x1="15" y1="15" x2="29" y2="29" strokeWidth="3" strokeLinecap="round" />
          <line x1="29" y1="15" x2="15" y2="29" strokeWidth="3" strokeLinecap="round" />
        </g>
      </svg>
    </span>
  );

  if (!fullScreen) return mark;

  return (
    <div className={`xo-loader-screen ${dark ? 'xo-dark' : ''}`}>
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
