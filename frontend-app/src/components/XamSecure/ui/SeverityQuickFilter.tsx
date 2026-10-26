import type { SevKey } from './tokens';
import SeverityLetterBadge from './SeverityLetterBadge';

/** Severity chips in display order (spec §5.1). */
const SEV_CHIPS: Array<{ value: SevKey; label: string }> = [
  { value: 'CRITICAL', label: 'Critical' },
  { value: 'HIGH', label: 'High' },
  { value: 'MEDIUM', label: 'Medium' },
  { value: 'LOW', label: 'Low' },
];

interface Props {
  counts: Record<SevKey, number>;
  total: number;
  active: string | null;
  onChange: (value: string | null) => void;
}

/**
 * The severity quick-filter chip row (spec §5.1). Light token-based chips — NOT a FilterChips
 * edit (that component uses a dark-pill active style shared by other importers). Severity is
 * never color-alone: each per-severity chip carries a letter badge AND its word label.
 */
const SeverityQuickFilter: React.FC<Props> = ({ counts, total, active, onChange }) => {
  const chipStyle = (isActive: boolean): React.CSSProperties => ({
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
    height: 30,
    padding: '0 12px',
    borderRadius: 'var(--xs-radius-pill)',
    fontSize: 14,
    fontWeight: 500,
    cursor: 'pointer',
    transition: 'border .15s ease-out',
    boxSizing: 'border-box',
    background: isActive ? 'var(--xs-blue-05)' : 'var(--xs-bg-primary)',
    color: isActive ? 'var(--xs-blue-02)' : 'var(--xs-text-secondary)',
    border: isActive ? '1px solid transparent' : '1px solid var(--xs-border-subtle)',
  });

  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
      <button
        type="button"
        aria-pressed={active === null}
        onClick={() => onChange(null)}
        style={chipStyle(active === null)}
      >
        <span>All</span>
        <span>{total}</span>
      </button>
      {SEV_CHIPS.map(({ value, label }) => (
        <button
          key={value}
          type="button"
          aria-pressed={active === value}
          onClick={() => onChange(value)}
          style={chipStyle(active === value)}
        >
          <SeverityLetterBadge severity={value} size={16} />
          <span>{label}</span>
          <span>{counts[value] ?? 0}</span>
        </button>
      ))}
    </div>
  );
};

export default SeverityQuickFilter;
