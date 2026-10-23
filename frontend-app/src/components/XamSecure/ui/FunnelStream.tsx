import { Icon } from '@iconify/react';
import { ribbonPath, type Point } from '../funnelPath';

interface Props {
  events: number;
  detections: number;
  stories: number;
  onAllStories?: () => void;
  children?: React.ReactNode;
}

const W = 100; // viewBox width units; the SVG scales to the container via preserveAspectRatio.
const H = 50;

/**
 * Build a tapering single-hue streamgraph from the three stage values. The three stages are
 * normalized to the 50px band (events = widest at left → stories thins to ~0 at right). Three
 * stacked layers all red-00, differing only in opacity, so the taper is decorative — the real
 * numbers are the three stat columns, never the ribbon geometry.
 */
function layerPaths(events: number, detections: number, stories: number): string[] {
  const peak = Math.max(1, events, detections, stories);
  // Half-heights per stage (0..H/2), proportional to each value.
  const half = (v: number) => (v / peak) * (H / 2) * 0.9;
  const mid = H / 2;
  const stages: Array<{ x: number; h: number }> = [
    { x: 0, h: half(events) },
    { x: W / 2, h: half(detections) },
    { x: W, h: half(stories) },
  ];

  // Three nested layers: the back layer is full width, each front layer scales down so the ribbons
  // read as stacked bands that all thin toward the right.
  const scales = [1, 0.66, 0.33];
  return scales.map((s) => {
    const upper: Point[] = stages.map((st) => [st.x, mid - st.h * s] as Point);
    const lower: Point[] = stages.map((st) => [st.x, mid + st.h * s] as Point);
    return ribbonPath(upper, lower);
  });
}

const StatCol: React.FC<{ icon: string; label: string; value: number; color: string }> = ({ icon, label, value, color }) => (
  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2, flex: 1 }}>
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 14, fontWeight: 400, color: 'var(--xs-text-secondary)' }}>
      <Icon icon={icon} width={14} height={14} color={color} aria-hidden />
      {label}
    </span>
    <span style={{ fontSize: 18, fontWeight: 500, color: 'var(--xs-text-primary)' }}>{value}</span>
  </div>
);

/**
 * Noise-to-signal funnel: three stat columns (Events → Detections → Stories) above a decorative
 * tapering streamgraph, with an optional story list below.
 */
const FunnelStream: React.FC<Props> = ({ events, detections, stories, onAllStories, children }) => {
  const paths = layerPaths(events, detections, stories);
  const opacities = [0.4, 0.6, 1];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
        <StatCol icon="mdi:pulse" label="Events" value={events} color="var(--xs-purple-02)" />
        <StatCol icon="mdi:shield-alert-outline" label="Detections" value={detections} color="var(--xs-pink-00)" />
        <StatCol icon="mdi:script-text-outline" label="Stories" value={stories} color="var(--xs-red-03)" />
      </div>

      <svg
        data-testid="funnel-stream"
        viewBox={`0 0 ${W} ${H}`}
        preserveAspectRatio="none"
        style={{ width: '100%', height: 50, margin: '12px 0' }}
        aria-hidden
      >
        {paths.map((d, i) => (
          <path key={i} data-testid={`funnel-layer-${i}`} d={d} fill="var(--xs-red-00)" opacity={opacities[i]} />
        ))}
      </svg>

      {onAllStories && (
        <button
          type="button"
          onClick={onAllStories}
          style={{
            alignSelf: 'flex-start', border: 'none', background: 'transparent', padding: 0,
            fontSize: 14, fontWeight: 500, color: 'var(--xs-blue-02)', textDecoration: 'underline', cursor: 'pointer',
          }}
        >
          All stories
        </button>
      )}

      {children != null && <div className="xs-card-body" style={{ marginTop: 8 }}>{children}</div>}
    </div>
  );
};

export default FunnelStream;
