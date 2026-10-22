import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import DataTable from '../DataTable';
import type { ColumnDef } from '../K8sDataTable';
import type { DrawerSection } from '../DetailDrawer';

interface Row { name: string; cpu: number }

const ROWS: Row[] = [
  { name: 'pod-a', cpu: 120 },
  { name: 'pod-b', cpu: 40 },
];

const COLUMNS: ColumnDef<Row>[] = [
  { id: 'name', header: 'Name', cell: (r) => r.name, sortValue: (r) => r.name },
  { id: 'cpu', header: 'CPU', cell: (r) => `${r.cpu}m`, sortValue: (r) => r.cpu },
];

const sections = (r: Row): DrawerSection[] => [
  { title: 'Overview', data: [{ label: 'Name', value: r.name }, { label: 'CPU', value: `${r.cpu}m` }] },
];

function renderTable() {
  return render(
    <DataTable<Row>
      rows={ROWS}
      columns={COLUMNS}
      rowKey={(r) => r.name}
      rowLabel={(r) => r.name}
      caption="Pods"
      rowTitle={(r) => r.name}
      rowToSections={sections}
    />,
  );
}

describe('DataTable (FEAT-001)', () => {
  it('opens a DetailDrawer from a row activation', async () => {
    const user = userEvent.setup();
    renderTable();
    expect(screen.queryByRole('dialog')).toBeNull();

    const row = screen.getByRole('button', { name: 'pod-a' });
    await user.click(row);
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAccessibleName('pod-a');
    expect(within(dialog).getByText('Overview')).toBeInTheDocument();
  });

  it('right-aligns numeric columns and keeps a sticky header by default', () => {
    renderTable();
    const cpuHeader = screen.getByRole('columnheader', { name: 'CPU' });
    expect(cpuHeader.className).toContain('text-right');
    const thead = screen.getByRole('table', { name: 'Pods' }).querySelector('thead')!;
    expect(thead.className).toContain('sticky');
  });

  it('opens the drawer on keyboard activation', async () => {
    const user = userEvent.setup();
    renderTable();
    const row = screen.getByRole('button', { name: 'pod-b' });
    row.focus();
    await user.keyboard('{Enter}');
    expect(screen.getByRole('dialog')).toHaveAccessibleName('pod-b');
  });
});
