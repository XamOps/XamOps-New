/**
 * Shared visual tokens for the WAR page.
 *
 * Two jobs. First, colour carries meaning here, so it lives in one place: red means
 * an attacker can use this today, amber means partially mitigated, neutral grey means
 * nobody has checked. Every status also ships an icon and a label so the page never
 * relies on colour alone.
 *
 * Second, these hold the house style constants so components don't re-derive them.
 * The palette is the rgba-alpha neutral ladder shared with BillOpsTickets and
 * AddAccount, not Tailwind's slate/gray scale.
 *
 * <h3>The neutral floor for text</h3>
 * The ladder used to run down to 0.18, which put most of this page's supporting copy
 * below WCAG AA — section eyebrows at 2.44:1, body copy at 3.35:1, risk weights at
 * 2.11:1. Text now uses four steps and nothing below 0.55:
 *
 * <pre>
 *   0.88  16.6:1  titles, row headings, values          primary
 *   0.70   8.5:1  emphasised secondary                  strong
 *   0.60   5.7:1  body copy, descriptions, data         body
 *   0.55   4.7:1  metadata, eyebrows, column headers    muted   <- floor
 * </pre>
 *
 * 0.45 (3.35:1) is permitted only for non-text graphics and large text, both of which
 * owe 3:1 rather than 4.5:1 — the disclosure chevrons, the unassessable score ring, and
 * the "—" placeholders at 20px/30px bold. Never use it for body-size text.
 *
 * Values at or below 0.16 are borders, dividers and fills. They carry no information and
 * are exempt, with one exception: a focus indicator is a graphical object and owes 3:1,
 * so {@link SELECT_CLS} and the inputs focus to 0.55 rather than the old 0.4 (2.85:1).
 */

import type { WarPathState, WarStatus } from '../../types/war';

interface StatusToken {
  label: string;
  icon: string;
  /** Badge classes. */
  badge: string;
  /** Solid pill, used where status is the primary anchor. */
  pill: string;
  /** Left-edge triage strip on a findings row. */
  accent: string;
}

/**
 * Pill backgrounds are solid, never alpha-blended.
 *
 * The labels exist so this page never leans on colour alone, which only works if they
 * can be read. At 9.5px they are normal-size text and owe 4.5:1, but blending a -500 at
 * 85% over a white card landed every one of them under it — measured: amber 1.91:1,
 * emerald 2.22:1, sky 2.39:1, red 3.19:1, and the two neutrals 2.24:1 and 3.36:1. The
 * darker solid steps below clear 4.5:1 with the lightest at 4.74:1.
 *
 * The two neutrals stay on the house rgba ladder rather than switching to Tailwind's
 * slate, per the palette note above.
 */
export const STATUS_TOKENS: Record<WarStatus, StatusToken> = {
  FAIL: {
    label: 'Exposed',
    icon: 'mdi:shield-off-outline',
    badge: 'bg-red-50 text-red-700 border-red-200',
    pill: 'bg-red-600 text-white', // 4.83:1
    accent: 'bg-red-500',
  },
  WARN: {
    label: 'Weakened',
    icon: 'mdi:shield-alert-outline',
    badge: 'bg-amber-50 text-amber-700 border-amber-200',
    // Amber is the hard one: even amber-600 only reaches 3.19:1 against white text.
    pill: 'bg-amber-700 text-white', // 5.02:1
    accent: 'bg-amber-400',
  },
  UNKNOWN: {
    label: 'Unverified',
    icon: 'mdi:shield-search',
    badge: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.16)]',
    pill: 'bg-[rgba(0,0,0,0.6)] text-white', // 5.74:1
    accent: 'bg-[rgba(0,0,0,0.18)]',
  },
  MANUAL: {
    label: 'Manual review',
    icon: 'mdi:account-search-outline',
    badge: 'bg-sky-50 text-sky-700 border-sky-200',
    pill: 'bg-sky-700 text-white', // 5.93:1
    accent: 'bg-sky-300',
  },
  PASS: {
    label: 'Protected',
    icon: 'mdi:shield-check-outline',
    badge: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    pill: 'bg-emerald-700 text-white', // 5.48:1
    accent: 'bg-emerald-500',
  },
  NOT_APPLICABLE: {
    label: 'Not in use',
    icon: 'mdi:minus-circle-outline',
    // No component renders STATUS_TOKENS.badge today, but it is kept at a passing value
    // so turning it on later cannot reintroduce a contrast failure.
    badge: 'bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.1)]',
    // Kept the lighter of the two neutrals so it still reads as the quieter state.
    pill: 'bg-[rgba(0,0,0,0.55)] text-white', // 4.74:1
    accent: 'bg-transparent',
  },
};

/**
 * Severity as coloured text plus an icon rather than a filled pill, so it never
 * competes with the status pill for attention. Matches the BillOps tickets list,
 * where only real escalations shout.
 */
export const SEVERITY_TOKENS: Record<string, { icon: string; text: string; badge: string }> = {
  critical: {
    icon: 'mdi:alert-circle',
    text: 'text-red-600 font-semibold',
    badge: 'bg-red-50 text-red-700 border-red-200',
  },
  high: {
    icon: 'mdi:alert',
    // orange-600 measured 3.56:1 at this size; 700 reaches 5.18:1.
    text: 'text-orange-700 font-semibold',
    badge: 'bg-orange-50 text-orange-700 border-orange-200',
  },
  medium: {
    icon: 'mdi:minus-circle-outline',
    // Raised one step so it still outranks low once low itself had to come up.
    text: 'text-[rgba(0,0,0,0.6)] font-medium',
    badge: 'bg-amber-50 text-amber-700 border-amber-200',
  },
  low: {
    icon: 'mdi:arrow-down-circle-outline',
    // Was 0.4 — 2.85:1, the worst text on the page. 0.55 is the first passing step.
    text: 'text-[rgba(0,0,0,0.55)] font-medium',
    // Badge label was 0.5 (3.95:1) on a 0.03 tint; 0.6 clears it with margin.
    badge: 'bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.12)]',
  },
};

export const severityToken = (severity?: string | null) =>
  SEVERITY_TOKENS[(severity ?? 'low').toLowerCase()] ?? SEVERITY_TOKENS.low;

export const PATH_STATE_TOKENS: Record<WarPathState, { label: string; classes: string }> = {
  OPEN: { label: 'Open', classes: 'bg-red-500/85 text-white' },
  PARTIAL: { label: 'Partial', classes: 'bg-amber-500/85 text-white' },
  UNVERIFIED: { label: 'Unverified', classes: 'bg-[rgba(0,0,0,0.32)] text-white' },
  // Deliberately not green. Nothing on this path was verified, so a success colour
  // would be an assurance the scan never earned.
  NEEDS_REVIEW: { label: 'Needs review', classes: 'bg-sky-500/85 text-white' },
  CONTAINED: { label: 'Contained', classes: 'bg-emerald-500/85 text-white' },
};

/** Icon per attack path, matching WarAttackPath in the backend. */
export const ATTACK_PATH_ICONS: Record<string, string> = {
  ACCOUNT_TAKEOVER: 'mdi:account-key-outline',
  CREDENTIAL_THEFT: 'mdi:key-remove',
  PRIVILEGE_ESCALATION: 'mdi:stairs-up',
  PUBLIC_EXPOSURE: 'mdi:earth',
  LATERAL_MOVEMENT: 'mdi:arrow-decision-outline',
  CRYPTO_MINING: 'mdi:pickaxe',
  DATA_EXFILTRATION: 'mdi:database-export-outline',
  RANSOMWARE: 'mdi:lock-alert-outline',
  AI_TOKEN_ABUSE: 'mdi:robot-outline',
  DDOS_AMPLIFICATION: 'mdi:wifi-strength-alert-outline',
  BLIND_SPOT: 'mdi:eye-off-outline',
  COST_ABUSE: 'mdi:cash-remove',
};

/**
 * The score ring and the verdict beside it.
 *
 * {@code text} renders at 13px semibold — normal-size text, so it owes 4.5:1. The -600
 * steps did not reach it (orange 3.56:1, amber 3.19:1, emerald 3.77:1) on what is
 * arguably the most consequential label on the page, so the verdict colours sit one step
 * darker than the ring they describe. {@code ring} is a 9px stroke whose value is also
 * printed as a numeral inside it, so it is a redundant graphic and stays at -500.
 */
export const RISK_LEVEL_TOKENS: Record<string, { ring: string; text: string; label: string }> = {
  CRITICAL: { ring: 'stroke-red-500', text: 'text-red-600', label: 'Critical risk' },
  HIGH: { ring: 'stroke-orange-500', text: 'text-orange-700', label: 'High risk' },
  MEDIUM: { ring: 'stroke-amber-500', text: 'text-amber-700', label: 'Medium risk' },
  LOW: { ring: 'stroke-emerald-500', text: 'text-emerald-700', label: 'Low risk' },
  // Not a risk rating. The scan could not read the account, so there is nothing to
  // rate — calling that "critical" would blame the customer for our blind spot.
  UNKNOWN: {
    ring: 'stroke-[rgba(0,0,0,0.45)]',
    text: 'text-[rgba(0,0,0,0.6)]',
    label: 'Unable to assess',
  },
};

// ── House style constants ───────────────────────────────────────────────────

/** Card chrome. Shadow is deliberately absent — reserved for the one anchor card. */
export const CARD = 'bg-white border border-[#e5e7eb] rounded-xl';

/** The anchor card: black left edge and a lifted shadow, for the page's lead panel. */
export const CARD_ANCHOR =
  'bg-white border border-[#e5e7eb] border-l-[3px] border-l-[rgba(0,0,0,0.88)] rounded-xl '
  + 'shadow-[0_1px_2px_rgba(0,0,0,0.04),0_10px_28px_-16px_rgba(0,0,0,0.18)]';

/** Section eyebrow above a panel. */
export const EYEBROW =
  'text-[11px] font-semibold text-[rgba(0,0,0,0.55)] uppercase tracking-widest';

/** Column header inside a list. */
export const COL_HEADER =
  'text-[10px] font-semibold text-[rgba(0,0,0,0.6)] uppercase tracking-widest';

export const BTN_PRIMARY =
  'flex items-center gap-1.5 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md '
  + 'text-[12px] font-medium hover:bg-black transition-colors disabled:opacity-50';

export const BTN_SECONDARY =
  'h-8 flex items-center gap-1.5 px-3 rounded-lg border border-[#e5e7eb] bg-white '
  + 'text-[12px] font-medium text-[rgba(0,0,0,0.7)] hover:border-[rgba(0,0,0,0.4)] '
  + 'hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-50';

export const SELECT_CLS =
  'h-8 pl-3 pr-8 rounded-lg border border-[#e5e7eb] bg-white text-[12px] font-medium '
  + 'text-[rgba(0,0,0,0.7)] appearance-none focus:outline-none '
  + 'focus:border-[rgba(0,0,0,0.55)] cursor-pointer';

/**
 * Shared column template for the findings list. The sticky header and every row use
 * the exact same grid so badges always line up under their label.
 * Columns: Control | Category | Severity | Status | Affected | chevron
 */
export const FINDINGS_GRID =
  'grid items-center gap-x-3 grid-cols-[minmax(0,1fr)_104px_24px] '
  + 'sm:grid-cols-[minmax(0,1fr)_84px_104px_24px] '
  + 'lg:grid-cols-[minmax(0,1fr)_148px_84px_104px_76px_24px]';
