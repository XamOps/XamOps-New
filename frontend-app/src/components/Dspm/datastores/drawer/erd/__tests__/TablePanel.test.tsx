import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCallback, useMemo, useRef, useState } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DataFlowColumnPage } from '../../../../../../types/dspmDatastores';
import type { DataFlowRelations } from '../../../../../../types/dspmDrawers';

vi.mock('../../../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));

import apiClient from '../../../../../../utils/apiClient';
import { datastoreDrawerApi } from '../../../../../../hooks/useDspmDatastoreDrawer';
import { DrawerShell } from '../../DrawerShell';
import TablePanel from '../TablePanel';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

// ─── synthetic data (shapes only; no captured values) ────────────────────────────────────────────

const SOURCE_ID = '0123456789abcdef01234567';
const TABLE_ID = '0e1d0000-0000-4000-8000-000000000001';

/** A five-column table without keys: one primary key, no foreign key. */
const REPORT_COLUMNS: DataFlowColumnPage = {
  rows: [
    ['snapshot_key', 'varchar', true], ['payload', 'text', false], ['refreshed_at', 'timestamp', false],
    ['region_code', 'varchar', false], ['vendor', 'varchar', false],
  ].map(([name, nativeType, primaryKey], i) => ({
    id: `0e1d0000-0000-4000-8000-0000000001${String(i).padStart(2, '0')}`,
    name: name as string,
    nativeType: nativeType as string,
    sensitiveCount: 0,
    dataElements: [],
    primaryKey: primaryKey as boolean,
    foreignKey: false,
  })),
  page: 1, pageSize: 10, total: 5, totalPages: 1,
};

/** A table with two keys out and two keys in, the other tables in the same schema. */
const ORDER_RELATIONS: DataFlowRelations = {
  outgoing: [
    { schemaName: 'sales', tableName: 'customers', columns: [{ sourceColumn: 'customer_id', targetColumn: 'id' }] },
    { schemaName: 'sales', tableName: 'stores', columns: [{ sourceColumn: 'store_id', targetColumn: 'id' }] },
  ],
  incoming: [
    { schemaName: 'sales', tableName: 'refunds', columns: [{ sourceColumn: 'order_id', targetColumn: 'id' }] },
    { schemaName: 'sales', tableName: 'order_items', columns: [{ sourceColumn: 'order_id', targetColumn: 'id' }] },
  ],
};

const COLUMNS_PATH = `/api/dspm/data-flow/tables/${TABLE_ID}/columns`;
const RELATIONS_PATH = `/api/dspm/data-flow/tables/${TABLE_ID}/relations`;

let relations: () => unknown;

beforeEach(() => {
  relations = () => ORDER_RELATIONS;
  get.mockImplementation(async (raw: string) => {
    const url = new URL(raw, 'http://localhost');
    if (url.pathname === COLUMNS_PATH) return REPORT_COLUMNS;
    if (url.pathname === RELATIONS_PATH) return relations();
    throw new Error(`HTTP 404: unexpected ${raw}`);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

const requestsTo = (path: string) =>
  get.mock.calls.map(([raw]) => new URL(String(raw), 'http://localhost')).filter(url => url.pathname === path);

// ─── harness: the panel over a root drawer, opened from a "card" ─────────────────────────────────

function Harness({ elevated, onRootClose }: { elevated: boolean; onRootClose: () => void }) {
  const rootClose = useRef<HTMLButtonElement>(null);
  const api = useMemo(() => datastoreDrawerApi(SOURCE_ID), []);
  const [opener, setOpener] = useState<HTMLButtonElement | null>(null);
  const [open, setOpen] = useState(true);
  const close = useCallback(() => setOpen(false), []);
  return (
    <DrawerShell label="warehouse" onClose={onRootClose} initialFocus={rootClose}>
      <button ref={rootClose} type="button">Close datastore</button>
      <button ref={setOpener} type="button">card</button>
      {open && opener && (
        <TablePanel
          api={api}
          table={{ id: TABLE_ID, name: 'orders' }}
          returnFocus={opener}
          onClose={close}
          elevated={elevated}
        />
      )}
    </DrawerShell>
  );
}

async function renderPanel(elevated = false) {
  const onRootClose = vi.fn();
  render(<Harness elevated={elevated} onRootClose={onRootClose} />);
  const panel = await screen.findByRole('dialog', { name: 'orders' });
  return { panel, onRootClose };
}

const headers = (table: HTMLElement) => within(table).getAllByRole('columnheader').map(cell => cell.textContent);
const rowsOf = (table: HTMLElement) =>
  within(table).getAllByRole('row').slice(1).map(row => [...(row as HTMLTableRowElement).cells].map(cell => cell.textContent));

// ─── tests ───────────────────────────────────────────────────────────────────────────────────────

describe('TablePanel', () => {
  it('shows the table icon, the bold name, "Table" and the close X, which takes focus', async () => {
    const { panel } = await renderPanel();
    expect(within(panel).getByRole('heading', { name: 'orders', level: 2 })).toHaveClass('font-bold');
    expect(within(panel).getByText('Table')).toBeInTheDocument();
    // The X takes focus in an effect, which can run after the dialog is found.
    await waitFor(() => expect(within(panel).getByRole('button', { name: 'Close' })).toHaveFocus());
  });

  it('opens on Columns with build 1\'s columns table, all four columns and no chooser', async () => {
    const { panel } = await renderPanel();
    const tabs = within(panel).getByRole('tablist', { name: 'Table details' });
    expect(within(tabs).getAllByRole('tab').map(tab => tab.textContent)).toEqual(['Columns', 'Relationships']);
    expect(within(tabs).getByRole('tab', { name: 'Columns' })).toHaveAttribute('aria-selected', 'true');

    const table = await within(panel).findByRole('table');
    expect(headers(table)).toEqual(['Column', 'Type', 'Sensitives', 'Data Elements']);
    expect(rowsOf(table)[0]).toEqual(['snapshot_key', 'varchar', '0', '-']);
    expect(within(panel).getByText('Showing 1 - 5 of 5')).toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: /^Column/ })).toBeNull();
    expect(requestsTo(COLUMNS_PATH)).toHaveLength(1);
    expect(requestsTo(COLUMNS_PATH)[0].searchParams.get('pageSize')).toBe('10');
    expect(requestsTo(RELATIONS_PATH)).toHaveLength(0);
  });

  it('loads Relationships once, with the outgoing and then the incoming keys', async () => {
    const user = userEvent.setup();
    const { panel } = await renderPanel();
    await within(panel).findByRole('table');

    await user.click(within(panel).getByRole('tab', { name: 'Relationships' }));
    const outgoing = await within(panel).findByRole('region', { name: 'Outgoing' });
    const incoming = within(panel).getByRole('region', { name: 'Incoming' });
    expect(outgoing.compareDocumentPosition(incoming) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(headers(within(outgoing).getByRole('table'))).toEqual(['Column', 'References']);
    expect(rowsOf(within(outgoing).getByRole('table'))).toEqual([
      ['customer_id', 'sales.customers · id'],
      ['store_id', 'sales.stores · id'],
    ]);
    expect(headers(within(incoming).getByRole('table'))).toEqual(['Column', 'Referenced by']);
    expect(rowsOf(within(incoming).getByRole('table'))).toEqual([
      ['id', 'sales.refunds · order_id'],
      ['id', 'sales.order_items · order_id'],
    ]);

    await user.click(within(panel).getByRole('tab', { name: 'Columns' }));
    expect(within(panel).getByText('Showing 1 - 5 of 5')).toBeInTheDocument();
    await user.click(within(panel).getByRole('tab', { name: 'Relationships' }));
    expect(within(panel).getByRole('region', { name: 'Outgoing' })).toBeInTheDocument();
    expect(requestsTo(RELATIONS_PATH)).toHaveLength(1);
    expect(requestsTo(COLUMNS_PATH)).toHaveLength(1);
  });

  it('reads "None" for an empty section', async () => {
    const user = userEvent.setup();
    relations = () => ({ outgoing: ORDER_RELATIONS.outgoing, incoming: [] });
    const { panel } = await renderPanel();
    await user.click(within(panel).getByRole('tab', { name: 'Relationships' }));
    const incoming = await within(panel).findByRole('region', { name: 'Incoming' });
    expect(incoming).toHaveTextContent('None');
    expect(within(incoming).queryByRole('table')).toBeNull();
    expect(within(panel).getByRole('region', { name: 'Outgoing' })).toBeInTheDocument();
  });

  it('shows "No relationships found." for a table without keys', async () => {
    const user = userEvent.setup();
    relations = () => ({ outgoing: [], incoming: [] });
    const { panel } = await renderPanel();
    await user.click(within(panel).getByRole('tab', { name: 'Relationships' }));
    expect(await within(panel).findByText('No relationships found.')).toBeInTheDocument();
    expect(within(panel).queryByRole('region', { name: 'Outgoing' })).toBeNull();
  });

  it('shows the not-found copy when the relations answer 404', async () => {
    const user = userEvent.setup();
    relations = () => {
      throw new Error(`HTTP 404: ${JSON.stringify({ code: 'ASSET_NOT_FOUND', message: 'gone' })}`);
    };
    const { panel } = await renderPanel();
    await user.click(within(panel).getByRole('tab', { name: 'Relationships' }));
    expect(await within(panel).findByRole('alert')).toHaveTextContent('This datastore could not be found. It may have been removed.');
  });

  it('closes on Escape with focus back on the card, before the drawer', async () => {
    const user = userEvent.setup();
    const { panel, onRootClose } = await renderPanel();
    await waitFor(() => expect(within(panel).getByRole('button', { name: 'Close' })).toHaveFocus());
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'orders' })).toBeNull();
    expect(screen.getByRole('button', { name: 'card' })).toHaveFocus();
    expect(onRootClose).not.toHaveBeenCalled();
    await user.keyboard('{Escape}');
    expect(onRootClose).toHaveBeenCalledTimes(1);
  });

  it('closes on its X with focus back on the card', async () => {
    const user = userEvent.setup();
    const { panel } = await renderPanel();
    await user.click(within(panel).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog', { name: 'orders' })).toBeNull();
    await waitFor(() => expect(screen.getByRole('button', { name: 'card' })).toHaveFocus());
  });

  it('stacks above the full-screen layer when elevated (R1), and above the drawer only otherwise', async () => {
    const elevated = await renderPanel(true);
    const overlay = elevated.panel.previousElementSibling as HTMLElement;
    expect(overlay).toHaveClass('z-[75]');
    expect(elevated.panel).toHaveClass('z-[80]');
    expect(elevated.panel).not.toHaveClass('z-[60]');
    expect(elevated.panel).toHaveClass('lg:w-[40vw]', 'lg:min-w-[28rem]');
  });

  it('uses the drawer\'s layer when not elevated (R1)', async () => {
    const { panel } = await renderPanel(false);
    const overlay = panel.previousElementSibling as HTMLElement;
    expect(overlay).toHaveClass('z-[55]');
    expect(overlay).not.toHaveClass('z-[75]');
    expect(panel).toHaveClass('z-[60]');
    expect(panel).not.toHaveClass('z-[80]');
  });
});
