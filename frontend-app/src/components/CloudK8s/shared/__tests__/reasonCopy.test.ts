import { describe, expect, it } from 'vitest';
import {
  NOT_READY_EXPLAINER,
  READ_WRITE_EXPLAINER,
  UNKNOWN_CODE_HEADING,
  alsoNeeds,
  alsoNeedsLine,
  reasonCopy,
  type Remedy,
} from '../reasonCopy';
import {
  AGENT_NOT_INSTALLED_DETAILS,
  NO_USER_PERMISSION_DETAILS,
  REASON_CODES,
  type Gate,
} from '../../../../types/k8sAutomation';

/** Design §6.1, verbatim. */
const TEXT: Record<string, string> = {
  DEMO: "Demo workspace. Changes aren't saved.",
  PROVIDER_UNSUPPORTED: 'Automation is available for EKS clusters.',
  CONTROLLER_NOT_READY: 'Automated apply is disabled until this controller passes safety validation.',
  ANALYSE_ONLY: 'Live consolidation is disabled until pod-fit simulation is available.',
  'NO_USER_PERMISSION/TAB_GRANT': "You don't have permission to change automation.",
  'NO_USER_PERMISSION/ROLE': 'Your role can view automation but not change it.',
  'NO_USER_PERMISSION/IMPERSONATION': "Automation changes aren't available while you're signed in as another user.",
  READ_ONLY_CONNECTION: 'Read-only connection. Automation changes need read-write access.',
  CONNECTION_NOT_VERIFIED: "This connection isn't verified. Reconnect the account to change automation.",
  'AGENT_NOT_INSTALLED/AUTOMATION_AGENT': "Node automation needs the xamops-autoscaler agent, which isn't installed on this cluster. "
    + "Workload rightsizing, HPA and VPA automation run server-side and don't need this agent.",
  'AGENT_NOT_INSTALLED/XAMOPS_AGENT': "This cluster isn't monitored yet. Install the XamOps agent first.",
  'AGENT_NOT_INSTALLED/EXECUTION_PATH': "The XamOps agent isn't connected, so XamOps can't send changes to this cluster.",
  CLUSTER_ACCESS_NOT_CONFIGURED: "Direct access isn't set up: no EKS access entry maps the XamOps role.",
  CLUSTER_UNREACHABLE: "Direct access isn't possible: the API server isn't reachable from XamOps (private endpoint).",
  NO_METRICS_SOURCE: "No usage metrics source for this cluster, so XamOps can't compute recommendations.",
  GITOPS_MANAGED: "Managed by Argo CD or Flux. XamOps won't patch its template.",
  UNKNOWN: "Couldn't check automation access.",
  GUARDRAIL_LOCKED: 'Always on while automation lacks pod-fit simulation.',
};

const SHORT: Record<string, string | null> = {
  DEMO: null,
  PROVIDER_UNSUPPORTED: 'an EKS cluster',
  CONTROLLER_NOT_READY: 'safety validation',
  ANALYSE_ONLY: 'safety validation',
  NO_USER_PERMISSION: 'permission to change automation',
  READ_ONLY_CONNECTION: 'read-write access',
  CONNECTION_NOT_VERIFIED: 'a verified connection',
  'AGENT_NOT_INSTALLED/AUTOMATION_AGENT': 'the node automation agent',
  'AGENT_NOT_INSTALLED/XAMOPS_AGENT': 'the XamOps agent',
  'AGENT_NOT_INSTALLED/EXECUTION_PATH': 'a connected XamOps agent',
  CLUSTER_ACCESS_NOT_CONFIGURED: 'cluster API access',
  CLUSTER_UNREACHABLE: 'cluster API access',
  NO_METRICS_SOURCE: 'a usage metrics source',
  INSUFFICIENT_HISTORY: 'more usage history',
  GITOPS_MANAGED: 'an in-place apply method',
  MISSING_AWS_PERMISSION: 'an updated connection',
  UNKNOWN: 'an access check',
  GUARDRAIL_LOCKED: null,
};

const gateOf = (key: string): Gate => {
  const [reason, detail] = key.split('/');
  return (detail ? { reason, detail } : { reason }) as Gate;
};

const labels = (remedies: Remedy[]) => remedies.map((r) => r.label);

describe('reasonCopy', () => {
  it('has text for every code and every detail', () => {
    const keys = [
      ...REASON_CODES,
      ...NO_USER_PERMISSION_DETAILS.map((d) => `NO_USER_PERMISSION/${d}`),
      ...AGENT_NOT_INSTALLED_DETAILS.map((d) => `AGENT_NOT_INSTALLED/${d}`),
    ];
    for (const key of keys) {
      expect(reasonCopy(gateOf(key)).text, key).toMatch(/\S/);
    }
  });

  it.each(Object.entries(TEXT))('%s reads exactly as §6.1', (key, text) => {
    expect(reasonCopy(gateOf(key)).text).toBe(text);
  });

  it.each(Object.entries(SHORT))('%s has its short label', (key, short) => {
    expect(reasonCopy(gateOf(key)).short).toBe(short);
  });

  it('gives every listable code a short label; only DEMO and GUARDRAIL_LOCKED are never listed', () => {
    for (const reason of REASON_CODES) {
      const short = reasonCopy({ reason }).short;
      if (reason === 'DEMO' || reason === 'GUARDRAIL_LOCKED') expect(short).toBeNull();
      else expect(short, reason).toMatch(/\S/);
    }
  });

  it('defaults NO_USER_PERMISSION to the TAB_GRANT copy and AGENT_NOT_INSTALLED to the automation agent', () => {
    expect(reasonCopy({ reason: 'NO_USER_PERMISSION' }).text).toBe(TEXT['NO_USER_PERMISSION/TAB_GRANT']);
    expect(reasonCopy({ reason: 'AGENT_NOT_INSTALLED' }).text).toBe(TEXT['AGENT_NOT_INSTALLED/AUTOMATION_AGENT']);
  });

  it('lets a server message override the text and keeps the short label', () => {
    const copy = reasonCopy({ reason: 'CONTROLLER_NOT_READY', message: 'Rebalancing is blocked.' });
    expect(copy.text).toBe('Rebalancing is blocked.');
    expect(copy.short).toBe('safety validation');
  });

  it('fills in the history window and the missing AWS action', () => {
    expect(reasonCopy({ reason: 'INSUFFICIENT_HISTORY' }, { window: '24h' }).text)
      .toBe('Not enough usage history yet. Recommendations need 24h of data.');
    expect(reasonCopy({ reason: 'MISSING_AWS_PERMISSION' }, { action: 'logs:DescribeLogGroups' }).text)
      .toBe("XamOps can't read logs:DescribeLogGroups in this account.");
  });

  it('renders an unknown code generically: "Not allowed", the message, "an access check"', () => {
    const copy = reasonCopy({ reason: 'UNKNOWN', raw: 'K8S_SOMETHING_NEW', message: 'The engine refused this change.' });
    expect(copy.heading).toBe(UNKNOWN_CODE_HEADING);
    expect(UNKNOWN_CODE_HEADING).toBe('Not allowed');
    expect(copy.text).toBe('The engine refused this change.');
    expect(copy.short).toBe('an access check');
    expect(copy.remedies).toEqual([]);
  });

  it('adds the direct-path line under EXECUTION_PATH, and the direct-access remedy only when not configured', () => {
    const notConfigured = reasonCopy({ reason: 'AGENT_NOT_INSTALLED', detail: 'EXECUTION_PATH', directPath: 'CLUSTER_ACCESS_NOT_CONFIGURED' });
    expect(notConfigured.text).toBe(`${TEXT['AGENT_NOT_INSTALLED/EXECUTION_PATH']} ${TEXT.CLUSTER_ACCESS_NOT_CONFIGURED}`);
    expect(labels(notConfigured.remedies)).toEqual(['Set up monitoring', 'Or set up direct access']);

    const unreachable = reasonCopy({ reason: 'AGENT_NOT_INSTALLED', detail: 'EXECUTION_PATH', directPath: 'CLUSTER_UNREACHABLE' });
    expect(unreachable.text).toBe(`${TEXT['AGENT_NOT_INSTALLED/EXECUTION_PATH']} ${TEXT.CLUSTER_UNREACHABLE}`);
    expect(labels(unreachable.remedies)).toEqual(['Set up monitoring']);
  });

  it('carries the §6.1 remedies', () => {
    expect(reasonCopy({ reason: 'READ_ONLY_CONNECTION' }).remedies)
      .toEqual([{ kind: 'explainer', label: 'Get read-write access', explainer: 'read-write' }]);
    expect(reasonCopy({ reason: 'CONNECTION_NOT_VERIFIED' }).remedies)
      .toEqual([{ kind: 'link', label: 'Open Account Manager', target: { kind: 'route', to: '/account-manager' } }]);
    expect(labels(reasonCopy({ reason: 'NO_USER_PERMISSION', detail: 'TAB_GRANT' }).remedies))
      .toEqual(['Ask your XamOps administrator to allow automation changes for your user.']);
    expect(reasonCopy({ reason: 'NO_USER_PERMISSION', detail: 'TAB_GRANT' }).remedies[0].kind).toBe('text');
    expect(labels(reasonCopy({ reason: 'NO_USER_PERMISSION', detail: 'IMPERSONATION' }).remedies))
      .toEqual(['Return to your own session to change automation.']);
    expect(reasonCopy({ reason: 'NO_USER_PERMISSION', detail: 'ROLE' }).remedies).toEqual([]);
    expect(labels(reasonCopy({ reason: 'CONTROLLER_NOT_READY' }).remedies)).toEqual(['Why?']);
    expect(labels(reasonCopy({ reason: 'ANALYSE_ONLY' }).remedies)).toEqual(['Why?']);
    expect(labels(reasonCopy({ reason: 'AGENT_NOT_INSTALLED', detail: 'AUTOMATION_AGENT' }).remedies)).toEqual(['Install the node automation agent']);
    expect(labels(reasonCopy({ reason: 'AGENT_NOT_INSTALLED', detail: 'XAMOPS_AGENT' }).remedies)).toEqual(['Set up monitoring']);
    expect(labels(reasonCopy({ reason: 'CLUSTER_ACCESS_NOT_CONFIGURED' }).remedies)).toEqual(['Set up direct access']);
    expect(labels(reasonCopy({ reason: 'CLUSTER_UNREACHABLE' }).remedies)).toEqual(['See access options']);
    expect(labels(reasonCopy({ reason: 'NO_METRICS_SOURCE' }).remedies)).toEqual(['See metrics options']);
    expect(labels(reasonCopy({ reason: 'GITOPS_MANAGED' }).remedies)).toEqual(['Use in-place resize']);
    expect(labels(reasonCopy({ reason: 'MISSING_AWS_PERMISSION' }).remedies)).toEqual(['Update connection']);
    expect(reasonCopy({ reason: 'UNKNOWN' }).remedies).toEqual([{ kind: 'retry', label: 'Retry' }]);
    for (const reason of ['DEMO', 'PROVIDER_UNSUPPORTED', 'INSUFFICIENT_HISTORY', 'GUARDRAIL_LOCKED'] as const) {
      expect(reasonCopy({ reason }).remedies, reason).toEqual([]);
    }
  });

  it('never sends a remedy to Add Account', () => {
    const all = [
      ...REASON_CODES.map((reason) => reasonCopy({ reason })),
      ...NO_USER_PERMISSION_DETAILS.map((detail) => reasonCopy({ reason: 'NO_USER_PERMISSION', detail })),
      ...AGENT_NOT_INSTALLED_DETAILS.map((detail) => reasonCopy({ reason: 'AGENT_NOT_INSTALLED', detail })),
    ];
    for (const copy of all) {
      for (const remedy of copy.remedies) {
        expect(JSON.stringify(remedy)).not.toMatch(/add-account/i);
      }
    }
    expect(READ_WRITE_EXPLAINER).toContain("XamOps can't switch a read-only connection to read-write in place.");
    expect(NOT_READY_EXPLAINER).toMatch(/\S/);
  });
});

describe('alsoNeeds', () => {
  it('lists short labels, each once, never DEMO', () => {
    const also: Gate[] = [
      { reason: 'CONTROLLER_NOT_READY' },
      { reason: 'ANALYSE_ONLY' },
      { reason: 'READ_ONLY_CONNECTION' },
      { reason: 'CLUSTER_ACCESS_NOT_CONFIGURED' },
      { reason: 'CLUSTER_UNREACHABLE' },
      { reason: 'DEMO' },
      { reason: 'GUARDRAIL_LOCKED' },
    ];
    expect(alsoNeeds(also)).toEqual(['safety validation', 'read-write access', 'cluster API access']);
    expect(alsoNeedsLine(also)).toBe('Also needs: safety validation, read-write access, cluster API access');
  });

  it('is empty when only DEMO remains', () => {
    expect(alsoNeedsLine([{ reason: 'DEMO' }])).toBeNull();
    expect(alsoNeedsLine([])).toBeNull();
  });

  it('uses the detail short label and the generic one for unknown codes', () => {
    expect(alsoNeeds([
      { reason: 'AGENT_NOT_INSTALLED', detail: 'EXECUTION_PATH' },
      { reason: 'UNKNOWN', raw: 'K8S_NEW', message: 'x' },
      { reason: 'UNKNOWN' },
    ])).toEqual(['a connected XamOps agent', 'an access check']);
  });
});
