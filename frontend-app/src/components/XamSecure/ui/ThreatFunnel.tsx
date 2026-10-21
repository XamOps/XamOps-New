import React, { useId } from 'react';
import { ribbonPath, type Point } from '../funnelPath';

interface Stage {
  label: string;
  value: number;
}

interface Props {
  events: number;
  detections: number;
  stories: number;
}

const VB_W = 360;
const VB_H = 150;
const PAD_X = 18;
const MID_Y = 70;
const MIN_HALF = 6;   // floor so a tiny/zero stage still reads as a thin ribbon
const MAX_HALF = 46;  // half-height of the widest (first) stage

/**
 * Noise-to-signal threat funnel drawn as a single flowing ribbon that narrows from Events to
 * Detections to Stories. Uses `ribbonPath` from funnelPath so the profile is one smooth curve
 * with level tangents at each stage rather than wedged corners.
 *
 * Light-only styling: a left-to-right brand gradient fill plus a soft low-alpha drop shadow for
 * depth (not a glow). Scales to the panel width via a viewBox. Accessible via role="img" and an
 * aria-label that summarises the three stages.
 */
const ThreatFunnel: React.FC<Props> = ({ events, detections, stories }) => {
  const uid = useId().replace(/[:]/g, '');
  const gradId = `funnel-grad-${uid}`;
  const shadowId = `funnel-shadow-${uid}`;

  const stages: Stage[] = [
    { label: 'Events', value: events },
    { label: 'Detections', value: detections },
    { label: 'Stories', value: stories },
  ];

  // Half-heights scale proportionally to the largest value, so the ribbon narrows left→right
  // in step with the real funnel. The first stage anchors the max; a zero top stage still
  // shows a readable sliver via MIN_HALF.
  const peak = Math.max(1, events, detections, stories);
  const xs = [PAD_X, VB_W / 2, VB_W - PAD_X];
  const halves = stages.map((s) =>
    Math.max(MIN_HALF, Math.round((s.value / peak) * MAX_HALF)));

  const upper: Point[] = xs.map((x, i) => [x, MID_Y - halves[i]] as const);
  const lower: Point[] = xs.map((x, i) => [x, MID_Y + halves[i]] as const);
  const path = ribbonPath(upper, lower);

  const ariaLabel =
    `Threat funnel: ${events.toLocaleString()} events narrow to `
    + `${detections.toLocaleString()} detections and ${stories.toLocaleString()} stories.`;

  return (
    <div className="py-2" role="img" aria-label={ariaLabel}>
      <svg
        viewBox={`0 0 ${VB_W} ${VB_H}`}
        width="100%"
        preserveAspectRatio="xMidYMid meet"
        className="block"
      >
        <defs>
          <linearGradient id={gradId} x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#2563eb" stopOpacity={0.9} />
            <stop offset="55%" stopColor="#7c3aed" stopOpacity={0.9} />
            <stop offset="100%" stopColor="#0891b2" stopOpacity={0.92} />
          </linearGradient>
          <filter id={shadowId} x="-20%" y="-40%" width="140%" height="180%">
            <feDropShadow dx="0" dy="3" stdDeviation="4" floodColor="#2563eb" floodOpacity={0.18} />
          </filter>
        </defs>

        <path d={path} fill={`url(#${gradId})`} filter={`url(#${shadowId})`} />

        {stages.map((s, i) => {
          const x = xs[i];
          const anchor = i === 0 ? 'start' : i === stages.length - 1 ? 'end' : 'middle';
          const dx = i === 0 ? -2 : i === stages.length - 1 ? 2 : 0;
          return (
            <g key={s.label}>
              <text
                x={x + dx}
                y={MID_Y - MAX_HALF - 8}
                textAnchor={anchor}
                className="fill-gray-900"
                style={{ fontSize: 15, fontWeight: 800 }}
              >
                {s.value.toLocaleString()}
              </text>
              <text
                x={x + dx}
                y={MID_Y + MAX_HALF + 20}
                textAnchor={anchor}
                className="fill-gray-400"
                style={{ fontSize: 9, fontWeight: 700, letterSpacing: '0.05em' }}
              >
                {s.label.toUpperCase()}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
};

export default ThreatFunnel;
