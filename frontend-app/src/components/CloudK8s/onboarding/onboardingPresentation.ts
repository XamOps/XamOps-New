import type { CapabilitiesOnboarding } from '../../../types/k8sAutomation';

/**
 * Maps the server-derived `onboarding` block to UI presentation (design §9.1): the status word, a
 * tone, the next action, and an optional muted secondary line. Pure and deterministic — the only
 * time-dependent rules (awaitingAgent age, identity-not-reported) take `now` as an argument so they
 * are testable. This is the read-only mapping; the OQ-6 "Write access" row is deferred to FEAT-003.
 */

export type Tone = 'off' | 'info' | 'good' | 'warn';

export interface OnboardingPresentation {
  word: string;
  tone: Tone;
  nextAction: string;
  /** Muted secondary line (Connection page), or null. */
  secondary: string | null;
}

const FIFTEEN_MIN_MS = 15 * 60 * 1000;
const TWO_HOURS_MS = 2 * 60 * 60 * 1000;

function ageMs(iso: string | null, now: number): number | null {
  if (!iso) {
    return null;
  }
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : now - t;
}

export function presentOnboarding(
  onboarding: CapabilitiesOnboarding | undefined,
  lastExchangeAt: string | null,
  now: number = Date.now(),
): OnboardingPresentation {
  // Absent onboarding block ⇒ treat as not installed (row 1).
  if (!onboarding) {
    return { word: 'Not installed', tone: 'off', nextAction: 'Connect', secondary: null };
  }

  switch (onboarding.state) {
    case 'UNKNOWN':
      return { word: 'Unknown', tone: 'off', nextAction: 'Retry', secondary: null };

    case 'NOT_INSTALLED': {
      if (onboarding.awaitingAgent) {
        const age = ageMs(lastExchangeAt, now);
        if (age !== null && age >= FIFTEEN_MIN_MS) {
          return { word: 'Agent not reporting', tone: 'warn', nextAction: 'Troubleshoot', secondary: null };
        }
        return { word: 'Waiting for agent', tone: 'info', nextAction: 'View progress', secondary: null };
      }
      return { word: 'Not installed', tone: 'off', nextAction: 'Connect', secondary: null };
    }

    case 'CONNECTED_READ_ONLY': {
      if (onboarding.automationDisabled) {
        return {
          word: 'Read-only',
          tone: 'good',
          nextAction: 'Enable automation',
          secondary:
            "Automation disabled. If you haven't yet, run the read-only command to remove the agent and its IAM role.",
        };
      }
      if (onboarding.stale) {
        return { word: 'Data stale', tone: 'warn', nextAction: 'Troubleshoot', secondary: null };
      }
      return { word: 'Read-only', tone: 'good', nextAction: 'Enable automation', secondary: null };
    }

    case 'AUTOMATION_ENABLED': {
      if (!onboarding.automationVerified) {
        const age = ageMs(onboarding.identitySeenAt, now);
        if (onboarding.identitySeenAt === null) {
          return { word: 'Automation · verifying', tone: 'info', nextAction: 'View', secondary: null };
        }
        if (age !== null && age > TWO_HOURS_MS) {
          return {
            word: 'Automation · identity not reported',
            tone: 'warn',
            nextAction: 'Troubleshoot',
            secondary: `Identity not reported since ${onboarding.identitySeenAt}`,
          };
        }
        return { word: 'Automation · verifying', tone: 'info', nextAction: 'View', secondary: null };
      }
      switch (onboarding.automationSubState) {
        case 'AUTOMATED':
          return { word: 'Automation · Automated', tone: 'good', nextAction: 'Open automation', secondary: null };
        case 'ANALYSIS':
          return { word: 'Automation · Analysis', tone: 'info', nextAction: 'Open automation', secondary: null };
        case 'OFF':
        default:
          return { word: 'Automation · Off', tone: 'off', nextAction: 'Open automation', secondary: null };
      }
    }

    default:
      return { word: 'Unknown', tone: 'off', nextAction: 'Retry', secondary: null };
  }
}
