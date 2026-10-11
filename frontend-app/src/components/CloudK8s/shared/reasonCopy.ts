/**
 * The one copy table for CloudK8s gate reasons (docs/cloudk8s/ui-revamp-design.md §6.1):
 * visible text, the short label used in "Also needs: …", and the remedy, with detail variants
 * and the generic rendering for codes this UI doesn't know yet.
 */
import type { Gate, ReasonCode, ReasonDetail } from '../../../types/k8sAutomation';

/** Where a remedy leads. Section targets are cluster-page sections (tab/sub plus an anchor). */
export type RemedyTarget =
  | { kind: 'route'; to: string }
  | { kind: 'section'; tab: string; sub: string; anchor: string };

export type Remedy =
  | { kind: 'link'; label: string; target: RemedyTarget }
  | { kind: 'text'; label: string }
  | { kind: 'explainer'; label: string; explainer: 'read-write' | 'not-ready' }
  | { kind: 'retry'; label: string };

export interface ReasonCopy {
  text: string;
  /** Used in "Also needs: …"; null for reasons that are never listed. */
  short: string | null;
  remedies: Remedy[];
  /** Label shown before the message for a code the UI doesn't know. */
  heading?: string;
}

export const READ_WRITE_EXPLAINER =
  "XamOps can't switch a read-only connection to read-write in place. Ask XamOps support to switch it. "
  + 'Doing it yourself means removing this connection and connecting the account again, which deletes its '
  + 'history in XamOps. Recommendations stay visible meanwhile.';

export const NOT_READY_EXPLAINER =
  'XamOps turns this on only after its controller passes safety validation. Until then the server refuses '
  + 'the change, whatever is set here, so no setting on this page can switch it on.';

const INSTALL = (anchor: string) => ({ kind: 'section', tab: 'data-sources', sub: 'install', anchor }) as const;

const SET_UP_MONITORING: Remedy = { kind: 'link', label: 'Set up monitoring', target: INSTALL('xamops-agent') };

/** Copy per reason; detail variants are resolved in {@link reasonCopy}. */
const COPY: Record<ReasonCode, ReasonCopy> = {
  DEMO: { text: "Demo workspace. Changes aren't saved.", short: null, remedies: [] },
  PROVIDER_UNSUPPORTED: { text: 'Automation is available for EKS clusters.', short: 'an EKS cluster', remedies: [] },
  CONTROLLER_NOT_READY: {
    text: 'Automated apply is disabled until this controller passes safety validation.',
    short: 'safety validation',
    remedies: [{ kind: 'explainer', label: 'Why?', explainer: 'not-ready' }],
  },
  ANALYSE_ONLY: {
    text: 'Live consolidation is disabled until pod-fit simulation is available.',
    short: 'safety validation',
    remedies: [{ kind: 'explainer', label: 'Why?', explainer: 'not-ready' }],
  },
  NO_USER_PERMISSION: {
    text: "You don't have permission to change automation.",
    short: 'permission to change automation',
    remedies: [{ kind: 'text', label: 'Ask your XamOps administrator to allow automation changes for your user.' }],
  },
  READ_ONLY_CONNECTION: {
    text: 'Read-only connection. Automation changes need read-write access.',
    short: 'read-write access',
    remedies: [{ kind: 'explainer', label: 'Get read-write access', explainer: 'read-write' }],
  },
  CONNECTION_NOT_VERIFIED: {
    text: "This connection isn't verified. Reconnect the account to change automation.",
    short: 'a verified connection',
    remedies: [{ kind: 'link', label: 'Open Account Manager', target: { kind: 'route', to: '/account-manager' } }],
  },
  AGENT_NOT_INSTALLED: {
    text: "The automation agent isn't installed on this cluster.",
    short: 'the automation agent',
    remedies: [{ kind: 'link', label: 'Install the automation agent', target: INSTALL('automation-agent') }],
  },
  CLUSTER_ACCESS_NOT_CONFIGURED: {
    text: "Direct access isn't set up: no EKS access entry maps the XamOps role.",
    short: 'cluster API access',
    remedies: [{ kind: 'link', label: 'Set up direct access', target: INSTALL('cluster-access') }],
  },
  CLUSTER_UNREACHABLE: {
    text: "Direct access isn't possible: the API server isn't reachable from XamOps (private endpoint).",
    short: 'cluster API access',
    remedies: [{ kind: 'link', label: 'See access options', target: INSTALL('cluster-access') }],
  },
  NO_METRICS_SOURCE: {
    text: "No usage metrics source for this cluster, so XamOps can't compute recommendations.",
    short: 'a usage metrics source',
    remedies: [{ kind: 'link', label: 'See metrics options', target: INSTALL('usage-metrics') }],
  },
  INSUFFICIENT_HISTORY: { text: 'Not enough usage history yet.', short: 'more usage history', remedies: [] },
  GITOPS_MANAGED: {
    text: "Managed by Argo CD or Flux. XamOps won't patch its template.",
    short: 'an in-place apply method',
    remedies: [{ kind: 'text', label: 'Use in-place resize' }],
  },
  MISSING_AWS_PERMISSION: {
    text: "XamOps can't read a required AWS action in this account.",
    short: 'an updated connection',
    remedies: [{ kind: 'link', label: 'Update connection', target: { kind: 'route', to: '/account-manager' } }],
  },
  UNKNOWN: { text: "Couldn't check automation access.", short: 'an access check', remedies: [{ kind: 'retry', label: 'Retry' }] },
  GUARDRAIL_LOCKED: { text: 'Always on while automation lacks pod-fit simulation.', short: null, remedies: [] },
};

/** Detail variants (§6.1). */
const DETAIL_COPY: Partial<Record<ReasonDetail, ReasonCopy>> = {
  TAB_GRANT: COPY.NO_USER_PERMISSION,
  ROLE: { text: 'Your role can view automation but not change it.', short: 'permission to change automation', remedies: [] },
  IMPERSONATION: {
    text: "Automation changes aren't available while you're signed in as another user.",
    short: 'permission to change automation',
    remedies: [{ kind: 'text', label: 'Return to your own session to change automation.' }],
  },
  AUTOMATION_AGENT: COPY.AGENT_NOT_INSTALLED,
  XAMOPS_AGENT: {
    text: "This cluster isn't monitored yet. Install the XamOps agent first.",
    short: 'the XamOps agent',
    remedies: [SET_UP_MONITORING],
  },
  EXECUTION_PATH: {
    text: "The XamOps agent isn't connected, so XamOps can't send changes to this cluster.",
    short: 'a connected XamOps agent',
    remedies: [SET_UP_MONITORING],
  },
};

/** The generic rendering for a code this UI doesn't know: "Not allowed" + the server's message. */
export const UNKNOWN_CODE_HEADING = 'Not allowed';

export interface ReasonCopyOptions {
  /** INSUFFICIENT_HISTORY's policy window, e.g. "24h". */
  window?: string;
  /** MISSING_AWS_PERMISSION's IAM action, e.g. "logs:DescribeLogGroups". */
  action?: string;
}

/** Copy for one gate: server messages override the text; unknown codes render generically. */
export function reasonCopy(gate: Gate, options: ReasonCopyOptions = {}): ReasonCopy {
  if (gate.raw !== undefined) {
    return {
      heading: UNKNOWN_CODE_HEADING,
      text: gate.message ?? "XamOps refused this change.",
      short: 'an access check',
      remedies: [],
    };
  }
  const base = (gate.detail && DETAIL_COPY[gate.detail]) || COPY[gate.reason];
  let text = gate.message ?? base.text;
  if (!gate.message && gate.reason === 'INSUFFICIENT_HISTORY' && options.window) {
    text = `Not enough usage history yet. Recommendations need ${options.window} of data.`;
  }
  if (!gate.message && gate.reason === 'MISSING_AWS_PERMISSION' && options.action) {
    text = `XamOps can't read ${options.action} in this account.`;
  }
  let remedies = base.remedies;
  if (gate.detail === 'EXECUTION_PATH') {
    const direct = gate.directPath ? COPY[gate.directPath] : null;
    if (direct) {
      text = `${text} ${direct.text}`;
    }
    if (gate.directPath === 'CLUSTER_ACCESS_NOT_CONFIGURED') {
      remedies = [...remedies, { kind: 'link', label: 'Or set up direct access', target: INSTALL('cluster-access') }];
    }
  }
  return { text, short: base.short, remedies };
}

/** Short labels of the remaining gates, each once, never DEMO (§6.1, §6.4). */
export function alsoNeeds(also: Gate[]): string[] {
  const out: string[] = [];
  for (const gate of also) {
    if (gate.reason === 'DEMO') continue;
    const short = reasonCopy(gate).short;
    if (short && !out.includes(short)) out.push(short);
  }
  return out;
}

/** "Also needs: read-write access, a connected XamOps agent", or null when there is nothing to add. */
export function alsoNeedsLine(also: Gate[]): string | null {
  const labels = alsoNeeds(also);
  return labels.length ? `Also needs: ${labels.join(', ')}` : null;
}
