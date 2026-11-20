import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { SecurityFinding } from '../../../../types/security';

// The export's save is stubbed; the CSV text itself is built with the real toCsv.
vi.mock('../../../../utils/csvExport', async importOriginal => ({
  ...(await importOriginal<typeof import('../../../../utils/csvExport')>()),
  downloadCsv: vi.fn(),
}));

import { downloadCsv } from '../../../../utils/csvExport';
import { findingKey } from '../../../../hooks/useSecurityData';
import SecurityFindingsTab from '../SecurityFindingsTab';
import { FINDINGS, LIVE_BUCKET, SSH_A, SSH_B, TITLES, finding } from './fixtures';

const download = downloadCsv as unknown as ReturnType<typeof vi.fn>;
const ACCOUNT = '123456789012';

afterEach(() => {
  vi.clearAllMocks();
});

interface Overrides {
  findings?: SecurityFinding[];
  loading?: boolean;
  error?: string | null;
  scanning?: boolean;
  resolvingKeys?: string[];
}

function setup(over: Overrides = {}) {
  const handlers = { onRunScan: vi.fn(), onRetry: vi.fn(), onResolve: vi.fn() };
  const element = (props: Overrides) => (
    <SecurityFindingsTab
      accountId={ACCOUNT}
      findings={props.findings ?? FINDINGS}
      loading={props.loading ?? false}
      error={props.error ?? null}
      scanning={props.scanning ?? false}
      resolvingKeys={props.resolvingKeys ?? []}
      {...handlers}
    />
  );
  const view = render(element(over));
  return { ...handlers, rerender: (next: Overrides) => view.rerender(element({ ...over, ...next })) };
}

const pageTable = () => screen.getAllByRole('table')[0];
const bodyRows = () => within(pageTable()).getAllByRole('row').slice(1);
const titles = () => bodyRows().map(row => within(row).queryAllByRole('cell')[0]?.textContent ?? '');
const card = (name: string) => screen.getByRole('group', { name });

describe('SecurityFindingsTab: the page', () => {
  it('renders the XamLens Misconfigurations layout', () => {
    setup();
    expect(screen.getByRole('heading', { level: 1, name: 'Misconfigurations' })).toBeInTheDocument();
    expect(screen.getByText('Auto-Scan runs twice a week')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run Scan' })).toBeEnabled();
    expect(screen.getByRole('searchbox', { name: 'Search' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Group by' })).toHaveDisplayValue('Select group by');
    expect(screen.getByRole('button', { name: /^Severity/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'More filters' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show analytics' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(pageTable()).getAllByRole('columnheader').map(th => th.textContent)).toEqual([
      'Misconfiguration', 'Severity', 'Affected Resources', 'Category', 'Regions', 'Frameworks',
    ]);
    expect(screen.getByRole('button', { name: /Columns/ })).toHaveTextContent('Columns 6');
    expect(screen.getByRole('button', { name: 'Export' })).toBeEnabled();
    expect(screen.getByText('Showing 1 - 8 of 8')).toBeInTheDocument();
  });

  it('lists one row per check with its severity, affected count, category, regions and framework', () => {
    setup();
    expect(titles()).toEqual(TITLES);
    const first = within(bodyRows()[0]);
    expect(first.getByText('Critical')).toBeInTheDocument();
    expect(first.getByText('2')).toBeInTheDocument();
    expect(first.getByText('VPC')).toBeInTheDocument();
    expect(first.getByText('ap-south-1')).toBeInTheDocument();
    expect(first.getByText('+1')).toHaveAttribute('title', 'us-east-1');
    expect(first.getByText('CIS AWS Foundations 4.1')).toBeInTheDocument();
  });

  it('shows the figures in both cards, following the search', async () => {
    const user = userEvent.setup();
    setup();
    expect(card('Total Affected Resources')).toHaveTextContent('10');
    const open = card('Open Misconfigurations');
    expect(open).toHaveTextContent('8');
    expect(open).toHaveTextContent('10 findings');
    for (const text of ['Critical 3', 'High 2', 'Medium 2', 'Low 1']) expect(open).toHaveTextContent(text);
    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'port(s) 22');
    expect(card('Total Affected Resources')).toHaveTextContent(/^Total Affected Resources2$/);
    expect(card('Open Misconfigurations')).toHaveTextContent('2 findings');
    expect(card('Open Misconfigurations')).toHaveTextContent('Critical 1');
  });

  it('hides and shows the cards with the chart button', async () => {
    const user = userEvent.setup();
    setup();
    const toggle = screen.getByRole('button', { name: 'Show analytics' });
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByRole('group', { name: 'Total Affected Resources' })).toBeNull();
    await user.click(toggle);
    expect(card('Open Misconfigurations')).toBeInTheDocument();
  });

  it('runs a scan, and shows it running', async () => {
    const user = userEvent.setup();
    const { onRunScan, rerender } = setup();
    await user.click(screen.getByRole('button', { name: 'Run Scan' }));
    expect(onRunScan).toHaveBeenCalledTimes(1);
    rerender({ scanning: true, loading: true });
    expect(screen.getByRole('button', { name: 'Scanning…' })).toBeDisabled();
    expect(screen.getByText('Loading misconfigurations…')).toBeInTheDocument();
  });

  it('searches titles, control ids and resource ids', async () => {
    const user = userEvent.setup();
    setup();
    const search = screen.getByRole('searchbox', { name: 'Search' });
    await user.type(search, 'MFA');
    expect(titles()).toEqual(['User has console access but MFA is not enabled.']);
    await user.clear(search);
    await user.type(search, 'cc6.1');
    expect(titles()).toEqual([TITLES[2], TITLES[5]]);
    await user.clear(search);
    await user.type(search, 'sg-0bbb');
    expect(titles()).toEqual([TITLES[0]]);
    await user.clear(search);
    await user.type(search, 'nothing like this');
    expect(screen.getByText('No matches')).toBeInTheDocument();
  });

  it('filters by Severity and by the More filters', async () => {
    const user = userEvent.setup();
    setup();
    await user.click(screen.getByRole('button', { name: /^Severity/ }));
    const severity = screen.getByRole('dialog', { name: 'Severity' });
    expect(within(severity).getAllByRole('checkbox')).toHaveLength(5);   // Select all and the four levels
    await user.click(within(severity).getByRole('checkbox', { name: /Critical/ }));
    expect(titles()).toEqual(TITLES.slice(0, 3));
    await user.click(within(severity).getByRole('button', { name: 'Clear' }));
    await user.keyboard('{Escape}');
    expect(titles()).toHaveLength(8);

    await user.click(screen.getByRole('button', { name: 'More filters' }));
    const more = screen.getByRole('dialog', { name: 'More filters' });
    expect(within(more).getAllByRole('button').map(b => b.textContent?.trim())).toEqual(['Category', 'Framework', 'Region']);
    await user.click(within(more).getByRole('button', { name: 'Region' }));
    await user.click(within(more).getByRole('checkbox', { name: /ap-south-1/ }));
    expect(titles()).toEqual([TITLES[0]]);
    await user.click(within(more).getByRole('button', { name: /^Framework/ }));
    await user.click(within(more).getByRole('checkbox', { name: /SOC2/ }));
    expect(screen.getByText('No matches')).toBeInTheDocument();
  });

  it('draws a header per group with Group By', async () => {
    const user = userEvent.setup();
    setup();
    const groupBy = screen.getByRole('combobox', { name: 'Group by' });
    expect(within(groupBy).getAllByRole('option').map(o => o.textContent)).toEqual(['Select group by', 'Severity', 'Frameworks']);
    const headers = () => within(pageTable()).getAllByRole('columnheader').slice(6).map(th => th.textContent);
    await user.selectOptions(groupBy, 'severity');
    expect(headers()).toEqual(['Critical 3', 'High 2', 'Medium 2', 'Low 1']);
    await user.selectOptions(groupBy, 'framework');
    expect(headers()).toEqual(['CIS AWS Foundations 4', 'Custom Best Practice 1', 'Security Hub 1', 'SOC2 2']);
  });

  it('hides a column through Columns and exports the filtered rows with the visible columns', async () => {
    const user = userEvent.setup();
    setup();
    await user.click(screen.getByRole('button', { name: /Columns/ }));
    await user.click(screen.getByRole('checkbox', { name: 'Frameworks' }));
    await user.keyboard('{Escape}');
    expect(screen.getByRole('button', { name: /Columns/ })).toHaveTextContent('Columns 5');
    expect(within(pageTable()).queryByRole('columnheader', { name: 'Frameworks' })).toBeNull();
    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'flow logs');
    await user.click(screen.getByRole('button', { name: 'Export' }));
    expect(download).toHaveBeenCalledTimes(1);
    const [fileName, csv] = download.mock.calls[0];
    expect(fileName).toMatch(/^security-misconfigurations-\d{4}-\d{2}-\d{2}\.csv$/);
    expect(csv.split('\r\n')).toEqual([
      'Misconfiguration,Severity,Affected Resources,Category,Regions',
      'VPC does not have Flow Logs enabled.,Medium,1,VPC,us-east-1',
    ]);
  });

  it('shows the loading row while findings load', () => {
    setup({ findings: [], loading: true });
    expect(screen.getByText('Loading misconfigurations…')).toBeInTheDocument();
    expect(screen.queryByText(/^Showing/)).toBeNull();
    expect(screen.getByRole('button', { name: 'Export' })).toBeDisabled();
    expect(card('Total Affected Resources')).toHaveTextContent('Loading');
  });

  it('shows the error with Retry instead of an empty state', async () => {
    const user = userEvent.setup();
    const { onRetry } = setup({ findings: [], error: 'HTTP 500' });
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Could not load the security findings (HTTP 500).');
    expect(screen.queryByText('No misconfigurations found.')).toBeNull();
    expect(card('Total Affected Resources')).toHaveTextContent(/^Total Affected Resources-$/);
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('says so when the account has no finding', () => {
    setup({ findings: [] });
    expect(screen.getByText('No misconfigurations found.')).toBeInTheDocument();
  });

  it('pages 10 rows at a time', () => {
    setup({
      findings: Array.from({ length: 12 }, (_, i) => finding({
        resourceId: `r-${i}`, controlId: `X-${i}`, description: `Check ${String(i).padStart(2, '0')}`,
      })),
    });
    expect(bodyRows()).toHaveLength(10);
    expect(screen.getByText('Showing 1 - 10 of 12')).toBeInTheDocument();
  });
});

describe('SecurityFindingsTab: the drawer', () => {
  const SSH = TITLES[0];
  const drawer = () => screen.getByRole('dialog', { name: SSH });

  async function openDrawer(over: Overrides = {}) {
    const user = userEvent.setup();
    const tab = setup(over);
    await user.click(screen.getByText(SSH));
    await screen.findByRole('dialog', { name: SSH });
    return { user, ...tab };
  }

  async function openAffected(over: Overrides = {}) {
    const tab = await openDrawer(over);
    await tab.user.click(within(drawer()).getByRole('tab', { name: 'Affected Resources' }));
    await within(drawer()).findByText('sg-0aaa1111');
    return tab;
  }

  it('opens on Details beside a vertical nav, with the close X focused', async () => {
    await openDrawer();
    const d = within(drawer());
    expect(d.getByRole('button', { name: 'Close' })).toHaveFocus();
    const nav = d.getByRole('tablist', { name: 'Misconfiguration details' });
    expect(nav).toHaveAttribute('aria-orientation', 'vertical');
    expect(within(nav).getAllByRole('tab').map(tab => [tab.textContent, tab.getAttribute('aria-selected')]))
      .toEqual([['Details', 'true'], ['Affected Resources', 'false']]);
    expect(d.getByRole('heading', { level: 2 })).toHaveTextContent(SSH);
    expect(d.getByText('Critical')).toBeInTheDocument();
    expect(d.getByText('CIS AWS Foundations 4.1')).toBeInTheDocument();
  });

  it('shows the control, framework, category and resource type, and the check\'s description and remediation', async () => {
    await openDrawer();
    const d = within(drawer());
    expect(d.getByText('4.1')).toBeInTheDocument();
    expect(d.getByRole('button', { name: 'Copy control ID' })).toBeInTheDocument();
    expect(d.getByText('CIS AWS Foundations')).toBeInTheDocument();
    expect(d.getByText('VPC')).toBeInTheDocument();
    expect(d.getByText('Security Group')).toBeInTheDocument();
    // The scan sends one sentence and no remediation: both come from the check's guide.
    expect(d.getByRole('region', { name: 'Description' }))
      .toHaveTextContent('An inbound rule accepts traffic from every IPv4 address (0.0.0.0/0)');
    const remediation = d.getByRole('region', { name: 'Remediation' });
    expect(within(remediation).getAllByRole('listitem')).toHaveLength(3);
    // Two affected resources in two regions: the command keeps its placeholders and says so.
    const code = remediation.querySelector('code')?.textContent ?? '';
    expect(code).toContain('--region <region> --group-id <security-group-id>');
    expect(code).toContain('FromPort==`22` && ToPort==`22`');
    expect(remediation).toHaveTextContent('open one of the 2 affected resources');
    expect(within(remediation).getByRole('button', { name: 'Copy code' })).toBeInTheDocument();
    const learnMore = within(remediation).getByRole('link', { name: /Learn More/ });
    expect(learnMore).toHaveAttribute('href', 'https://docs.aws.amazon.com/vpc/latest/userguide/security-group-rules.html');
    expect(learnMore).toHaveAttribute('target', '_blank');
    expect(learnMore).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('fills the command in for a misconfiguration on a single resource', async () => {
    const user = userEvent.setup();
    setup();
    await user.click(screen.getByText(TITLES[1]));
    const remediation = within(await screen.findByRole('dialog', { name: TITLES[1] })).getByRole('region', { name: 'Remediation' });
    const code = remediation.querySelector('code')?.textContent ?? '';
    expect(code).toContain('aws s3api put-bucket-acl --region eu-west-1 --bucket orders-archive --acl private');
    expect(remediation).not.toHaveTextContent('placeholders');
  });

  it('shows a live finding\'s own description and remediation', async () => {
    const user = userEvent.setup();
    setup({ findings: [LIVE_BUCKET] });
    await user.click(screen.getByText('S3 buckets should block public access'));
    const d = within(await screen.findByRole('dialog', { name: 'S3 buckets should block public access' }));
    expect(d.getByRole('region', { name: 'Description' }))
      .toHaveTextContent('This control checks whether Block Public Access is on.');
    const remediation = d.getByRole('region', { name: 'Remediation' });
    expect(remediation).toHaveTextContent('Turn on Block Public Access for the bucket.');
    expect(within(remediation).getByRole('link', { name: /Learn More/ }))
      .toHaveAttribute('href', 'https://docs.aws.amazon.com/securityhub/latest/userguide/s3-controls.html#s3-8-remediation');
    // Security Hub's own text, not a guide: no numbered steps and no command.
    expect(within(remediation).queryByRole('listitem')).toBeNull();
    expect(remediation.querySelector('code')).toBeNull();
  });

  it('opens a row on Enter', async () => {
    const user = userEvent.setup();
    setup();
    bodyRows()[3].focus();
    await user.keyboard('{Enter}');
    expect(await screen.findByRole('dialog', { name: TITLES[3] })).toBeInTheDocument();
  });

  it('lists the affected resources with search, Region, Columns 3 and Export', async () => {
    const { user } = await openAffected();
    const d = within(drawer());
    expect(within(d.getByRole('table')).getAllByRole('columnheader').map(th => th.textContent))
      .toEqual(['Affected Resource', 'Region', 'Actions']);
    expect(d.getByText('Showing 1 - 2 of 2')).toBeInTheDocument();
    expect(d.getAllByRole('button', { name: 'Copy resource ID' })).toHaveLength(2);

    await user.type(d.getByRole('searchbox', { name: 'Search' }), 'BBB');
    expect(d.getByText('Showing 1 - 1 of 1')).toBeInTheDocument();
    await user.clear(d.getByRole('searchbox', { name: 'Search' }));

    await user.click(d.getByRole('button', { name: /^Region/ }));
    const filter = within(screen.getByRole('dialog', { name: 'Region' }));
    expect(filter.getAllByRole('checkbox').map(box => box.closest('label')?.textContent))
      .toEqual(['Select all', 'ap-south-1 1', 'us-east-1 1']);
    await user.click(filter.getByRole('checkbox', { name: /us-east-1/ }));
    await user.keyboard('{Escape}');
    expect(d.getByText('Showing 1 - 1 of 1')).toBeInTheDocument();

    await user.click(d.getByRole('button', { name: /Columns/ }));
    expect(d.getByRole('button', { name: /Columns/ })).toHaveTextContent('Columns 3');
    await user.click(d.getByRole('checkbox', { name: 'Region' }));
    await user.keyboard('{Escape}');
    expect(d.getByRole('button', { name: /Columns/ })).toHaveTextContent('Columns 2');

    await user.click(d.getByRole('button', { name: 'Export' }));
    expect(download).toHaveBeenCalledTimes(1);
    const [fileName, csv] = download.mock.calls[0];
    expect(fileName).toMatch(/^security-misconfiguration-cis-aws-foundations-4\.1-affected-resources-\d{4}-\d{2}-\d{2}\.csv$/);
    expect(csv.split('\r\n')).toEqual(['Affected Resource', 'sg-0aaa1111']);
    expect(drawer()).toBeInTheDocument();
  });

  it('marks a resource fixed, and follows the findings as they change', async () => {
    const { user, onResolve, rerender } = await openAffected();
    await user.click(within(drawer()).getByRole('button', { name: 'Mark fixed: VPC on sg-0aaa1111' }));
    expect(onResolve).toHaveBeenCalledWith(SSH_A);

    rerender({ resolvingKeys: [findingKey(SSH_B)] });
    expect(within(drawer()).getByRole('button', { name: 'Mark fixed: VPC on sg-0bbb2222' })).toBeDisabled();

    // The page drops a resource as soon as it is marked fixed.
    rerender({ findings: FINDINGS.filter(f => f !== SSH_A), resolvingKeys: [] });
    expect(within(drawer()).getByText('Showing 1 - 1 of 1')).toBeInTheDocument();
    expect(titles()[0]).toBe(SSH);
    expect(within(bodyRows()[0]).getAllByRole('cell')[2]).toHaveTextContent(/^1$/);

    // Its last resource gone, the row leaves the table but its drawer stays open for Undo.
    rerender({ findings: FINDINGS.filter(f => f !== SSH_A && f !== SSH_B) });
    expect(within(drawer()).getByText('No affected resources.')).toBeInTheDocument();
    expect(titles()).not.toContain(SSH);

    rerender({ findings: FINDINGS });
    expect(within(drawer()).getByText('Showing 1 - 2 of 2')).toBeInTheDocument();
  });

  it('closes an open filter on the first Escape, the drawer on the second, and gives focus back to the row', async () => {
    const { user } = await openAffected();
    await user.click(within(drawer()).getByRole('button', { name: /^Region/ }));
    expect(screen.getByRole('dialog', { name: 'Region' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Region' })).toBeNull();
    expect(drawer()).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: SSH })).toBeNull());
    expect(bodyRows()[0]).toHaveFocus();
  });

  it('closes on the X', async () => {
    const { user } = await openDrawer();
    await user.click(within(drawer()).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog', { name: SSH })).toBeNull();
  });
});

describe('SecurityFindingsTab: the resource drawer', () => {
  const SSH = TITLES[0];
  const RDP = 'Allows inbound traffic from anywhere (0.0.0.0/0) on port(s) 3389';
  // A second check on the same security group, in the same region.
  const RDP_A = { ...SSH_A, description: RDP };
  const WITH_RDP = [...FINDINGS, RDP_A];
  const resourceDrawer = () => screen.getByRole('dialog', { name: 'sg-0aaa1111' });
  // The list's own items: an open card's remediation steps are a nested list.
  const listItems = () => Array.from(
    within(resourceDrawer()).getByRole('list', { name: 'Misconfigurations on this resource' }).children,
  ) as HTMLElement[];

  async function openResource(over: Overrides = {}) {
    const user = userEvent.setup();
    const tab = setup({ findings: WITH_RDP, ...over });
    await user.click(screen.getByText(SSH));
    const drawer = within(await screen.findByRole('dialog', { name: SSH }));
    await user.click(drawer.getByRole('tab', { name: 'Affected Resources' }));
    await user.click(await drawer.findByRole('button', { name: 'sg-0aaa1111' }));
    await screen.findByRole('dialog', { name: 'sg-0aaa1111' });
    return { user, ...tab };
  }

  it('opens over the misconfiguration drawer with the resource\'s details', async () => {
    await openResource();
    const r = within(resourceDrawer());
    expect(screen.getByRole('dialog', { name: SSH })).toBeInTheDocument();
    expect(r.getByRole('button', { name: 'Close resource' })).toHaveFocus();
    expect(r.getByRole('heading', { level: 2 })).toHaveTextContent('sg-0aaa1111');
    const details = r.getByRole('region', { name: 'Resource Details' });
    expect(within(details).getAllByRole('term').map(dt => dt.textContent))
      .toEqual(['Resource ID', 'Resource Type', 'Category', 'Region', 'Account', 'Misconfigurations']);
    expect(within(details).getAllByRole('definition').map(dd => dd.textContent))
      .toEqual(['sg-0aaa1111', 'Security Group', 'VPC', 'us-east-1', ACCOUNT, '2']);
    expect(r.getByRole('link', { name: /Go to AWS/ })).toHaveAttribute(
      'href', 'https://us-east-1.console.aws.amazon.com/ec2/home?region=us-east-1#SecurityGroup:groupId=sg-0aaa1111',
    );
  });

  it('lists every misconfiguration on the resource, the one it came from first and open, with its own command', async () => {
    const { user } = await openResource();
    const items = listItems();
    expect(items.map(item => within(item).getAllByRole('button')[0].getAttribute('aria-expanded'))).toEqual(['true', 'false']);
    expect(items[0]).toHaveTextContent(SSH);
    expect(items[1]).toHaveTextContent(RDP);
    const ssh = items[0].querySelector('code')?.textContent ?? '';
    expect(ssh).toContain('--region us-east-1 --filters Name=group-id,Values=sg-0aaa1111');
    expect(ssh).toContain('FromPort==`22` && ToPort==`22`');
    expect(ssh).toContain('aws ec2 revoke-security-group-ingress --region us-east-1 --group-id sg-0aaa1111');
    expect(items[1].querySelector('code')).toBeNull();
    await user.click(within(items[1]).getAllByRole('button')[0]);
    expect(items[1].querySelector('code')?.textContent).toContain('FromPort==`3389` && ToPort==`3389`');
  });

  it('marks a misconfiguration fixed from the resource, and follows the findings', async () => {
    const { user, onResolve, rerender } = await openResource();
    await user.click(within(resourceDrawer()).getByRole('button', { name: `Mark fixed: ${RDP} on sg-0aaa1111` }));
    expect(onResolve).toHaveBeenCalledWith(RDP_A);
    rerender({ findings: FINDINGS });
    expect(listItems()).toHaveLength(1);
    rerender({ findings: FINDINGS.filter(f => f !== SSH_A) });
    expect(within(resourceDrawer()).getByText('No open misconfigurations on this resource.')).toBeInTheDocument();
  });

  it('closes on the first Escape with focus back on the resource, and the misconfiguration drawer on the second', async () => {
    const { user } = await openResource();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'sg-0aaa1111' })).toBeNull();
    const drawer = screen.getByRole('dialog', { name: SSH });
    expect(within(drawer).getByRole('button', { name: 'sg-0aaa1111' })).toHaveFocus();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: SSH })).toBeNull());
  });

  it('opens from a click anywhere on the resource\'s row, and closes on its X', async () => {
    const user = userEvent.setup();
    setup({ findings: WITH_RDP });
    await user.click(screen.getByText(SSH));
    const drawer = within(await screen.findByRole('dialog', { name: SSH }));
    await user.click(drawer.getByRole('tab', { name: 'Affected Resources' }));
    await user.click(await drawer.findByText('ap-south-1'));
    const r = within(await screen.findByRole('dialog', { name: 'sg-0bbb2222' }));
    expect(r.getByRole('region', { name: 'Resource Details' })).toHaveTextContent('ap-south-1');
    await user.click(r.getByRole('button', { name: 'Close resource' }));
    expect(screen.queryByRole('dialog', { name: 'sg-0bbb2222' })).toBeNull();
    expect(screen.getByRole('dialog', { name: SSH })).toBeInTheDocument();
  });
});
