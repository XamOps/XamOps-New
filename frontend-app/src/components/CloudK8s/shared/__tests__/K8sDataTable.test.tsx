import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { downloadCsv } = vi.hoisted(() => ({ downloadCsv: vi.fn() }));
vi.mock('../../../../utils/csvExport', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../utils/csvExport')>();
  return { ...actual, downloadCsv };
});

import { K8sDataTable, compareSortValues, type ColumnDef, type FilterDef } from '../K8sDataTable';

interface Row {
  name: string;
  ns: string;
  cpu: number | null;
  type: 'Warning' | 'Normal';
}

const ROWS: Row[] = Array.from({ length: 60 }, (_, i) => ({
  name: `pod-${String(i).padStart(2, '0')}`,
  ns: i % 2 === 0 ? 'prod' : 'dev',
  cpu: i % 10 === 0 ? null : i * 10,
  type: i % 3 === 0 ? 'Warning' : 'Normal',
}));

const COLUMNS: ColumnDef<Row>[] = [
  { id: 'name', header: 'Name', cell: (r) => r.name, sortValue: (r) => r.name, priority: 'essential' },
  { id: 'ns', header: 'Namespace', cell: (r) => r.ns, sortValue: (r) => r.ns },
  { id: 'cpu', header: 'CPU', cell: (r) => (r.cpu === null ? '—' : `${r.cpu}m`), sortValue: (r) => r.cpu, align: 'right' },
  { id: 'type', header: 'Type', cell: (r) => r.type, csv: (r) => (r.type === 'Warning' ? '=WARN' : r.type), priority: 'low', hideBelowPx: 1440 },
  { id: 'action', header: 'Action', cell: () => <button type="button">Open</button>, priority: 'essential' },
];

const FILTERS: FilterDef<Row>[] = [
  { id: 'type', label: 'Type', options: [{ value: 'Warning', label: 'Warning' }, { value: 'Normal', label: 'Normal' }], match: (r, v) => r.type === v },
];

function renderTable(extra: Partial<Parameters<typeof K8sDataTable<Row>>[0]> = {}) {
  return render(
    <K8sDataTable<Row>
      rows={ROWS}
      columns={COLUMNS}
      rowKey={(r) => r.name}
      caption="Pods"
      search={(r) => `${r.name} ${r.ns}`}
      filters={FILTERS}
      exportName="pods-test"
      {...extra}
    />,
  );
}

const bodyRows = () => within(screen.getByRole('table', { name: 'Pods' })).getAllByRole('row').slice(1);
const firstCells = () => bodyRows().map((r) => within(r).getAllByRole('cell')[0].textContent);

beforeEach(() => {
  downloadCsv.mockReset();
  window.innerWidth = 1280;
});

afterEach(() => {
  window.innerWidth = 1024;
});

describe('K8sDataTable (§11.1, D14)', () => {
  it('has a caption and labelled column headers', () => {
    renderTable();
    const table = screen.getByRole('table', { name: 'Pods' });
    expect(within(table).getByRole('columnheader', { name: 'Name' })).toHaveAttribute('aria-sort', 'none');
    // An unsortable column has no aria-sort.
    expect(within(table).getByRole('columnheader', { name: 'Action' })).not.toHaveAttribute('aria-sort');
  });

  it('sorts by a header button, with aria-sort, and keeps nulls last in both directions', async () => {
    const user = userEvent.setup();
    renderTable({ pageSize: 100 });
    const header = screen.getByRole('columnheader', { name: 'CPU' });
    await user.click(within(header).getByRole('button', { name: 'CPU' }));
    expect(header).toHaveAttribute('aria-sort', 'ascending');
    let cpus = bodyRows().map((r) => within(r).getAllByRole('cell')[2].textContent);
    expect(cpus[0]).toBe('10m');
    expect(cpus.slice(-6)).toEqual(['—', '—', '—', '—', '—', '—']);

    await user.click(within(header).getByRole('button', { name: 'CPU' }));
    expect(header).toHaveAttribute('aria-sort', 'descending');
    cpus = bodyRows().map((r) => within(r).getAllByRole('cell')[2].textContent);
    expect(cpus[0]).toBe('590m');
    expect(cpus.slice(-6)).toEqual(['—', '—', '—', '—', '—', '—']);
  });

  it('pages 25 at a time and offers 50 and 100', async () => {
    const user = userEvent.setup();
    renderTable({ initialSort: { columnId: 'name', direction: 'asc' } });
    expect(bodyRows()).toHaveLength(25);
    expect(screen.getByText('1–25 of 60')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(firstCells()[0]).toBe('pod-25');
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(bodyRows()).toHaveLength(10);
    // The last page's Next is aria-disabled and does nothing.
    const next = screen.getByRole('button', { name: 'Next page' });
    expect(next).toHaveAttribute('aria-disabled', 'true');
    await user.click(next);
    expect(screen.getByText('Page 3 of 3')).toBeInTheDocument();

    await user.selectOptions(screen.getByRole('combobox', { name: 'Rows per page' }), '50');
    expect(bodyRows()).toHaveLength(50);
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Rows per page' }), '100');
    expect(bodyRows()).toHaveLength(60);
  });

  it('searches and filters, and goes back to page 1 when either changes', async () => {
    const user = userEvent.setup();
    renderTable({ initialSort: { columnId: 'name', direction: 'asc' } });
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(screen.getByText('Page 2 of 3')).toBeInTheDocument();

    await user.type(screen.getByRole('searchbox', { name: 'Search pods' }), 'prod');
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();
    expect(screen.getByText('1–25 of 30 (filtered from 60)')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Next page' }));
    const chips = screen.getByRole('group', { name: 'Type' });
    await user.click(within(chips).getByRole('button', { name: 'Warning' }));
    expect(within(chips).getByRole('button', { name: 'Warning' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('Page 1 of 1')).toBeInTheDocument();
    // prod (even) and Warning (multiple of 3): 0, 6, 12, … 54.
    expect(screen.getByText('1–10 of 10 (filtered from 60)')).toBeInTheDocument();

    await user.type(screen.getByRole('searchbox', { name: 'Search pods' }), ' nothing-matches');
    expect(screen.getByText('No rows match the search or filters.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear search and filters' }));
    expect(screen.getByText('1–25 of 60')).toBeInTheDocument();
  });

  it('exports the filtered, sorted rows as CSV, every page, with the formula guard', async () => {
    const user = userEvent.setup();
    renderTable();
    await user.click(within(screen.getByRole('group', { name: 'Type' })).getByRole('button', { name: 'Warning' }));
    await user.click(within(screen.getByRole('columnheader', { name: 'Name' })).getByRole('button'));
    await user.click(within(screen.getByRole('columnheader', { name: 'Name' })).getByRole('button'));
    await user.click(screen.getByRole('button', { name: 'Export CSV' }));

    expect(downloadCsv).toHaveBeenCalledTimes(1);
    const [fileName, csv] = downloadCsv.mock.calls[0] as [string, string];
    expect(fileName).toBe('pods-test');
    const lines = csv.split('\r\n');
    // Columns with a csv or sortValue only; Action has neither.
    expect(lines[0]).toBe('Name,Namespace,CPU,Type');
    expect(lines).toHaveLength(21); // 20 Warning rows + header
    expect(lines[1]).toBe("pod-57,dev,570,'=WARN");
    expect(lines[lines.length - 1]).toBe("pod-00,prod,,'=WARN");
  });

  it('expands a row with aria-expanded pointing at its detail', async () => {
    const user = userEvent.setup();
    renderTable({ expandRow: (r) => (r.cpu === null ? null : <p>Detail of {r.name}</p>), rowLabel: (r) => r.name });
    const toggle = screen.getByRole('button', { name: 'Show details for pod-01' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle);
    const open = screen.getByRole('button', { name: 'Hide details for pod-01' });
    expect(open).toHaveAttribute('aria-expanded', 'true');
    const detail = document.getElementById(open.getAttribute('aria-controls')!);
    expect(detail).toHaveTextContent('Detail of pod-01');
    // A row whose detail is null has no toggle.
    expect(screen.queryByRole('button', { name: 'Show details for pod-00' })).toBeNull();
  });

  it('hides only low-priority columns below their width, never essential ones', () => {
    window.innerWidth = 1280;
    const { unmount } = renderTable();
    expect(screen.queryByRole('columnheader', { name: 'Type' })).toBeNull();
    expect(screen.getByRole('columnheader', { name: 'Action' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Open' })).toHaveLength(25);
    unmount();

    window.innerWidth = 1600;
    renderTable();
    expect(screen.getByRole('columnheader', { name: 'Type' })).toBeInTheDocument();
  });

  it('allows hideBelowPx only on low columns (type-checked)', () => {
    // @ts-expect-error hideBelowPx is only allowed when priority is 'low'
    const normal: ColumnDef<Row> = { id: 'x', header: 'X', cell: () => null, priority: 'normal', hideBelowPx: 1440 };
    // @ts-expect-error hideBelowPx is only allowed when priority is 'low'
    const essential: ColumnDef<Row> = { id: 'y', header: 'Y', cell: () => null, priority: 'essential', hideBelowPx: 1440 };
    const low: ColumnDef<Row> = { id: 'z', header: 'Z', cell: () => null, priority: 'low', hideBelowPx: 1440 };
    expect([normal.id, essential.id, low.id]).toEqual(['x', 'y', 'z']);
  });

  it('shows loading, error with Retry, and empty states', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = render(
      <K8sDataTable<Row> rows={[]} columns={COLUMNS} rowKey={(r) => r.name} caption="Pods" loading />,
    );
    expect(screen.getByText('Loading pods…')).toBeInTheDocument();

    rerender(<K8sDataTable<Row> rows={[]} columns={COLUMNS} rowKey={(r) => r.name} caption="Pods" error="HTTP 500" onRetry={onRetry} />);
    expect(screen.getByRole('alert')).toHaveTextContent("Couldn't load pods");
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);

    rerender(
      <K8sDataTable<Row> rows={[]} columns={COLUMNS} rowKey={(r) => r.name} caption="Pods" emptyState={<p>No pods reported.</p>} source="Agent · k8s-resources" />,
    );
    expect(screen.getByText('No pods reported.')).toBeInTheDocument();
    expect(screen.getByText('Agent · k8s-resources')).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('orders numbers before strings and nulls last', () => {
    expect([3, null, 'b', 1, 'a10', 'a9'].sort(compareSortValues)).toEqual([1, 3, 'a9', 'a10', 'b', null]);
  });

  // ─── FEAT-001 additive props ────────────────────────────────────────────────

  it('leaves existing expandRow callers unaffected when the new props are unset', async () => {
    const user = userEvent.setup();
    renderTable({ expandRow: (r) => (r.cpu === null ? null : <p>Detail of {r.name}</p>), rowLabel: (r) => r.name });
    // Rows are not clickable buttons, and no trailing chevron / "Open" column exists.
    expect(screen.queryByRole('columnheader', { name: 'Open' })).toBeNull();
    const toggle = screen.getByRole('button', { name: 'Show details for pod-01' });
    await user.click(toggle);
    expect(screen.getByRole('button', { name: 'Hide details for pod-01' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('onRowActivate makes each row a clickable button, triggered by click and Enter/Space', async () => {
    const user = userEvent.setup();
    const onRowActivate = vi.fn();
    renderTable({ onRowActivate, rowLabel: (r) => r.name, initialSort: { columnId: 'name', direction: 'asc' } });
    const row = screen.getByRole('button', { name: 'pod-00' });
    await user.click(row);
    expect(onRowActivate).toHaveBeenCalledWith(expect.objectContaining({ name: 'pod-00' }));
    row.focus();
    await user.keyboard('{Enter}');
    await user.keyboard(' ');
    expect(onRowActivate).toHaveBeenCalledTimes(3);
  });

  it('stickyHeader bounds the body and sticks the thead', () => {
    const { container } = renderTable({ stickyHeader: true });
    expect(container.querySelector('.max-h-\\[70vh\\]')).toBeTruthy();
    expect(screen.getByRole('table', { name: 'Pods' }).querySelector('thead')!.className).toContain('sticky');
  });

  it('numericAlignRight right-aligns number-sorted columns but not string ones', () => {
    renderTable({ numericAlignRight: true });
    // CPU sorts by number -> right; Name sorts by string -> left.
    expect(screen.getByRole('columnheader', { name: 'CPU' }).className).toContain('text-right');
    expect(screen.getByRole('columnheader', { name: 'Name' }).className).toContain('text-left');
  });

  it('applies a colgroup when a column sets width', () => {
    const cols: ColumnDef<Row>[] = COLUMNS.map((c) => (c.id === 'name' ? { ...c, width: '200px' } : c));
    const { container } = render(
      <K8sDataTable<Row> rows={ROWS} columns={cols} rowKey={(r) => r.name} caption="Pods" />,
    );
    const col = container.querySelector('colgroup col') as HTMLTableColElement | null;
    expect(col).toBeTruthy();
    expect(col!.style.width).toBe('200px');
    expect(container.querySelector('table')!.className).toContain('table-fixed');
  });
});
