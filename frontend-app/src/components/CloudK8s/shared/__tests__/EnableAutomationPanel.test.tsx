import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { EnableAutomationPanel, automationChecklist } from '../EnableAutomationPanel';
import { READINESS_KEYS, type AgentStatus, type CapabilitiesDto } from '../../../../types/k8sAutomation';

interface Options {
  accessLevel?: 'READ_WRITE' | 'READ_ONLY';
  status?: string;
  userReasons?: string[];
  detail?: string | null;
  agent?: AgentStatus;
  automationAgent?: AgentStatus;
  readyKeys?: readonly string[];
  listed?: boolean;
}

function caps(o: Options = {}): CapabilitiesDto {
  const connection = { accessLevel: o.accessLevel ?? 'READ_WRITE', status: o.status ?? 'CONNECTED' } as const;
  return {
    schemaVersion: 1,
    accountId: '111111111111',
    generatedAt: '2026-10-10T09:30:00Z',
    connection,
    user: { canChangeAutomation: !(o.userReasons ?? []).length, reasons: o.userReasons ?? [], detail: o.detail ?? null },
    writeGate: { allowed: true, reasons: [] },
    readiness: Object.fromEntries(READINESS_KEYS.map((k) => [k, (o.readyKeys ?? []).includes(k)
      ? { ready: true, reason: null, message: null }
      : { ready: false, reason: 'CONTROLLER_NOT_READY', message: 'not ready' }])),
    verticalEngine: { enabled: true },
    clusters: o.listed === false ? [] : [{
      clusterName: 'prod-eks', registered: true, connection,
      writeGate: { allowed: true, reasons: [] }, dataSourceMode: 'agent',
      agent: { status: o.agent ?? 'CONNECTED', lastDataAt: null, version: null },
      automationAgent: { status: o.automationAgent ?? 'CONNECTED', lastSeenAt: null, version: null },
      nodeAutomation: { enabled: false, mode: null, evictorAnalyseOnly: null, derivedState: 'OFF' },
      dataSources: [],
    }],
  };
}

const unmetIds = (data: CapabilitiesDto, cluster: string | null = 'prod-eks', demo = false) =>
  automationChecklist(data, cluster, demo).filter((r) => !r.met).map((r) => r.id);

function renderPanel(data: CapabilitiesDto, cluster: string | null = 'prod-eks', demo = false) {
  render(<MemoryRouter><EnableAutomationPanel id="enable" data={data} cluster={cluster} demo={demo} /></MemoryRouter>);
  return screen.getByRole('region', { name: 'What automation needs' });
}

const row = (panel: HTMLElement, id: string) => {
  const el = panel.querySelector(`[data-row="${id}"]`) as HTMLElement | null;
  expect(el, `row ${id}`).not.toBeNull();
  return el!;
};

describe('automationChecklist', () => {
  it('lists only controller validation when everything a user can fix is in place', () => {
    expect(unmetIds(caps())).toEqual(['controllers']);
    expect(unmetIds(caps({ readyKeys: READINESS_KEYS }))).toEqual([]);
  });

  it('marks each unmet requirement', () => {
    expect(unmetIds(caps({ accessLevel: 'READ_ONLY' }))).toEqual(['read-write', 'controllers']);
    expect(unmetIds(caps({ status: 'PENDING' }))).toEqual(['verified', 'controllers']);
    expect(unmetIds(caps({ userReasons: ['NO_USER_PERMISSION'], detail: 'TAB_GRANT' }))).toEqual(['permission', 'controllers']);
    expect(unmetIds(caps({ automationAgent: 'NOT_INSTALLED' }))).toEqual(['automation-agent', 'controllers']);
    expect(unmetIds(caps({ agent: 'NOT_INSTALLED', automationAgent: 'NOT_INSTALLED' })))
      .toEqual(['xamops-agent', 'automation-agent', 'controllers']);
    expect(unmetIds(caps({ listed: false }))).toEqual(['xamops-agent', 'automation-agent', 'controllers']);
    expect(unmetIds(caps(), null)).toEqual(['controllers']);
    expect(unmetIds(caps(), 'prod-eks', true)).toEqual(['controllers', 'demo']);
  });

  it('treats a stale agent as present', () => {
    expect(unmetIds(caps({ agent: 'STALE', automationAgent: 'STALE' }))).toEqual(['controllers']);
  });
});

describe('EnableAutomationPanel', () => {
  it('holds the read-write explainer with Contact support and Account Manager, never Add Account', () => {
    const panel = renderPanel(caps({ accessLevel: 'READ_ONLY' }));
    const readWrite = row(panel, 'read-write');
    expect(readWrite).toHaveTextContent('Read-write connection');
    expect(readWrite).toHaveTextContent('Read-only connection. Automation changes need read-write access.');
    expect(readWrite).toHaveTextContent("XamOps can't switch a read-only connection to read-write in place.");
    expect(within(readWrite).getByRole('link', { name: 'Contact support' })).toHaveAttribute('href', '/tickets');
    expect(within(readWrite).getByRole('link', { name: 'Open Account Manager' })).toHaveAttribute('href', '/account-manager');
    for (const link of panel.querySelectorAll('a')) {
      expect(link.getAttribute('href') ?? '').not.toMatch(/add-account/i);
    }
  });

  it('gives each unmet row its remedy', () => {
    const panel = renderPanel(caps({
      status: 'PENDING', userReasons: ['NO_USER_PERMISSION'], detail: 'TAB_GRANT', automationAgent: 'NOT_INSTALLED',
    }));
    expect(within(row(panel, 'verified')).getByRole('link', { name: 'Open Account Manager' }))
      .toHaveAttribute('href', '/account-manager');
    expect(row(panel, 'permission')).toHaveTextContent('Ask your XamOps administrator to allow automation changes for your user.');
    expect(within(row(panel, 'automation-agent')).getByRole('link', { name: 'Install the node automation agent' }))
      .toHaveAttribute('href', '/?tab=data-sources&sub=install#automation-agent');
    expect(row(panel, 'controllers')).toHaveTextContent("9 of 9 controllers haven't passed safety validation yet, so their live changes stay off.");
    expect(within(row(panel, 'controllers')).getByRole('button', { name: 'Why?' })).toBeInTheDocument();
  });

  it('uses the detail copy for role and impersonation', () => {
    const role = renderPanel(caps({ userReasons: ['NO_USER_PERMISSION'], detail: 'ROLE' }));
    expect(row(role, 'permission')).toHaveTextContent('Your role can view automation but not change it.');
  });

  it('points an unmonitored cluster at monitoring setup', () => {
    const panel = renderPanel(caps({ agent: 'NOT_INSTALLED', automationAgent: 'NOT_INSTALLED' }));
    expect(row(panel, 'xamops-agent')).toHaveTextContent("This cluster isn't monitored yet. Install the XamOps agent first.");
    expect(within(row(panel, 'xamops-agent')).getByRole('link', { name: 'Set up monitoring' })).toBeInTheDocument();
  });

  it('says so when nothing else is needed', () => {
    const panel = renderPanel(caps({ readyKeys: READINESS_KEYS }));
    expect(panel).toHaveTextContent('Nothing else is needed. Automation changes are allowed on this cluster.');
  });
});
