/**
 * The status tone / icon maps from glossary-additions.md §2. Colour reaches a page only through a
 * component that pairs it with an icon and a label (CLAUDE.md), so these maps are the single place
 * that pins each meaning to a tone and a bundled `mdi:*` icon. Components in this folder read a tone
 * and still take `icon` and `label` as required props — the lookups here are the typed source for
 * those props, never a way to colour something without a label.
 *
 * Tones map to the semantic status tokens (src/styles/k8sSemanticTokens.css); the components turn a
 * `Tone` into `text-status-*` / `bg-status-*` classes. There is no raw hue anywhere.
 */

/** The five tones of §2. Each always carries its own icon and label. */
export type Tone = 'good' | 'warn' | 'bad' | 'info' | 'off';

export interface ToneIcon {
  tone: Tone;
  /** A bundled Iconify `mdi:*` id. */
  icon: string;
}

/**
 * Workload status (glossary §2.1), keyed by the stable v2 status keys. The visible wording is the
 * component's `label`; this table only fixes the tone and icon.
 */
export type WorkloadStatusKey =
  | 'over-provisioned'
  | 'needs-more'
  | 'applied-watching'
  | 'left-alone-recently'
  | 'right-sized'
  | 'left-alone-managed'
  | 'not-running'
  | 'not-enough-data'
  | 'skipped'
  | 'off';

export const WORKLOAD_STATUS_TONES: Record<WorkloadStatusKey, ToneIcon> = {
  'over-provisioned': { tone: 'warn', icon: 'mdi:arrow-collapse-down' },
  'needs-more': { tone: 'bad', icon: 'mdi:speedometer' },
  'applied-watching': { tone: 'good', icon: 'mdi:check-circle' },
  'left-alone-recently': { tone: 'off', icon: 'mdi:minus-circle-outline' },
  'right-sized': { tone: 'good', icon: 'mdi:check-circle' },
  'left-alone-managed': { tone: 'off', icon: 'mdi:minus-circle-outline' },
  'not-running': { tone: 'bad', icon: 'mdi:alert-octagon-outline' },
  'not-enough-data': { tone: 'info', icon: 'mdi:progress-clock' },
  skipped: { tone: 'off', icon: 'mdi:debug-step-over' },
  off: { tone: 'off', icon: 'mdi:minus-circle-outline' },
};

/** Apply / audit results (glossary §2.2), keyed by their terminal meaning. */
export type ResultKey =
  | 'applied'
  | 'applied-put-back'
  | 'test-run'
  | 'deferred'
  | 'infeasible'
  | 'partly-applied'
  | 'refused'
  | 'failed'
  | 'not-confirmed'
  | 'expired';

export const RESULT_TONES: Record<ResultKey, ToneIcon> = {
  applied: { tone: 'good', icon: 'mdi:check-circle' },
  'applied-put-back': { tone: 'good', icon: 'mdi:undo-variant' },
  'test-run': { tone: 'info', icon: 'mdi:flask-outline' },
  deferred: { tone: 'warn', icon: 'mdi:clock-alert-outline' },
  infeasible: { tone: 'bad', icon: 'mdi:close-octagon-outline' },
  'partly-applied': { tone: 'warn', icon: 'mdi:progress-alert' },
  refused: { tone: 'bad', icon: 'mdi:cancel' },
  failed: { tone: 'bad', icon: 'mdi:alert-octagon-outline' },
  'not-confirmed': { tone: 'warn', icon: 'mdi:progress-question' },
  expired: { tone: 'off', icon: 'mdi:timer-off-outline' },
};

/** Feature status (glossary §2.3): tone only; the icon is the feature accent's own icon (§11). */
export type FeatureStatusKey =
  | 'on-apply'
  | 'on-suggest'
  | 'waiting'
  | 'unreachable'
  | 'off'
  | 'at-maximum';

export const FEATURE_STATUS_TONES: Record<FeatureStatusKey, Tone> = {
  'on-apply': 'good',
  'on-suggest': 'warn',
  waiting: 'warn',
  unreachable: 'bad',
  off: 'off',
  'at-maximum': 'warn',
};

/**
 * The backend's terminal apply vocabulary (`ApplyOutcomeWire.terminal`) mapped onto a result tone,
 * so the ApplyStepper can read the wire outcome directly. Unknown values fall back to `failed`,
 * which is the safe side: only `APPLIED` may render as a success (workloadRightsizing.ts).
 */
export function resultKeyForTerminal(terminal: string | null | undefined): ResultKey {
  switch (terminal) {
    case 'APPLIED':
      return 'applied';
    case 'DRY_RUN':
      return 'test-run';
    case 'DEFERRED':
      return 'deferred';
    case 'INFEASIBLE':
      return 'infeasible';
    case 'PARTIALLY_APPLIED':
      return 'partly-applied';
    case 'REFUSED':
      return 'refused';
    case 'STUCK_IN_PROGRESS':
      return 'not-confirmed';
    case 'EXPIRED':
      return 'expired';
    case 'RESTART_DETECTED':
    case 'NOT_READY':
    case 'FAILED':
    default:
      return 'failed';
  }
}
