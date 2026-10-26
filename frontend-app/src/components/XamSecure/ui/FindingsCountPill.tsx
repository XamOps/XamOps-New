/**
 * The group-bar "N Findings" pill (spec §5.2a). A tiny `#f8fafc` pill. Its text color is
 * `--xs-text-secondary` (#75808e, AA on white/#f8fafc) — deliberately raised from the measured
 * #94a3b8, which falls below WCAG AA for normal text. Not a `CountChip` restyle (CountChip is
 * 25px/14px/blue-tinted with a mandatory icon; leaving it untouched keeps Overview unaffected).
 */
const FindingsCountPill: React.FC<{ count: number }> = ({ count }) => (
  <span
    data-testid="findings-count-pill"
    style={{
      display: 'inline-flex',
      alignItems: 'center',
      height: 18,
      padding: '0 6px',
      marginLeft: 10,
      borderRadius: 'var(--xs-radius-pill)',
      background: 'var(--xs-bg-secondary)',
      color: 'var(--xs-text-secondary)',
      fontSize: 12,
      lineHeight: '18px',
      whiteSpace: 'nowrap',
    }}
  >
    {count} Findings
  </span>
);

export default FindingsCountPill;
