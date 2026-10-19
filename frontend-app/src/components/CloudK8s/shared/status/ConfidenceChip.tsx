/**
 * The confidence chip (FEAT-001): the 0..1 confidence in a vertical rightsizing recommendation,
 * surfaced on top of the existing {@link StatusChip} so it inherits the status kit's tones, icons
 * and dark-mode discipline. No new colours, no gradient, no emoji — the band picks an existing
 * semantic tone: HIGH → `good`, MEDIUM → `info`, LOW → `warn`.
 *
 * The label is `{score} · {band}` (e.g. `0.82 · High`) and the second line is the "why", phrased off
 * the same measured coverage the score used, so the chip and the score never disagree.
 */
import type { ConfidenceView } from '../../../../types/verticalRightsizer';
import { confidenceBandWord, confidenceWhy, formatConfidenceScore } from '../../../../api/verticalRightsizerAdapter';
import { StatusChip } from './StatusChip';
import type { Tone } from './statusTones';

/** The band → tone and icon map. Existing tones only. */
const BAND: Record<ConfidenceView['band'], { tone: Tone; icon: string }> = {
  HIGH: { tone: 'good', icon: 'mdi:shield-check' },
  MEDIUM: { tone: 'info', icon: 'mdi:shield-half-full' },
  LOW: { tone: 'warn', icon: 'mdi:shield-alert-outline' },
};

export interface ConfidenceChipProps {
  confidence: ConfidenceView;
  /** Drop the "why" sub-line, for a compact table cell. */
  compact?: boolean;
  className?: string;
}

export function ConfidenceChip({ confidence, compact = false, className = '' }: ConfidenceChipProps) {
  const { tone, icon } = BAND[confidence.band];
  const label = `${formatConfidenceScore(confidence.score)} · ${confidenceBandWord(confidence.band)}`;
  // data-k8s-kit on an inline-flex wrapper keeps the dark-kit discipline explicit on this
  // component's own root (the legacy .dark overrides are exempted on a kit-marked subtree), even
  // though the StatusChip it wraps also marks itself.
  return (
    <span className="inline-flex" data-k8s-kit="">
      <StatusChip
        tone={tone}
        icon={icon}
        label={label}
        detail={compact ? null : confidenceWhy(confidence)}
        className={className}
      />
    </span>
  );
}

export default ConfidenceChip;
