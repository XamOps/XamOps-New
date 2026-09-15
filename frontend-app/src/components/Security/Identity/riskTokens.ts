/**
 * Visual vocabulary for IAM permission risk.
 *
 * <p>Extracted into its own module because the canvas, the card and the KPI strip all need
 * the same mapping, and the three existing severity maps in this codebase
 * (SecurityFindingsSection's SEVERITY_CONFIG, GcpSecurityPage's four parallel records) have
 * already drifted from each other. One table here keeps a CRITICAL identity the same colour
 * as a CRITICAL finding on the sibling tab.
 *
 * <p>Colours match the Security Center's existing scale — red-500 / orange-500 / amber /
 * blue-500 — so the Identity tab reads as part of the same product rather than a bolt-on.
 */

import type { RiskLevel } from '../../../types/userManagement';

export interface RiskToken {
  /** Human label for badges. */
  label: string;
  /** Iconify id. */
  icon: string;
  /** Solid badge, for the severity pill on a finding row. */
  solid: string;
  /** Soft badge with border, for chips inside the card body. */
  soft: string;
  /** Small status dot. */
  dot: string;
  /** Numeric colour for KPI figures, matching SecurityKPICards' raw-rgb convention. */
  kpi: string;
}

export const RISK_TOKENS: Record<RiskLevel, RiskToken> = {
  CRITICAL: {
    label: 'Critical',
    icon: 'mdi:alert-circle',
    solid: 'bg-red-500 text-white',
    soft: 'bg-red-50 text-red-700 border-red-200',
    dot: 'bg-red-500',
    kpi: 'rgb(220,38,38)',
  },
  HIGH: {
    label: 'High',
    icon: 'mdi:alert',
    solid: 'bg-orange-500 text-white',
    soft: 'bg-orange-50 text-orange-700 border-orange-200',
    dot: 'bg-orange-500',
    kpi: 'rgb(234,88,12)',
  },
  MEDIUM: {
    label: 'Medium',
    icon: 'mdi:minus-circle',
    solid: 'bg-amber-400 text-gray-900',
    soft: 'bg-amber-50 text-amber-700 border-amber-200',
    dot: 'bg-amber-500',
    kpi: 'rgb(161,98,7)',
  },
  LOW: {
    label: 'Low',
    icon: 'mdi:information',
    solid: 'bg-blue-500 text-white',
    soft: 'bg-blue-50 text-blue-700 border-blue-200',
    dot: 'bg-blue-400',
    kpi: 'rgb(37,99,235)',
  },
  NONE: {
    label: 'No excess access',
    icon: 'mdi:shield-check',
    solid: 'bg-emerald-500 text-white',
    soft: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    dot: 'bg-emerald-500',
    kpi: 'rgb(5,150,105)',
  },
};

/**
 * Token for a possibly-absent risk level.
 *
 * <p>Falls back to a neutral "unknown" rather than to NONE. NONE means "audited and clean",
 * and an identity the audit could not read must never borrow that reassurance — that is the
 * whole failure mode {@code permissionAnalysisDegraded} exists to prevent.
 */
export const UNKNOWN_TOKEN: RiskToken = {
  label: 'Not assessed',
  icon: 'mdi:help-circle-outline',
  solid: 'bg-[rgba(0,0,0,0.35)] text-white',
  soft: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.55)] border-[rgba(0,0,0,0.12)]',
  dot: 'bg-[rgba(0,0,0,0.25)]',
  kpi: 'rgb(120,120,120)',
};

export function riskToken(level?: RiskLevel | null): RiskToken {
  if (!level) return UNKNOWN_TOKEN;
  return RISK_TOKENS[level] ?? UNKNOWN_TOKEN;
}

/**
 * Card shell classes.
 *
 * <p>A flagged identity gets a red border and tinted ground regardless of whether its worst
 * finding is CRITICAL or HIGH — the request was for one unmistakable red flag on excess
 * permission, and the precise severity is carried by the badge inside. Splitting the card
 * colour too would make HIGH read as a lesser category of problem than it is.
 */
export function cardShell(flagged: boolean): string {
  return flagged
    ? 'border-red-200 bg-red-50/40 hover:border-red-300'
    : 'border-[rgba(0,0,0,0.12)] bg-white hover:border-[rgba(0,0,0,0.22)]';
}
