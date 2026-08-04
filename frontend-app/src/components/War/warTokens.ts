/**
 * Shared visual tokens for the WAR page.
 *
 * Two jobs. First, colour carries meaning here, so it lives in one place: red means
 * an attacker can use this today, amber means partially mitigated, neutral grey means
 * nobody has checked. Every status also ships an icon and a label so the page never
 * relies on colour alone.
 *
 * Second, these hold the house style constants so components don't re-derive them.
 * The palette is the same rgba-alpha neutral ladder used by BillOpsTickets and
 * AddAccount (0.88 → 0.6 → 0.45 → 0.35), not Tailwind's slate/gray scale.
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

export const STATUS_TOKENS: Record<WarStatus, StatusToken> = {
  FAIL: {
    label: 'Exposed',
    icon: 'mdi:shield-off-outline',
    badge: 'bg-red-50 text-red-700 border-red-200',
    pill: 'bg-red-500/85 text-white',
    accent: 'bg-red-500',
  },
  WARN: {
    label: 'Weakened',
    icon: 'mdi:shield-alert-outline',
    badge: 'bg-amber-50 text-amber-700 border-amber-200',
    pill: 'bg-amber-500/85 text-white',
    accent: 'bg-amber-400',
  },
  UNKNOWN: {
    label: 'Unverified',
    icon: 'mdi:shield-search',
    badge: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.16)]',
    pill: 'bg-[rgba(0,0,0,0.45)] text-white',
    accent: 'bg-[rgba(0,0,0,0.18)]',
  },
  MANUAL: {
    label: 'Manual review',
    icon: 'mdi:account-search-outline',
    badge: 'bg-sky-50 text-sky-700 border-sky-200',
    pill: 'bg-sky-500/85 text-white',
    accent: 'bg-sky-300',
  },
  PASS: {
    label: 'Protected',
    icon: 'mdi:shield-check-outline',
    badge: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    pill: 'bg-emerald-500/85 text-white',
    accent: 'bg-emerald-500',
  },
  NOT_APPLICABLE: {
    label: 'Not in use',
    icon: 'mdi:minus-circle-outline',
    badge: 'bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.1)]',
    pill: 'bg-[rgba(0,0,0,0.32)] text-white',
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
    text: 'text-orange-600 font-semibold',
    badge: 'bg-orange-50 text-orange-700 border-orange-200',
  },
  medium: {
    icon: 'mdi:minus-circle-outline',
    text: 'text-[rgba(0,0,0,0.55)] font-medium',
    badge: 'bg-amber-50 text-amber-700 border-amber-200',
  },
  low: {
    icon: 'mdi:arrow-down-circle-outline',
    text: 'text-[rgba(0,0,0,0.4)] font-medium',
    badge: 'bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.5)] border-[rgba(0,0,0,0.12)]',
  },
};

export const severityToken = (severity?: string | null) =>
  SEVERITY_TOKENS[(severity ?? 'low').toLowerCase()] ?? SEVERITY_TOKENS.low;

export const PATH_STATE_TOKENS: Record<WarPathState, { label: string; classes: string }> = {
  OPEN: { label: 'Open', classes: 'bg-red-500/85 text-white' },
  PARTIAL: { label: 'Partial', classes: 'bg-amber-500/85 text-white' },
  UNVERIFIED: { label: 'Unverified', classes: 'bg-[rgba(0,0,0,0.32)] text-white' },
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

export const RISK_LEVEL_TOKENS: Record<string, { ring: string; text: string; label: string }> = {
  CRITICAL: { ring: 'stroke-red-500', text: 'text-red-600', label: 'Critical risk' },
  HIGH: { ring: 'stroke-orange-500', text: 'text-orange-600', label: 'High risk' },
  MEDIUM: { ring: 'stroke-amber-500', text: 'text-amber-600', label: 'Medium risk' },
  LOW: { ring: 'stroke-emerald-500', text: 'text-emerald-600', label: 'Low risk' },
  // Not a risk rating. The scan could not read the account, so there is nothing to
  // rate — calling that "critical" would blame the customer for our blind spot.
  UNKNOWN: {
    ring: 'stroke-[rgba(0,0,0,0.2)]',
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
  'text-[11px] font-semibold text-[rgba(0,0,0,0.35)] uppercase tracking-widest';

/** Column header inside a list. */
export const COL_HEADER =
  'text-[10px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-widest';

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
  + 'focus:border-[rgba(0,0,0,0.4)] cursor-pointer';

/**
 * Shared column template for the findings list. The sticky header and every row use
 * the exact same grid so badges always line up under their label.
 * Columns: Control | Category | Severity | Status | Affected | chevron
 */
export const FINDINGS_GRID =
  'grid items-center gap-x-3 grid-cols-[minmax(0,1fr)_104px_24px] '
  + 'sm:grid-cols-[minmax(0,1fr)_84px_104px_24px] '
  + 'lg:grid-cols-[minmax(0,1fr)_148px_84px_104px_76px_24px]';
