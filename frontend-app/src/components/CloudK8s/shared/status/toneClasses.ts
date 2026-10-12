/**
 * Turns a `Tone` into the exact Tailwind classes the status components use. The opacity numbers are
 * design-delta §2.2's "Opacity rules" table (chip background 11% light / 20% dark, chip border 38% /
 * 44%, active-step band 8% / 13%). These are numbers, not colours, so they live in code with a
 * `dark:` variant, never in the token file.
 *
 * Keeping the class strings as whole literals (not built from a template) is deliberate: the
 * design-rule guard bans template-built colour classes, and whole literals also let Tailwind's
 * content scanner see every class.
 */
import type { Tone } from './statusTones';

export interface ToneClasses {
  /** Text in the tone, e.g. a chip label or a KPI accent label. */
  text: string;
  /** The accent bar / border in the tone, at full strength. */
  border: string;
  /** The chip tint behind text (11% light / 20% dark). */
  chipBg: string;
  /** The chip border (38% light / 44% dark). */
  chipBorder: string;
  /** The tinted band behind an active step (8% light / 13% dark). */
  band: string;
}

const TONE_CLASSES: Record<Tone, ToneClasses> = {
  good: {
    text: 'text-status-good',
    border: 'border-status-good',
    chipBg: 'bg-status-good/[.11] dark:bg-status-good/[.20]',
    chipBorder: 'border-status-good/[.38] dark:border-status-good/[.44]',
    band: 'bg-status-good/[.08] dark:bg-status-good/[.13]',
  },
  warn: {
    text: 'text-status-warn',
    border: 'border-status-warn',
    chipBg: 'bg-status-warn/[.11] dark:bg-status-warn/[.20]',
    chipBorder: 'border-status-warn/[.38] dark:border-status-warn/[.44]',
    band: 'bg-status-warn/[.08] dark:bg-status-warn/[.13]',
  },
  bad: {
    text: 'text-status-bad',
    border: 'border-status-bad',
    chipBg: 'bg-status-bad/[.11] dark:bg-status-bad/[.20]',
    chipBorder: 'border-status-bad/[.38] dark:border-status-bad/[.44]',
    band: 'bg-status-bad/[.08] dark:bg-status-bad/[.13]',
  },
  info: {
    text: 'text-status-info',
    border: 'border-status-info',
    chipBg: 'bg-status-info/[.11] dark:bg-status-info/[.20]',
    chipBorder: 'border-status-info/[.38] dark:border-status-info/[.44]',
    band: 'bg-status-info/[.08] dark:bg-status-info/[.13]',
  },
  off: {
    text: 'text-status-off',
    border: 'border-status-off',
    chipBg: 'bg-status-off/[.11] dark:bg-status-off/[.20]',
    chipBorder: 'border-status-off/[.38] dark:border-status-off/[.44]',
    band: 'bg-status-off/[.08] dark:bg-status-off/[.13]',
  },
};

export function toneClasses(tone: Tone): ToneClasses {
  return TONE_CLASSES[tone];
}
