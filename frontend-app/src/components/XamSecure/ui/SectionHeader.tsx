import React from 'react';
import { Icon } from '@iconify/react';
import { TEXT } from './tokens';

/**
 * An editorial section header in Upwind's rhythm: a small 28×28 rounded-lg soft-tinted icon chip
 * holding an mdi icon in the section's accent color, followed by a quiet title. No uppercase
 * eyebrow treatment on the main sections — the chip + title is the whole mark.
 */
const SectionHeader: React.FC<{
  icon: string;
  title: string;
  /** Icon color. */
  accent: string;
  /** Chip background (a pale wash of the accent, or the soft-gray panel fill). */
  chipBg: string;
}> = ({ icon, title, accent, chipBg }) => (
  <div className="flex items-center gap-2.5">
    <span
      className="inline-flex items-center justify-center rounded-lg flex-shrink-0"
      style={{ width: 28, height: 28, background: chipBg }}
    >
      <Icon icon={icon} width={17} style={{ color: accent }} aria-hidden="true" />
    </span>
    <h2 className="text-sm font-semibold" style={{ color: TEXT.strong }}>{title}</h2>
  </div>
);

export default SectionHeader;
