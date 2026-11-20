import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

const ACCOUNT = '123456789012';

vi.mock('../../contexts/AccountContext', async importOriginal => ({
  ...(await importOriginal<typeof import('../../contexts/AccountContext')>()),
  useAccounts: () => ({ selectedAccountIds: [ACCOUNT] }),
}));

import SecurityPage from '../SecurityPage';
import { MFA_ALICE, MFA_BOB, SSH_A, SSH_B } from '../../components/Security/Findings/__tests__/fixtures';

const SSH = SSH_A.description;
const MFA = MFA_ALICE.description;

interface Answer { status: number; body: unknown }
const ok = (body: unknown): Answer => ({ status: 200, body });

let findingsAnswers: Answer[];
let fetchMock: ReturnType<typeof vi.fn>;
let alertSpy: ReturnType<typeof vi.spyOn>;

const calls = () => fetchMock.mock.calls.map(([url, init]) => ({ url: String(url), method: (init as RequestInit | undefined)?.method ?? 'GET' }));
const posts = () => calls().filter(call => call.method === 'POST');

beforeEach(() => {
  findingsAnswers = [ok([SSH_A, SSH_B, MFA_ALICE, MFA_BOB])];
  fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    let answer: Answer = { status: 404, body: {} };
    if (url.startsWith('/api/xamops/security/findings?')) answer = findingsAnswers.length > 1 ? findingsAnswers.shift()! : findingsAnswers[0];
    else if (url.startsWith('/api/xamops/security/prowler/status')) answer = ok({ status: 'UNKNOWN' });
    else if (url.startsWith('/api/xamops/security/findings/resolve')) answer = ok({ status: 'RESOLVED' });
    else if (url.startsWith('/api/xamops/security/findings/restore')) answer = ok({ status: 'RESTORED' });
    return { ok: answer.status < 400, status: answer.status, json: async () => answer.body } as Response;
  });
  vi.stubGlobal('fetch', fetchMock);
  alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function renderPage(path = '/security') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SecurityPage />
    </MemoryRouter>,
  );
}

const pageTable = () => screen.getAllByRole('table')[0];
const titles = () => within(pageTable()).getAllByRole('row').slice(1)
  .map(row => within(row).queryAllByRole('cell')[0]?.textContent ?? '');

describe('SecurityPage', () => {
  it('loads the findings once and shows them grouped under the Findings tab', async () => {
    renderPage();
    expect(await screen.findByText(SSH)).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Misconfigurations' })).toBeInTheDocument();
    expect(titles()).toEqual([SSH, MFA]);
    expect(screen.getByRole('group', { name: 'Total Affected Resources' })).toHaveTextContent('4');
    const loads = calls().filter(call => call.url.startsWith('/api/xamops/security/findings?'));
    expect(loads).toEqual([{ url: `/api/xamops/security/findings?accountId=${ACCOUNT}&forceRefresh=false`, method: 'GET' }]);
    for (const tab of ['Findings', 'Identity & Access', 'Alerts']) expect(screen.getByRole('tab', { name: tab })).toBeInTheDocument();
  });

  it('runs a forced scan from Run Scan', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText(SSH);
    await user.click(screen.getByRole('button', { name: 'Run Scan' }));
    await waitFor(() => expect(calls().map(call => call.url))
      .toContain(`/api/xamops/security/findings?accountId=${ACCOUNT}&forceRefresh=true`));
    expect(await screen.findByText(SSH)).toBeInTheDocument();
  });

  it('marks a resource fixed from the drawer, and Undo puts it back', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByText(SSH));
    const drawer = within(await screen.findByRole('dialog', { name: SSH }));
    await user.click(drawer.getByRole('tab', { name: 'Affected Resources' }));
    await user.click(drawer.getByRole('button', { name: 'Mark fixed: VPC on sg-0aaa1111' }));

    expect(drawer.getByText('Showing 1 - 1 of 1')).toBeInTheDocument();
    await waitFor(() => expect(posts()).toHaveLength(1));
    const resolve = new URL(posts()[0].url, 'http://localhost');
    expect(resolve.pathname).toBe('/api/xamops/security/findings/resolve');
    expect(Object.fromEntries(resolve.searchParams)).toEqual({
      accountId: ACCOUNT, resourceId: 'sg-0aaa1111', controlId: '4.1', severity: 'Critical', title: SSH,
    });

    const toast = await screen.findByRole('status');
    expect(toast).toHaveTextContent('Marked fixed');
    await user.click(within(toast).getByRole('button', { name: 'Undo' }));
    await waitFor(() => expect(posts().map(call => new URL(call.url, 'http://localhost').pathname))
      .toEqual(['/api/xamops/security/findings/resolve', '/api/xamops/security/findings/restore']));
    await waitFor(() => expect(drawer.getByText('Showing 1 - 2 of 2')).toBeInTheDocument());
  });

  it('opens an affected resource in a second drawer with the selected account, and marks it fixed there', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByText(SSH));
    const drawer = within(await screen.findByRole('dialog', { name: SSH }));
    await user.click(drawer.getByRole('tab', { name: 'Affected Resources' }));
    await user.click(drawer.getByRole('button', { name: 'sg-0bbb2222' }));
    const resource = within(await screen.findByRole('dialog', { name: 'sg-0bbb2222' }));
    expect(resource.getByRole('region', { name: 'Resource Details' })).toHaveTextContent(ACCOUNT);
    expect(resource.getByRole('link', { name: /Go to AWS/ }))
      .toHaveAttribute('href', expect.stringContaining('#SecurityGroup:groupId=sg-0bbb2222'));

    await user.click(resource.getByRole('button', { name: `Mark fixed: ${SSH} on sg-0bbb2222` }));
    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(new URL(posts()[0].url, 'http://localhost').searchParams.get('resourceId')).toBe('sg-0bbb2222');
    expect(await resource.findByText('No open misconfigurations on this resource.')).toBeInTheDocument();
    expect(await screen.findByRole('status')).toHaveTextContent('Marked fixed');
  });

  it('explains a failed load in the tab with Retry, without a blocking alert', async () => {
    findingsAnswers = [{ status: 500, body: [] }, ok([MFA_ALICE])];
    const user = userEvent.setup();
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the security findings (HTTP 500).');
    expect(alertSpy).not.toHaveBeenCalled();
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText(MFA)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('narrows the rows to a deep-linked resource', async () => {
    renderPage(`/security?resource=${encodeURIComponent(`arn:aws:ec2:ap-south-1:${ACCOUNT}:security-group/sg-0bbb2222`)}`);
    expect(await screen.findByText(SSH)).toBeInTheDocument();
    expect(titles()).toEqual([SSH]);
    expect(screen.getByText('Showing 1 - 1 of 1')).toBeInTheDocument();
    // Only sg-0bbb2222 matches the ARN's tail, so the check shows one affected resource.
    const affected = within(within(pageTable()).getAllByRole('row')[1]).getAllByRole('cell')[2];
    expect(affected).toHaveTextContent(/^1$/);
  });
});
