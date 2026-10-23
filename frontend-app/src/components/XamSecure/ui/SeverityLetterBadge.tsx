import { sevTokens } from './tokens';

interface Props {
  severity: string | null | undefined;
  /** Diameter in px. Default 20. */
  size?: number;
}

/**
 * The "never color-alone" carrier: a 20px circle with a transparent fill, a vivid decorative ring
 * (non-text, exempt from the 4.5:1 ratio) and an AA-corrected letter glyph (the darker *-01 ramp).
 *
 * Maps CRITICAL→C, HIGH→H, MEDIUM→M, LOW→L, anything else→O. Independent of the shared
 * `SeverityChip` (untouched); this page uses letter badges per the spec.
 */
const SeverityLetterBadge: React.FC<Props> = ({ severity, size = 20 }) => {
  const { badge, ring, letter } = sevTokens(severity);
  const label = (severity ?? 'OTHER').toUpperCase();
  return (
    <span
      role="img"
      aria-label={`Severity ${label}`}
      title={label}
      data-testid="severity-letter-badge"
      data-letter={letter}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: size,
        height: size,
        flexShrink: 0,
        borderRadius: '999px',
        background: 'transparent',
        border: `1.5px solid ${ring}`,
        color: badge,
        fontSize: Math.round(size * 0.55),
        fontWeight: 600,
        lineHeight: 1,
      }}
    >
      {letter}
    </span>
  );
};

export default SeverityLetterBadge;
