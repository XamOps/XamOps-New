import { SEV_TOKENS, type SevKey } from './tokens';

interface Counts {
  CRITICAL: number;
  HIGH: number;
  MEDIUM: number;
  LOW: number;
}

interface Props {
  counts: Counts;
  onSegmentClick?: (sev: SevKey) => void;
}

const ORDER: SevKey[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
const LABEL: Record<SevKey, string> = { CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low' };

/**
 * A 6px severity gradient line (segment widths ∝ counts; a 0 count collapses its segment) plus a
 * legend row of `●count Label` items. Counts come straight from `findingsBySeverity`; clicking a
 * legend item deep-links the filtered list.
 */
const SeverityGradientBar: React.FC<Props> = ({ counts, onSegmentClick }) => {
  const total = ORDER.reduce((a, s) => a + (counts[s] || 0), 0);

  return (
    <div>
      <div
        data-testid="severity-gradient-bar"
        style={{ display: 'flex', width: '100%', height: 6, borderRadius: 'var(--xs-radius-pill)', overflow: 'hidden', background: 'var(--xs-bg-secondary)' }}
      >
        {ORDER.map((sev) => {
          const value = counts[sev] || 0;
          const pct = total > 0 ? (value / total) * 100 : 0;
          if (pct === 0) return null;
          return (
            <span
              key={sev}
              data-testid={`gradient-segment-${sev}`}
              style={{ width: `${pct}%`, height: '100%', background: SEV_TOKENS[sev].color }}
            />
          );
        })}
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, marginTop: 10 }}>
        {ORDER.map((sev) => {
          const value = counts[sev] || 0;
          const clickable = !!onSegmentClick;
          const content = (
            <>
              <span style={{ width: 8, height: 8, borderRadius: '999px', background: SEV_TOKENS[sev].color, flexShrink: 0 }} aria-hidden />
              <span style={{ fontSize: 14, fontWeight: 500, color: 'var(--xs-text-primary)' }}>{value}</span>
              <span style={{ fontSize: 14, fontWeight: 400, color: 'var(--xs-text-secondary)' }}>{LABEL[sev]}</span>
            </>
          );
          const style: React.CSSProperties = {
            display: 'inline-flex',
            alignItems: 'center',
            gap: 4,
            padding: '2px 4px',
            borderRadius: 'var(--xs-radius-chip)',
            border: 'none',
            background: 'transparent',
          };
          return clickable ? (
            <button key={sev} type="button" className="xs-hoverable" style={{ ...style, cursor: 'pointer' }} onClick={() => onSegmentClick?.(sev)}>
              {content}
            </button>
          ) : (
            <span key={sev} style={style}>{content}</span>
          );
        })}
      </div>

      <div style={{ borderTop: '1px solid var(--xs-border-subtle)', marginTop: 16 }} />
    </div>
  );
};

export default SeverityGradientBar;
