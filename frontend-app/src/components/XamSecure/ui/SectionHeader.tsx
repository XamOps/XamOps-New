import { Icon } from '@iconify/react';

interface Props {
  /** mdi icon name. */
  icon: string;
  title: string;
  /** Tile background token. */
  tileBg: string;
  /** Tile icon color token. */
  tileIcon: string;
}

/**
 * Pillar header: a 36px tinted icon tile + a 24/400 title. Decorative (not clickable).
 */
const SectionHeader: React.FC<Props> = ({ icon, title, tileBg, tileIcon }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 8, height: 36 }}>
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: 36,
        height: 36,
        borderRadius: 'var(--xs-radius-chip)',
        background: tileBg,
        flexShrink: 0,
      }}
    >
      <Icon icon={icon} width={20} height={20} color={tileIcon} aria-hidden />
    </span>
    <h2 style={{ fontSize: 24, fontWeight: 400, letterSpacing: '-0.24px', color: 'var(--xs-text-primary)', margin: 0 }}>
      {title}
    </h2>
  </div>
);

export default SectionHeader;
