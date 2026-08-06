/**
 * Shared tokens for the ComplianceOps page.
 *
 * Same house style as the WAR page: #fafafa canvas, `border-[#e5e7eb] rounded-xl` cards
 * with no shadow, a pixel-pinned type scale, and the rgba alpha neutral ladder
 * (0.88 → 0.6 → 0.45 → 0.35) rather than Tailwind's slate/gray scale.
 *
 * The framework key/badge system below moved here from the page body unchanged. It is
 * used by four different components, and leaving it inline was most of the reason the
 * page was 1741 lines.
 */

import type { ComplianceStatus } from '../../types/compliance';

// ── Card chrome ─────────────────────────────────────────────────────────────

export const CARD = 'bg-white border border-[#e5e7eb] rounded-xl';

/** The one anchor card: black left edge and a lifted shadow, for the lead panel. */
export const CARD_ANCHOR =
  'bg-white border border-[#e5e7eb] border-l-[3px] border-l-[rgba(0,0,0,0.88)] rounded-xl '
  + 'shadow-[0_1px_2px_rgba(0,0,0,0.04),0_10px_28px_-16px_rgba(0,0,0,0.18)]';

export const EYEBROW =
  'text-[11px] font-semibold text-[rgba(0,0,0,0.35)] uppercase tracking-widest';

export const COL_HEADER =
  'text-[10px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-widest';

export const BTN_PRIMARY =
  'h-8 flex items-center gap-1.5 px-3 bg-[rgba(0,0,0,0.88)] text-white rounded-lg '
  + 'text-[12px] font-medium hover:bg-black transition-colors disabled:opacity-50';

export const BTN_SECONDARY =
  'h-8 flex items-center gap-1.5 px-3 rounded-lg border border-[#e5e7eb] bg-white '
  + 'text-[12px] font-medium text-[rgba(0,0,0,0.7)] hover:border-[rgba(0,0,0,0.4)] '
  + 'hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-50';

export const SELECT_CLS =
  'h-8 pl-3 pr-8 rounded-lg border border-[#e5e7eb] bg-white text-[12px] font-medium '
  + 'text-[rgba(0,0,0,0.7)] appearance-none focus:outline-none '
  + 'focus:border-[rgba(0,0,0,0.4)] cursor-pointer';

export const INPUT_CLS =
  'h-8 pl-8 pr-3 rounded-lg border border-[#e5e7eb] bg-white text-[12px] '
  + 'text-[rgba(0,0,0,0.78)] placeholder:text-[rgba(0,0,0,0.3)] focus:outline-none '
  + 'focus:border-[rgba(0,0,0,0.4)]';

/**
 * Shared column template for the controls list. The sticky header and every row use
 * the same grid so cells always line up under their label.
 * Control | Service | Severity | Status | Failed | Frameworks | chevron
 */
export const CONTROLS_GRID =
  'grid items-center gap-x-3 grid-cols-[minmax(0,1fr)_104px_24px] '
  + 'sm:grid-cols-[minmax(0,1fr)_76px_104px_24px] '
  + 'lg:grid-cols-[minmax(0,1fr)_104px_76px_104px_84px_128px_24px]';

// ── Status ──────────────────────────────────────────────────────────────────

interface StatusToken {
  label: string;
  icon: string;
  /** Solid pill — status is the primary anchor on a row. */
  pill: string;
  badge: string;
  /** Left-edge triage strip. */
  accent: string;
  description: string;
}

/**
 * Four statuses, four meanings, none of them collapsed into each other.
 *
 * MANUAL is grey rather than green or red because the scanner did not establish an
 * answer — the old page had no such state, so anything not failing read as compliant.
 * WAIVED is sky rather than green because an accepted risk is not a passing control,
 * and colouring it green is how a score quietly stops meaning anything.
 */
export const COMPLIANCE_STATUS_TOKENS: Record<ComplianceStatus, StatusToken> = {
  FAIL: {
    label: 'Failing',
    icon: 'mdi:close-circle-outline',
    pill: 'bg-red-500/85 text-white',
    badge: 'bg-red-50 text-red-700 border-red-200',
    accent: 'bg-red-500',
    description: 'At least one in-scope resource failed this check.',
  },
  PASS: {
    label: 'Passing',
    icon: 'mdi:check-circle-outline',
    pill: 'bg-emerald-500/85 text-white',
    badge: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    accent: 'bg-emerald-500',
    description: 'Every in-scope resource passed this check.',
  },
  MANUAL: {
    label: 'Not evaluated',
    icon: 'mdi:help-circle-outline',
    pill: 'bg-[rgba(0,0,0,0.45)] text-white',
    badge: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.16)]',
    accent: 'bg-[rgba(0,0,0,0.18)]',
    description: 'The scanner could not decide this check. It is neither passing nor failing.',
  },
  WAIVED: {
    label: 'Waived',
    icon: 'mdi:file-document-check-outline',
    pill: 'bg-sky-500/85 text-white',
    badge: 'bg-sky-50 text-sky-700 border-sky-200',
    accent: 'bg-sky-300',
    description: 'Failing, but covered by an approved exception with an expiry date.',
  },
};

export const statusToken = (status: ComplianceStatus | null | undefined): StatusToken =>
  COMPLIANCE_STATUS_TOKENS[(status ?? 'MANUAL') as ComplianceStatus]
  ?? COMPLIANCE_STATUS_TOKENS.MANUAL;

// ── Severity ────────────────────────────────────────────────────────────────

/** One source of truth for severity colour: chips, donut, legend and trend all read it. */
export const SEVERITY_COLORS = {
  CRITICAL: '#dc2626',
  HIGH: '#ea580c',
  MEDIUM: '#f59e0b',
  LOW: '#22c55e',
} as const;

export const SEVERITY_ROWS = [
  { key: 'critical', label: 'Critical', color: SEVERITY_COLORS.CRITICAL },
  { key: 'high', label: 'High', color: SEVERITY_COLORS.HIGH },
  { key: 'medium', label: 'Medium', color: SEVERITY_COLORS.MEDIUM },
  { key: 'low', label: 'Low', color: SEVERITY_COLORS.LOW },
] as const;

/**
 * Severity as icon + coloured text rather than a filled pill, so it never competes
 * with the status pill for attention.
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

export const SEVERITY_RANK: Record<string, number> = {
  critical: 0,
  high: 1,
  medium: 2,
  low: 3,
};

// ── Score bands ─────────────────────────────────────────────────────────────

/**
 * Bands, not a Pass/Fail stamp. No scanner output is a certification, and a page used
 * in audit conversations should not imply one. The labels match ComplianceScoring.label
 * on the backend so the two never disagree.
 */
export const SCORE_BANDS: Record<string, { ring: string; text: string; hex: string }> = {
  Strong: { ring: 'stroke-emerald-500', text: 'text-emerald-600', hex: '#10b981' },
  Adequate: { ring: 'stroke-amber-500', text: 'text-amber-600', hex: '#f59e0b' },
  Weak: { ring: 'stroke-orange-500', text: 'text-orange-600', hex: '#ea580c' },
  Critical: { ring: 'stroke-red-500', text: 'text-red-600', hex: '#dc2626' },
  'Unable to assess': {
    ring: 'stroke-[rgba(0,0,0,0.2)]',
    text: 'text-[rgba(0,0,0,0.6)]',
    hex: 'rgba(0,0,0,0.2)',
  },
};

export const scoreBand = (label: string | null | undefined) =>
  SCORE_BANDS[label ?? 'Unable to assess'] ?? SCORE_BANDS['Unable to assess'];

// ── Framework identity ──────────────────────────────────────────────────────

export const FRAMEWORK_NAMES: Record<string, string> = {
  CIS: 'CIS Benchmark',
  SOC2: 'SOC 2',
  'PCI-DSS': 'PCI DSS',
  HIPAA: 'HIPAA',
  NIST: 'NIST CSF',
  NIST800: 'NIST 800-53',
  ISO27001: 'ISO 27001',
  GDPR: 'GDPR',
  MITRE: 'MITRE ATT&CK',
  CISA: 'CISA',
  FEDRAMP: 'FedRAMP',
  FFIEC: 'FFIEC',
  RBI: 'RBI CSF',
  ENS: 'ENS',
  AWS_WELL_ARCHITECTED: 'AWS Well-Architected',
  FSBP: 'AWS FSBP',
  AWS_FTR: 'AWS FTR',
  GXP: 'GxP',
  NIS2: 'NIS2',
  CSA_CCM: 'CSA CCM',
  CMMC: 'CMMC',
  KISA: 'KISA ISMS-P',
  BSI_C5: 'BSI C5',
  SECNUMCLOUD: 'SecNumCloud',
  CUSTOM: 'Best Practices',
  SEBI: 'SEBI',
  IRDAI: 'IRDAI',
  C5_2025: 'C5 2025',
  CCC: 'CCC',
  PROWLER_TS: 'Prowler ThreatScore',
};

/** Industry short form, 2–4 characters. Version suffixes live on the full name. */
export const FRAMEWORK_MONOGRAM: Record<string, string> = {
  CIS: 'CIS', SOC2: 'SOC2', 'PCI-DSS': 'PCI', HIPAA: 'HIP',
  NIST: 'CSF', NIST800: 'NIST', ISO27001: 'ISO', GDPR: 'GDPR',
  MITRE: 'MTR', CISA: 'CISA', FEDRAMP: 'FR', FFIEC: 'FFI',
  RBI: 'RBI', ENS: 'ENS', AWS_WELL_ARCHITECTED: 'AWA', FSBP: 'FSBP',
  AWS_FTR: 'FTR', GXP: 'GXP', NIS2: 'NIS2', CSA_CCM: 'CSA',
  CMMC: 'CMMC', KISA: 'KISA', BSI_C5: 'BSI', SECNUMCLOUD: 'SNC',
  CUSTOM: 'BP', SEBI: 'SEBI', IRDAI: 'IRD', C5_2025: 'C5',
  CCC: 'CCC', PROWLER_TS: 'PTS',
};

/**
 * 26 colours across the full hue wheel, each verified to clear WCAG AA (4.5:1) against
 * white text at both the base tone and the 14% lighter gradient stop.
 *
 * Generated monograms rather than official logos, deliberately: several standards bodies
 * restrict their certification marks to entities that actually hold the certification,
 * and others have no official mark at all.
 */
const BADGE_PALETTE = [
  '#9f1239', '#991b1b', '#9a3412', '#854d0e', '#3f6212', '#166534',
  '#065f46', '#115e59', '#155e75', '#075985', '#1e40af', '#3730a3',
  '#5b21b6', '#6b21a8', '#86198f', '#9d174d', '#1e293b', '#292524',
  '#78350f', '#14532d', '#134e4a', '#1e3a8a', '#4c1d95', '#7f1d1d',
  '#334155', '#0c4a6e',
];

/** Assigned rather than hashed so no two neighbours land on nearby hues. */
const BADGE_COLOR_INDEX: Record<string, number> = {
  NIST800: 14, 'PCI-DSS': 0, C5_2025: 18, ISO27001: 6, NIS2: 23,
  CIS: 5, FEDRAMP: 7, NIST: 10, ENS: 22, CCC: 20,
  CSA_CCM: 4, PROWLER_TS: 2, MITRE: 21, SOC2: 3, FFIEC: 11,
  SECNUMCLOUD: 17, HIPAA: 19, KISA: 9, AWS_WELL_ARCHITECTED: 15, GDPR: 25,
  CMMC: 1, FSBP: 16, GXP: 13, BSI_C5: 8, CISA: 12,
  RBI: 24, SEBI: 0, IRDAI: 3, AWS_FTR: 0, CUSTOM: 3,
};

/** Raw scanner framework id → stable key used for names, monograms and colour. */
export function frameworkKey(raw: string): string {
  const u = (raw ?? '').toUpperCase();
  if (u.includes('CIS')) return 'CIS';
  if (u.includes('SOC')) return 'SOC2';
  if (u.includes('PCI')) return 'PCI-DSS';
  if (u.includes('HIPAA')) return 'HIPAA';
  if (u.includes('NIST') && u.includes('800')) return 'NIST800';
  if (u.includes('NIST')) return 'NIST';
  if (u.includes('ISO') && u.includes('27001')) return 'ISO27001';
  if (u.includes('GDPR')) return 'GDPR';
  if (u.includes('MITRE')) return 'MITRE';
  if (u.includes('CISA')) return 'CISA';
  if (u.includes('FEDRAMP') || u.includes('FED_RAMP')) return 'FEDRAMP';
  if (u.includes('FFIEC')) return 'FFIEC';
  if (u.includes('RBI')) return 'RBI';
  if (u.includes('WELL') && u.includes('ARCHITECTED')) return 'AWS_WELL_ARCHITECTED';
  if (u.includes('FSBP') || (u.includes('FOUNDATIONAL') && u.includes('SECURITY'))) return 'FSBP';
  if (u.includes('GXP')) return 'GXP';
  if (u.includes('NIS2')) return 'NIS2';
  if (u.includes('PROWLER')) return 'PROWLER_TS';
  if (/\bCCC\b/.test(u) || u.startsWith('CCC')) return 'CCC';
  if (u.includes('CSA') || u.includes('CCM')) return 'CSA_CCM';
  if (u.includes('CMMC')) return 'CMMC';
  if (u.includes('KISA') || u.includes('ISMS')) return 'KISA';
  if (u.includes('BSI') && u.includes('C5')) return 'BSI_C5';
  if (u.includes('C5')) return 'C5_2025';
  if (u.includes('SECNUM')) return 'SECNUMCLOUD';
  if (u.includes('FTR') || u.includes('FOUNDATIONAL TECHNICAL')) return 'AWS_FTR';
  if (u.includes('SEBI')) return 'SEBI';
  if (u.includes('IRDAI')) return 'IRDAI';
  if (u.includes('ENS')) return 'ENS';
  if (u.includes('CUSTOM') || u.includes('BEST PRACTICE')) return 'CUSTOM';
  return raw;
}

export const frameworkName = (rawOrKey: string): string =>
  FRAMEWORK_NAMES[frameworkKey(rawOrKey)] ?? rawOrKey;

/** "ProwlerThreatScore-1.0" → "PTS", "C5-2025" → "C5". */
function deriveMonogram(raw: string): string {
  const STOP = new Set(['THE', 'OF', 'FOR', 'AND']);
  const words = (raw ?? '')
    .replace(/[^A-Za-z0-9]+/g, ' ')
    .trim()
    .split(' ')
    .filter((w) => w && !/^\d+$/.test(w) && !STOP.has(w.toUpperCase()));
  if (words.length === 0) return (raw ?? '').slice(0, 3).toUpperCase() || '?';
  if (/^[A-Z0-9]{1,4}$/.test(words[0])) return words[0];
  if (words.length === 1) {
    const parts = words[0].split(/(?=[A-Z][a-z])/).filter(Boolean);
    if (parts.length > 1) return parts.slice(0, 3).map((p) => p[0]).join('').toUpperCase();
    return words[0].slice(0, 4).toUpperCase();
  }
  return words.slice(0, 4).map((w) => w[0]).join('').toUpperCase();
}

function hashString(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i += 1) h = (h * 31 + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}

/** Mix a hex colour toward white for the gradient highlight. */
function lighten(hex: string, amount: number): string {
  const n = (i: number) => parseInt(hex.slice(i, i + 2), 16);
  const mix = (v: number) => Math.round(v + (255 - v) * amount);
  return `rgb(${mix(n(1))}, ${mix(n(3))}, ${mix(n(5))})`;
}

export interface FrameworkBadgeSpec {
  text: string;
  bg: string;
  bgLight: string;
  name: string;
}

export function frameworkBadge(raw: string): FrameworkBadgeSpec {
  const key = frameworkKey(raw);
  const index = BADGE_COLOR_INDEX[key] ?? hashString(key) % BADGE_PALETTE.length;
  const bg = BADGE_PALETTE[index];
  const name = FRAMEWORK_NAMES[key] ?? raw;
  return {
    text: FRAMEWORK_MONOGRAM[key] ?? deriveMonogram(name),
    bg,
    bgLight: lighten(bg, 0.14),
    name,
  };
}

/** Grouping for the framework picker, so a flat list of 26 becomes four short ones. */
export const FRAMEWORK_CATEGORY: Record<string, string> = {
  NIST: 'Government & Public Sector', NIST800: 'Government & Public Sector',
  FEDRAMP: 'Government & Public Sector', CMMC: 'Government & Public Sector',
  CISA: 'Government & Public Sector', FFIEC: 'Government & Public Sector',
  CIS: 'Industry Standards', SOC2: 'Industry Standards', 'PCI-DSS': 'Industry Standards',
  ISO27001: 'Industry Standards', MITRE: 'Industry Standards', CSA_CCM: 'Industry Standards',
  HIPAA: 'Industry Standards', GXP: 'Industry Standards', CUSTOM: 'Industry Standards',
  GDPR: 'Regional & National', NIS2: 'Regional & National', ENS: 'Regional & National',
  BSI_C5: 'Regional & National', SECNUMCLOUD: 'Regional & National', KISA: 'Regional & National',
  RBI: 'Regional & National', SEBI: 'Regional & National', IRDAI: 'Regional & National',
  FSBP: 'Cloud & Vendor', AWS_FTR: 'Cloud & Vendor', AWS_WELL_ARCHITECTED: 'Cloud & Vendor',
};

export const CATEGORY_ORDER = [
  'Government & Public Sector',
  'Industry Standards',
  'Regional & National',
  'Cloud & Vendor',
  'Other',
];

export const frameworkCategory = (raw: string): string =>
  FRAMEWORK_CATEGORY[frameworkKey(raw)] ?? 'Other';

/** First sentence of a scanner description, used as a title when none is given. */
export function deriveTitle(title: string | null, fallback: string): string {
  if (title && title.trim()) return title.trim();
  const plain = (fallback ?? '').replace(/\*\*/g, '').replace(/`/g, '');
  const cut = plain.split(/(?<=\.)\s/)[0];
  return cut.length > 4 && cut.length < 160 ? cut : plain.slice(0, 140);
}
