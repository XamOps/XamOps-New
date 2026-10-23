import { usePrefersReducedMotion } from './tokens';

interface Bar {
  label: string;
  value: number;
}

interface Props {
  data: Bar[];
  /** Pixel height of the plot band. Default 120. */
  height?: number;
}

/**
 * 7-day cumulative open-issue pill bars (plain divs). Width-less vertical bars in metal-blue;
 * height ∝ value/max, with a 6px dot when a day is zero. Grows in from 0 unless reduced-motion.
 */
const FindingsOverTimeBars: React.FC<Props> = ({ data, height = 120 }) => {
  const reduced = usePrefersReducedMotion();
  const max = Math.max(1, ...data.map((d) => d.value));

  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 6, height: height + 20 }}>
      {data.map((d) => {
        const h = Math.round((d.value / max) * height);
        return (
          <div key={d.label} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4, flex: 1, minWidth: 0 }}>
            <div style={{ height, display: 'flex', alignItems: 'flex-end' }}>
              {d.value > 0 ? (
                <div
                  data-testid={`fot-bar-${d.label}`}
                  style={{
                    width: 10,
                    height: h,
                    minHeight: 4,
                    borderRadius: 'var(--xs-radius-pill)',
                    background: 'var(--xs-metal-02)',
                    transition: reduced ? 'none' : 'height .6s ease',
                  }}
                  title={`${d.label}: ${d.value}`}
                />
              ) : (
                <div data-testid={`fot-zero-${d.label}`} style={{ width: 6, height: 6, borderRadius: '999px', background: 'var(--xs-text-tertiary)' }} title={`${d.label}: 0`} />
              )}
            </div>
            <span style={{ fontSize: 12, fontWeight: 400, color: 'var(--xs-text-secondary)', whiteSpace: 'nowrap' }}>{d.label}</span>
          </div>
        );
      })}
    </div>
  );
};

export default FindingsOverTimeBars;
