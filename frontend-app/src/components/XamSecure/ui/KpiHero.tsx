import { Icon } from '@iconify/react';
import { useCountUp } from './tokens';

type Tone = 'good' | 'warn' | 'bad' | 'neutral';

interface Props {
  icon: string;
  /** The count (the page's existing hero value — NOT a fabricated percentage/score). */
  value: number;
  label: string;
  desc?: string;
  tone?: Tone;
  onClick?: () => void;
  /** Story list (or any supplemental content) rendered below the hero number. */
  children?: React.ReactNode;
}

const TONE: Record<Tone, { color: string; tint: string }> = {
  neutral: { color: 'var(--xs-blue-02)', tint: 'var(--xs-blue-06)' },
  good: { color: 'var(--xs-green-00)', tint: 'var(--xs-green-06)' },
  warn: { color: 'var(--xs-amber-00)', tint: 'var(--xs-amber-06)' },
  bad: { color: 'var(--xs-pink-00)', tint: 'var(--xs-pink-06)' },
};

/**
 * KPI hero: the big 80/300 count-up with a heartbeat tint circle behind it, and a zero-state
 * scanner sweep over the icon. Count-up and heartbeat collapse to the static final state under
 * reduced-motion (via `useCountUp` + the CSS media block).
 *
 * When `children` is present (the story list), it renders below the hero number so the card shows
 * the stories themselves, not just a bare count.
 */
const KpiHero: React.FC<Props> = ({ icon, value, label, desc, tone = 'neutral', onClick, children }) => {
  const display = useCountUp(value);
  const t = TONE[tone];
  const isZero = value === 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', height: '100%', minHeight: 0 }}>
      <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', alignItems: 'center', paddingTop: 4 }}>
        {/* heartbeat tint circle */}
        <span
          className="xs-heartbeat"
          aria-hidden
          style={{
            position: 'absolute',
            top: -8,
            width: 160,
            height: 160,
            borderRadius: '999px',
            background: t.tint,
            zIndex: 0,
          }}
        />
        {/* icon + zero-state scanner */}
        <span style={{ position: 'relative', zIndex: 1, display: 'inline-flex', overflow: 'hidden' }}>
          <Icon icon={icon} width={48} height={48} color={t.color} aria-hidden />
          {isZero && (
            <span
              className="xs-scanner"
              aria-hidden
              style={{
                position: 'absolute',
                left: 0,
                top: 0,
                width: '100%',
                height: '40%',
                background: 'linear-gradient(180deg, rgba(35,224,133,0) 0%, rgba(35,224,133,.5) 50%, rgba(35,224,133,0) 100%)',
              }}
            />
          )}
        </span>
        <span
          data-testid="kpi-hero-value"
          style={{ position: 'relative', zIndex: 1, fontSize: 80, fontWeight: 300, lineHeight: 1, marginTop: 16, color: t.color }}
        >
          {display}
        </span>
      </div>
      <p style={{ fontSize: 24, fontWeight: 400, margin: '8px 0 0', color: 'var(--xs-text-primary)' }}>{label}</p>
      {desc && (
        <p style={{ fontSize: 14, fontWeight: 400, margin: '4px 0 0', maxWidth: 320, textAlign: 'center', color: 'var(--xs-text-secondary)' }}>
          {desc}
        </p>
      )}
      {children != null && (
        <div className="xs-card-body" style={{ width: '100%', marginTop: 12 }}>
          {children}
        </div>
      )}
      {onClick && (
        <button
          type="button"
          className="xs-hoverable"
          onClick={onClick}
          style={{
            marginTop: 'auto',
            alignSelf: 'stretch',
            border: 'none',
            background: 'transparent',
            borderRadius: 'var(--xs-radius-chip)',
            padding: '6px 8px',
            fontSize: 14,
            fontWeight: 500,
            color: 'var(--xs-blue-02)',
            cursor: 'pointer',
          }}
        >
          View all threats
        </button>
      )}
    </div>
  );
};

export default KpiHero;
