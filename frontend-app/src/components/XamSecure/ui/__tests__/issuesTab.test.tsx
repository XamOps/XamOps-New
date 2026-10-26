import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import IssuesTab from '../../IssuesTab';
import SeverityGroup, { openHeightFor } from '../SeverityGroup';
import FindingsTable from '../FindingsTable';
import FindingsCountPill from '../FindingsCountPill';
import SeverityQuickFilter from '../SeverityQuickFilter';
import FindingRow from '../FindingRow';
import type { IssueSummary, IssueDetail } from '../../../../types/xamsecure';

/** matchMedia mock (copied from ui.test.tsx) — pass `reduce` for prefers-reduced-motion. */
function mockMatchMedia(reduce: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: query.includes('reduce') ? reduce : false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
}

const mk = (over: Partial<IssueSummary> & { check: string; title: string; severity: IssueSummary['severity'] }): IssueSummary => ({
  resourceCount: 2,
  frameworks: ['CIS'],
  firstSeen: '2026-01-01T00:00:00Z',
  lastEvaluation: '2026-02-01T00:00:00Z',
  ...over,
});

const ISSUES: IssueSummary[] = [
  mk({ check: 'c1', title: 'Critical one', severity: 'CRITICAL' }),
  mk({ check: 'c2', title: 'Critical two', severity: 'CRITICAL' }),
  mk({ check: 'h1', title: 'High one', severity: 'HIGH' }),
  mk({ check: 'm1', title: 'Medium one', severity: 'MEDIUM' }),
  mk({ check: 'l1', title: 'Low one', severity: 'LOW' }),
];

const draw = (ui: React.ReactElement) => render(<MemoryRouter>{ui}</MemoryRouter>);

const DETAIL: IssueDetail = {
  title: 'Critical one',
  severity: 'CRITICAL',
  description: 'desc',
  remediation: 'fix',
  frameworks: ['CIS'],
  firstSeen: '2026-01-01T00:00:00Z',
  lastEvaluation: '2026-02-01T00:00:00Z',
  resourceCount: 3,
  resources: [],
};
const noDetail = (async () => DETAIL) as unknown as (check: string) => Promise<IssueDetail>;

afterEach(() => vi.restoreAllMocks());

describe('IssuesTab — always-mounted / collapsed reachability', () => {
  it('renders finding titles with no interaction while groups default collapsed', () => {
    draw(<IssuesTab data={ISSUES} isLoading={false} loadDetail={noDetail} />);
    // A finding in the collapsed Critical group is still in the DOM (protects xamsecureMockData.test.tsx).
    expect(screen.getByText('Critical one')).toBeInTheDocument();
    expect(screen.getByText('Low one')).toBeInTheDocument();
    // Every group bar defaults aria-expanded=false.
    const bars = screen.getAllByRole('button', { expanded: false });
    expect(bars.length).toBeGreaterThanOrEqual(4);
  });

  it('renders the "By severity" list header exactly once and the five column labels inside tables', () => {
    draw(<IssuesTab data={ISSUES} isLoading={false} loadDetail={noDetail} />);
    expect(screen.getAllByText('By severity')).toHaveLength(1);
    expect(screen.getAllByText('Finding').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Resources').length).toBeGreaterThan(0);
    expect(screen.getAllByText('First seen').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Last evaluation').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Framework').length).toBeGreaterThan(0);
  });
});

/** Find a quick-filter chip by its visible label word + count (its composed accessible name
 *  also includes the badge's "Severity X" prefix, so match loosely). */
const chip = (label: string, count: number) =>
  screen.getByRole('button', { name: new RegExp(`${label}${count}`) });

/** The finding row for a title. Collapsed rows are visibility:hidden (excluded from getByRole),
 *  so we expand the group first, then resolve the row <button> via its title text. */
const expandGroup = (label: string, count: number) => fireEvent.click(chip(label, count));

describe('IssuesTab — severity quick filter', () => {
  it('narrows the rendered groups and resets with All', () => {
    draw(<IssuesTab data={ISSUES} isLoading={false} loadDetail={noDetail} />);
    fireEvent.click(chip('High', 1));
    expect(screen.getByText('High one')).toBeInTheDocument();
    expect(screen.queryByText('Critical one')).not.toBeInTheDocument();
    // All resets.
    fireEvent.click(chip('All', 5));
    expect(screen.getByText('Critical one')).toBeInTheDocument();
  });
});

describe('IssuesTab — drawer open path', () => {
  it('clicking a finding calls loadDetail and opens the IssueDrawer', async () => {
    const loadDetail = vi.fn(noDetail);
    draw(<IssuesTab data={ISSUES} isLoading={false} loadDetail={loadDetail} />);
    // Filtering to Critical auto-expands its group, making the row focusable/visible.
    expandGroup('Critical', 2);
    fireEvent.click(screen.getByText('Critical one').closest('button')!);
    expect(loadDetail).toHaveBeenCalledWith('c1');
    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument());
  });

  it('a rejected loadDetail still opens the drawer empty state', async () => {
    const loadDetail = vi.fn(async () => { throw new Error('boom'); }) as unknown as (c: string) => Promise<IssueDetail>;
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    draw(<IssuesTab data={ISSUES} isLoading={false} loadDetail={loadDetail} />);
    expandGroup('High', 1);
    fireEvent.click(screen.getByText('High one').closest('button')!);
    await waitFor(() => expect(screen.getByText('Issue details unavailable')).toBeInTheDocument());
    errSpy.mockRestore();
  });
});

describe('IssuesTab — states', () => {
  it('shows the loading skeleton under .xs-issues', () => {
    const { container } = draw(<IssuesTab data={null} isLoading loadDetail={noDetail} />);
    const root = container.querySelector('.xs-issues')!;
    expect(root).toBeInTheDocument();
    expect(root.querySelectorAll('.xs-skeleton').length).toBeGreaterThan(0);
  });

  it('shows the empty message when there are no issues', () => {
    draw(<IssuesTab data={[]} isLoading={false} loadDetail={noDetail} />);
    expect(screen.getByText('No open issues for the selected account.')).toBeInTheDocument();
  });

  it('shows the filtered-empty copy preserving the original wording', () => {
    // Only CRITICAL issues present → filtering to LOW yields the filtered-empty copy.
    draw(<IssuesTab data={[ISSUES[0]]} isLoading={false} loadDetail={noDetail} />);
    fireEvent.click(screen.getByRole('button', { name: /Low0/ }));
    expect(screen.getByText(/No low-severity issues\. Clear the filter to see all issues\./)).toBeInTheDocument();
  });
});

describe('SeverityGroup', () => {
  it('renders collapsed: height 0, chevron rotate(-90deg), aria-expanded false; pill shows count', () => {
    const { container } = render(
      <div className="xs-issues">
        <SeverityGroup severity="HIGH" label="High" issues={[ISSUES[2]]} open={false} onToggle={() => {}} onOpenIssue={() => {}} />
      </div>,
    );
    const bar = screen.getByRole('button', { expanded: false });
    expect(bar).toHaveAttribute('aria-expanded', 'false');
    const collapse = container.querySelector('.xs-collapse') as HTMLElement;
    expect(collapse.style.height).toBe('0px');
    expect(collapse.style.visibility).toBe('hidden');
    const chevron = container.querySelector('svg')!;
    expect((chevron as unknown as HTMLElement).style.transform).toBe('rotate(-90deg)');
    expect(screen.getByTestId('findings-count-pill')).toHaveTextContent('1 Findings');
  });

  it('expands when open=true: computed open height + rotate(0deg)', () => {
    const { container } = render(
      <div className="xs-issues">
        <SeverityGroup severity="HIGH" label="High" issues={[ISSUES[2]]} open onToggle={() => {}} onOpenIssue={() => {}} />
      </div>,
    );
    const collapse = container.querySelector('.xs-collapse') as HTMLElement;
    expect(collapse.style.height).toBe(`${openHeightFor(1)}px`); // 41 + 56 = 97
    expect(collapse.style.visibility).toBe('visible');
  });

  it('toggles via the bar button (native click)', () => {
    const onToggle = vi.fn();
    render(
      <div className="xs-issues">
        <SeverityGroup severity="HIGH" label="High" issues={[ISSUES[2]]} open={false} onToggle={onToggle} onOpenIssue={() => {}} />
      </div>,
    );
    fireEvent.click(screen.getByRole('button'));
    expect(onToggle).toHaveBeenCalledTimes(1);
  });
});

describe('openHeightFor — cap math min(41 + rows*56, 499)', () => {
  it.each([
    [0, 41],
    [1, 97],
    [7, 433],
    [8, 489],
    [9, 499],
    [93, 499],
  ])('rows=%i → %ipx', (rows, expected) => {
    expect(openHeightFor(rows)).toBe(expected);
  });
});

describe('FindingsTable', () => {
  it('renders all rows (reachability) even beyond 8; header labels present; body has no table role', () => {
    const many = Array.from({ length: 12 }, (_, i) =>
      mk({ check: `x${i}`, title: `Finding ${i}`, severity: 'MEDIUM' }));
    const { container } = render(
      <div className="xs-issues"><FindingsTable issues={many} onOpenIssue={() => {}} /></div>,
    );
    // 12 finding rows all mounted.
    expect(screen.getAllByRole('button')).toHaveLength(12);
    // header labels
    expect(screen.getByText('Finding')).toBeInTheDocument();
    expect(screen.getByText('Last evaluation')).toBeInTheDocument();
    // invisible scroll body present
    expect(container.querySelector('.xs-invisible-scroll')).toBeInTheDocument();
    // body has no table/row role (only the header is role=row)
    expect(screen.getAllByRole('row')).toHaveLength(1);
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});

describe('FindingRow', () => {
  it('renders badge, title, resources, dates and frameworks; click & Enter call onOpen', () => {
    const onOpen = vi.fn();
    render(
      <div className="xs-issues">
        <FindingRow issue={mk({ check: 'r1', title: 'Row title', severity: 'CRITICAL', resourceCount: 4, frameworks: ['CIS', 'SOC2', 'PCI-DSS', 'HIPAA', 'NIST'] })} onOpen={onOpen} />
      </div>,
    );
    const btn = screen.getByRole('button', { name: 'Row title, severity CRITICAL' });
    expect(btn).toBeInTheDocument();
    expect(screen.getByTestId('severity-letter-badge')).toHaveTextContent('C');
    expect(screen.getByText('Row title')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.getByText('+1')).toBeInTheDocument(); // 5 frameworks → 4 shown + "+1"
    fireEvent.click(btn);
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('renders an em-dash for a null date', () => {
    render(
      <div className="xs-issues">
        <FindingRow issue={mk({ check: 'r2', title: 'No dates', severity: 'LOW', firstSeen: null, lastEvaluation: null })} onOpen={() => {}} />
      </div>,
    );
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2);
  });
});

describe('FindingsCountPill — AA text color', () => {
  it('resolves to --xs-text-secondary (#75808e), not #94a3b8', () => {
    render(<FindingsCountPill count={3} />);
    const pill = screen.getByTestId('findings-count-pill');
    expect(pill).toHaveTextContent('3 Findings');
    expect(pill.style.color).toBe('var(--xs-text-secondary)');
    expect(pill.style.color).not.toBe('var(--xs-text-tertiary)');
  });
});

describe('SeverityQuickFilter', () => {
  it('marks the active chip aria-pressed and fires onChange', () => {
    const onChange = vi.fn();
    render(
      <SeverityQuickFilter
        counts={{ CRITICAL: 2, HIGH: 1, MEDIUM: 1, LOW: 1 }}
        total={5}
        active="HIGH"
        onChange={onChange}
      />,
    );
    const high = screen.getByRole('button', { name: /High1/ });
    expect(high).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: /All5/ }));
    expect(onChange).toHaveBeenCalledWith(null);
    fireEvent.click(screen.getByRole('button', { name: /Critical2/ }));
    expect(onChange).toHaveBeenCalledWith('CRITICAL');
  });
});

describe('IssuesTab — reduced motion', () => {
  it('renders the final static state (no crash) when prefers-reduced-motion is set', () => {
    mockMatchMedia(true);
    const { container } = draw(<IssuesTab data={ISSUES} isLoading={false} loadDetail={noDetail} />);
    // Collapsed groups render their collapse container at height 0 regardless of motion pref.
    const collapses = container.querySelectorAll('.xs-collapse');
    expect(collapses.length).toBe(4);
    collapses.forEach((c) => expect((c as HTMLElement).style.height).toBe('0px'));
  });
});
