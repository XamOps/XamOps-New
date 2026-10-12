/**
 * The 7-day per-workload activity timeline (design-delta §3 "What automation did, last 7 days";
 * glossary §10). Plain inline SVG in CapacityDonut's style: one lane per workload, a greyscale
 * baseline over the window, in-place intervals as faint bars, and one dot per Activity entry placed
 * at its time. A dot's colour is the status tone of its outcome.
 *
 * The Activity SOURCE dots are the only place a feature accent appears inside a plot area
 * (design-delta §2.7 check 4): a small accent dot at the lane's left marks which feature recorded
 * the lane. The status-tone marker colours and the feature-accent colours are read from chartTokens
 * (the one file allowed raw `var(--k8s-…)`) and passed as inline `fill`/`stroke`, so charts/ carries
 * no status or feature class and the token-scope guard stays green. Series classes (used/capacity)
 * are allowed in charts/ and used for the greyscale baseline and the in-place bars.
 *
 * Deterministic: entries map to the same x by their time, with no wall-clock dependence (the window
 * is passed in, not read from Date.now()).
 */
import { FEATURE_COLOR, TONE_COLOR, type FeatureAccent, type SeriesTone } from './chartTokens';

export interface LaneEntry {
  /** Position in the window, 0..1 (its time mapped onto [from, to]). */
  at: number;
  /** The outcome's status tone. */
  tone: SeriesTone;
  /** A dry run is drawn as an outline rather than a filled dot. */
  dryRun?: boolean;
  /** An accessible label for the marker. */
  label?: string;
}

export interface Lane {
  /** The workload name, shown at the lane's left. */
  name: string;
  /** The feature that recorded the lane (its accent dot). */
  feature: FeatureAccent;
  entries: LaneEntry[];
  /** In-place intervals as [from, to] fractions of the window. */
  inPlace?: Array<{ from: number; to: number }>;
}

export interface LanesTimelineProps {
  lanes: Lane[];
  /** The fraction (0..1) before the cluster existed, drawn as a hatched "no history" block. */
  noHistoryBefore?: number;
  width?: number;
  /** Height of one lane row in px. */
  laneHeight?: number;
  ariaLabel?: string;
  className?: string;
}

const LABEL_W = 120;
const DOT_R = 3.5;

export function LanesTimeline({
  lanes,
  noHistoryBefore = 0,
  width = 480,
  laneHeight = 22,
  ariaLabel,
  className = '',
}: LanesTimelineProps) {
  const plotW = width - LABEL_W;
  const height = Math.max(laneHeight, lanes.length * laneHeight);
  const clampFrac = (v: number) => Math.max(0, Math.min(1, v));
  const toX = (frac: number) => LABEL_W + clampFrac(frac) * plotW;

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      className={className}
      role="img"
      aria-label={ariaLabel}
      data-lanes-timeline=""
      focusable="false"
    >
      <defs>
        <pattern id="k8s-lanes-no-history" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <line x1="0" y1="0" x2="0" y2="6" className="stroke-series-capacity" strokeWidth="1" />
        </pattern>
      </defs>

      {noHistoryBefore > 0 && (
        <rect
          x={LABEL_W}
          y="0"
          width={(clampFrac(noHistoryBefore) * plotW).toFixed(2)}
          height={height}
          fill="url(#k8s-lanes-no-history)"
          data-no-history=""
        />
      )}

      {lanes.map((lane, li) => {
        const cy = li * laneHeight + laneHeight / 2;
        return (
          <g key={lane.name} data-lane={lane.name}>
            {/* The feature accent dot at the lane's left: the only feature colour in a plot area. */}
            <circle cx={LABEL_W - 10} cy={cy} r={DOT_R} fill={FEATURE_COLOR[lane.feature]} data-feature-dot={lane.feature} />
            <text x="0" y={cy + 3} className="fill-current text-[11px]" fillOpacity={0.72}>
              {lane.name}
            </text>
            {/* Greyscale baseline. */}
            <line x1={LABEL_W} y1={cy} x2={width} y2={cy} className="stroke-series-capacity" strokeOpacity={0.25} strokeWidth="1" />
            {(lane.inPlace ?? []).map((iv, i) => (
              <rect
                key={`ip-${i}`}
                x={toX(iv.from)}
                y={cy - 3}
                width={Math.max(0, clampFrac(iv.to) - clampFrac(iv.from)) * plotW}
                height="6"
                rx="2"
                className="fill-series-used"
                fillOpacity={0.2}
              />
            ))}
            {lane.entries.map((e, i) =>
              e.dryRun ? (
                <circle key={`e-${i}`} cx={toX(e.at)} cy={cy} r={DOT_R} fill="none" stroke={TONE_COLOR[e.tone]} strokeWidth="1.5">
                  {e.label && <title>{e.label}</title>}
                </circle>
              ) : (
                <circle key={`e-${i}`} cx={toX(e.at)} cy={cy} r={DOT_R} fill={TONE_COLOR[e.tone]}>
                  {e.label && <title>{e.label}</title>}
                </circle>
              ),
            )}
          </g>
        );
      })}
    </svg>
  );
}

export default LanesTimeline;
