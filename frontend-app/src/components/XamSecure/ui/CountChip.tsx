import { Icon } from '@iconify/react';

type Variant = 'resources' | 'events' | 'scan';

interface Props {
  value: number | string;
  variant: Variant;
  icon?: string;
}

const VARIANTS: Record<Variant, { bg: string; text: string; icon: string }> = {
  resources: { bg: 'var(--xs-blue-06)', text: 'var(--xs-blue-01)', icon: 'mdi:layers-outline' },
  events: { bg: 'var(--xs-purple-06)', text: 'var(--xs-purple-01)', icon: 'mdi:radar' },
  scan: { bg: 'var(--xs-purple-06)', text: 'var(--xs-purple-01)', icon: 'mdi:radar' },
};

/**
 * Small count chip: a tinted pill with an icon + number. AA-corrected text tokens (blue-01 /
 * purple-01) per the spec so the number stays legible on its tint.
 */
const CountChip: React.FC<Props> = ({ value, variant, icon }) => {
  const v = VARIANTS[variant];
  return (
    <span
      data-testid="count-chip"
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 4,
        height: 25,
        padding: '2px 6px',
        borderRadius: 'var(--xs-radius-chip)',
        background: v.bg,
        color: v.text,
        fontSize: 14,
        fontWeight: 500,
        whiteSpace: 'nowrap',
      }}
    >
      <Icon icon={icon ?? v.icon} width={13} height={13} aria-hidden />
      {value}
    </span>
  );
};

export default CountChip;
