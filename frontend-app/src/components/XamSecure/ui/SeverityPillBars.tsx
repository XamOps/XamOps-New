import { useCountUp, usePrefersReducedMotion } from './tokens';
import SeverityLetterBadge from './SeverityLetterBadge';

/** Opacity encoding by rank: C 100%, H 70%, M 40%, Other/L 25%. */
const H_OPACITY = [1, 0.7, 0.4, 0.25];
const V_OPACITY = [1, 0.7, 0.4];

interface HRow {
  sev: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';
  value: number;
}

interface HorizontalProps {
  mode: 'horizontal';
  rows: HRow[];
  max: number;
  caption?: string;
  onRowClick?: (sev: HRow['sev']) => void;
}

interface VCol {
  sev: string;
  value: number;
}

interface VerticalProps {
  mode: 'vertical';
  cols: VCol[];
  max: number;
  /** Footer line, e.g. "{totalIssues} open issues". */
  footerValue?: number;
  footerLabel?: string;
  /** Tallest bar in px (plot area ≈ this). Defaults to 150; pass smaller to fit a shared card. */
  barMax?: number;
  onColClick?: (sev: string) => void;
}

type Props = HorizontalProps | VerticalProps;

const SEV_LABEL: Record<string, string> = { CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low', OTHER: 'Other' };

/** Horizontal mini-bars: a letter badge + a metal-blue pill whose width ∝ value/max. */
const Horizontal: React.FC<HorizontalProps> = ({ rows, max, caption, onRowClick }) => {
  const reduced = usePrefersReducedMotion();
  return (
    <div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {rows.map((row, i) => {
          const pct = max > 0 ? Math.min(100, (row.value / max) * 100) : 0;
          const clickable = !!onRowClick;
          const inner = (
            <>
              <SeverityLetterBadge severity={row.sev} size={18} />
              <span style={{ display: 'flex', alignItems: 'center', flex: '1 1 auto', minWidth: 0 }}>
                {row.value > 0 ? (
                  <span
                    data-testid={`hbar-${row.sev}`}
                    style={{
                      height: 8,
                      width: `${pct}%`,
                      minWidth: 2,
                      maxWidth: 130,
                      borderRadius: 'var(--xs-radius-pill)',
                      background: 'var(--xs-metal-02)',
                      opacity: H_OPACITY[i] ?? 0.25,
                      transition: reduced ? 'none' : 'width .6s ease',
                    }}
                  />
                ) : (
                  <span data-testid={`hbar-zero-${row.sev}`} style={{ width: 6, height: 6, borderRadius: '999px', background: 'var(--xs-text-tertiary)' }} />
                )}
              </span>
              <span style={{ fontSize: 14, fontWeight: 400, color: 'var(--xs-text-primary)', width: 32, textAlign: 'right' }}>{row.value}</span>
            </>
          );
          const style: React.CSSProperties = {
            display: 'flex', alignItems: 'center', gap: 8, height: 33, width: '100%',
            padding: '0 4px', borderRadius: 'var(--xs-radius-chip)', border: 'none', background: 'transparent', textAlign: 'left',
          };
          return clickable ? (
            <button key={row.sev} type="button" className="xs-hoverable" style={{ ...style, cursor: 'pointer' }} onClick={() => onRowClick?.(row.sev)}>
              {inner}
            </button>
          ) : (
            <div key={row.sev} style={style}>{inner}</div>
          );
        })}
      </div>
      {caption && <p style={{ fontSize: 12, color: 'var(--xs-text-secondary)', margin: '12px 0 0' }}>{caption}</p>}
    </div>
  );
};

/** One vertical bar in the grouped-pill mode, with its own count-up grow-in. */
const VBar: React.FC<{ col: VCol; max: number; opacity: number; barMax: number; onClick?: () => void }> = ({ col, max, opacity, barMax, onClick }) => {
  const reduced = usePrefersReducedMotion();
  const grown = useCountUp(col.value, 600);
  const basis = reduced ? col.value : grown;
  const h = max > 0 ? Math.round((basis / max) * barMax) : 0;
  const clickable = !!onClick;
  const inner = (
    <>
      <span style={{ fontSize: 14, fontWeight: 500, color: 'var(--xs-pink-00)' }}>{col.value}</span>
      {col.value > 0 ? (
        <span
          data-testid={`vbar-${col.sev}`}
          style={{ width: 10, height: h, minHeight: 4, borderRadius: 'var(--xs-radius-pill)', background: 'var(--xs-pink-00)', opacity, transition: reduced ? 'none' : 'height .6s ease' }}
        />
      ) : (
        <span data-testid={`vbar-zero-${col.sev}`} style={{ width: 6, height: 6, borderRadius: '999px', background: 'var(--xs-text-tertiary)' }} />
      )}
      <SeverityLetterBadge severity={col.sev} size={18} />
    </>
  );
  const style: React.CSSProperties = {
    display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'flex-end', gap: 6,
    width: 45, padding: '4px 8px 8px', borderRadius: 'var(--xs-radius-chip)', border: 'none', background: 'transparent',
  };
  return clickable ? (
    <button type="button" className="xs-hoverable" style={{ ...style, cursor: 'pointer' }} onClick={onClick}>{inner}</button>
  ) : (
    <div style={style}>{inner}</div>
  );
};

/** Vertical grouped pills: a single "New" group (no Resolved — omitted, no datum). */
const Vertical: React.FC<VerticalProps> = ({ cols, max, footerValue, footerLabel, barMax = 150, onColClick }) => (
  <div style={{ display: 'flex', flexDirection: 'column' }}>
    <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'center', gap: 24, minHeight: barMax + 30 }}>
      {cols.map((col, i) => (
        <VBar key={col.sev} col={col} max={max} barMax={barMax} opacity={V_OPACITY[i] ?? 0.4} onClick={onColClick ? () => onColClick(col.sev) : undefined} />
      ))}
    </div>
    {typeof footerValue === 'number' && (
      <div style={{ borderTop: '1px solid var(--xs-border-subtle)', marginTop: 8, paddingTop: 8, display: 'flex', alignItems: 'baseline', gap: 6 }}>
        <span style={{ fontSize: 18, fontWeight: 400, color: 'var(--xs-pink-00)' }}>{footerValue}</span>
        <span style={{ fontSize: 14, fontWeight: 400, color: 'var(--xs-text-secondary)' }}>{footerLabel ?? 'open issues'}</span>
      </div>
    )}
  </div>
);

/** Severity pill bars — horizontal mini-bars or a vertical grouped pill set. */
const SeverityPillBars: React.FC<Props> = (props) => {
  // The Resolved group never renders: the component takes a single `cols` group only.
  return props.mode === 'horizontal' ? <Horizontal {...props} /> : <Vertical {...props} />;
};

export { SEV_LABEL };
export default SeverityPillBars;
