import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { useMemo, useRef } from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type {
  DataFlowColumn, DataFlowColumnPage, DataFlowDatabases, DataFlowSchemas, DataFlowTable, DataFlowTablePage,
} from '../../../../../types/dspmDatastores';
import type { DataFlowErd, DataFlowRelations } from '../../../../../types/dspmDrawers';

vi.mock('../../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));

import apiClient from '../../../../../utils/apiClient';
import { datastoreDrawerApi, type LazyResource } from '../../../../../hooks/useDspmDatastoreDrawer';
import DataFlowTab from '../DataFlowTab';
import { DrawerShell } from '../DrawerShell';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

// ─── synthetic data (shapes only; no captured values) ────────────────────────────────────────────

const SOURCE_ID = '0123456789abcdef01234567';
const uuid = (kind: 'a' | 'b' | 'c' | 'd', n: number) => `${kind}0000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

const DB = uuid('a', 1);
const FLAT_DB = uuid('a', 2);
const SALES = uuid('b', 1);
const OPS = uuid('b', 2);
const ORDERS = uuid('c', 1);
const CUSTOMERS = uuid('c', 2);
const STORES = uuid('c', 3);
const REFUNDS = uuid('c', 4);
const ITEMS = uuid('c', 5);
const AUDIT = uuid('c', 6);
const REPORT = uuid('c', 7);
const LEGACY = 'legacy-view';

const ROW = { name: 'warehouse', source: { provider: 'POSTGRES', providerLabel: 'PostgreSQL', accountLabel: null, region: null } };

const DATABASES: DataFlowDatabases = {
  items: [{ id: DB, name: 'warehouse_db', engine: 'POSTGRES', schemaless: false }],
  truncated: false,
};
const FLAT_DATABASES: DataFlowDatabases = {
  items: [{ id: FLAT_DB, name: 'metrics_db', engine: 'POSTGRES', schemaless: true }],
  truncated: false,
};
const SCHEMAS: DataFlowSchemas = { items: [{ id: SALES, name: 'sales' }, { id: OPS, name: 'ops' }], truncated: false };

/**
 * The "sales" diagram. `orders` lists its key columns in edge order (its first key out, then the target
 * of a key in, then its second key out, then a key to another schema); `audit_trail` has eight; two
 * tables have no key; `legacy_view`'s id is not a UUID.
 */
const SALES_ERD: DataFlowErd = {
  nodes: [
    { id: ORDERS, name: 'orders', sensitiveCount: 4, keyColumns: ['customer_id', 'id', 'store_id', 'ledger_id'] },
    { id: CUSTOMERS, name: 'customers', sensitiveCount: 0, keyColumns: ['id'] },
    { id: STORES, name: 'stores', sensitiveCount: 1, keyColumns: ['id'] },
    { id: REFUNDS, name: 'refunds', sensitiveCount: 2, keyColumns: ['order_id'] },
    { id: ITEMS, name: 'order_items', sensitiveCount: 0, keyColumns: ['order_id'] },
    {
      id: AUDIT, name: 'audit_trail', sensitiveCount: 0,
      keyColumns: ['customer_id', 'order_id', 'store_id', 'refund_id', 'item_id', 'actor_id', 'batch_id', 'device_id'],
    },
    { id: REPORT, name: 'report_cache', sensitiveCount: 0, keyColumns: [] },
    { id: LEGACY, name: 'legacy_view', sensitiveCount: null, keyColumns: [] },
  ],
  edges: [
    { id: 'fk-0', sourceTableId: ORDERS, targetKey: CUSTOMERS, external: false },
    { id: 'fk-1', sourceTableId: REFUNDS, targetKey: ORDERS, external: false },
    { id: 'fk-2', sourceTableId: ORDERS, targetKey: STORES, external: false },
    { id: 'fk-3', sourceTableId: ITEMS, targetKey: ORDERS, external: false },
    { id: 'fk-4', sourceTableId: ORDERS, targetKey: 'ext:finance.ledgers', external: true },
    { id: 'fk-5', sourceTableId: AUDIT, targetKey: CUSTOMERS, external: false },
  ],
  externalTables: [{ key: 'ext:finance.ledgers', schemaName: 'finance', tableName: 'ledgers' }],
  nodesTruncated: false,
  edgesTruncated: false,
};
const NAMES: Record<string, string> = Object.fromEntries(SALES_ERD.nodes.map(node => [node.id, node.name ?? '']));

const EMPTY_ERD: DataFlowErd = { nodes: [], edges: [], externalTables: [], nodesTruncated: false, edgesTruncated: false };
const FLAT_ERD: DataFlowErd = {
  ...EMPTY_ERD,
  nodes: [{ id: uuid('c', 20), name: 'daily_metrics', sensitiveCount: 0, keyColumns: [] }],
};

const TABLES: DataFlowTable[] = SALES_ERD.nodes.map(node => ({
  id: node.id, name: node.name, rowCountEstimate: 10, foreignKeyCount: node.keyColumns.length, sensitiveCount: node.sensitiveCount,
}));

function tablePage(url: URL): DataFlowTablePage {
  return {
    rows: TABLES, page: Number(url.searchParams.get('page')), pageSize: Number(url.searchParams.get('pageSize')),
    total: TABLES.length, totalPages: 1, searchCapped: false,
  };
}

const column = (n: number, name: string, nativeType: string, key: 'PK' | 'FK' | null = null): DataFlowColumn => ({
  id: uuid('d', n), name, nativeType, sensitiveCount: 0, dataElements: [], primaryKey: key === 'PK', foreignKey: key === 'FK',
});
const columnPage = (rows: DataFlowColumn[], total = rows.length): DataFlowColumnPage => ({
  rows, page: 1, pageSize: 100, total, totalPages: Math.ceil(total / 100),
});

const ORDER_COLUMNS = columnPage([
  column(1, 'id', 'int8', 'PK'), column(2, 'customer_id', 'int8', 'FK'), column(3, 'store_id', 'int8', 'FK'),
  column(4, 'total', 'numeric'), column(5, 'placed_at', 'timestamp'),
]);
const AUDIT_COLUMNS = columnPage(Array.from({ length: 100 }, (_, i) => column(100 + i, `field_${i}`, 'text')), 130);
const REFUND_COLUMNS = columnPage([column(201, 'id', 'int8', 'PK'), column(202, 'order_id', 'int8', 'FK')]);

const RELATIONS: DataFlowRelations = { outgoing: [], incoming: [] };

// ─── mocked API ──────────────────────────────────────────────────────────────────────────────────

const FLOW_PATH = /^\/api\/dspm\/data-flow\/(.+)$/;
const httpError = (status: number, code: string) => new Error(`HTTP ${status}: ${JSON.stringify({ code, message: 'synthetic failure' })}`);

let salesErd: () => unknown;
let refundColumns: () => unknown;

function flowAnswer(path: string, url: URL): unknown {
  if (path === `databases/${DB}/schemas`) return SCHEMAS;
  if (/^(schemas|databases)\/[^/]+\/tables$/.test(path)) return tablePage(url);
  if (path === `schemas/${SALES}/erd`) return salesErd();
  if (path === `schemas/${OPS}/erd`) return EMPTY_ERD;
  if (path === `databases/${FLAT_DB}/erd`) return FLAT_ERD;
  if (path === `tables/${ORDERS}/columns`) return ORDER_COLUMNS;
  if (path === `tables/${AUDIT}/columns`) return AUDIT_COLUMNS;
  if (path === `tables/${REFUNDS}/columns`) return refundColumns();
  if (/^tables\/[^/]+\/relations$/.test(path)) return RELATIONS;
  throw httpError(404, 'ASSET_NOT_FOUND');
}

beforeAll(async () => {
  // The canvas is lazy: import it (with cytoscape) once here, so no wait below includes that import.
  await import('../erd/ErdCanvas');
});

beforeEach(() => {
  salesErd = () => SALES_ERD;
  refundColumns = () => REFUND_COLUMNS;
  get.mockImplementation(async (raw: string) => {
    const url = new URL(raw, 'http://localhost');
    const path = FLOW_PATH.exec(url.pathname)?.[1];
    if (path === undefined) throw new Error(`HTTP 404: unexpected ${raw}`);
    return flowAnswer(path, url);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

const calls = () => get.mock.calls.map(([raw]) => new URL(String(raw), 'http://localhost'));
const erdRequests = () => calls().filter(url => url.pathname.endsWith('/erd')).map(url => url.pathname);
const tableRequests = () => calls().filter(url => url.pathname.endsWith('/tables'));
const columnRequests = (tableId: string) => calls().filter(url => url.pathname === `/api/dspm/data-flow/tables/${tableId}/columns`);

/** Waits past the 300 ms search debounce. */
const afterDebounce = () => act(async () => { await new Promise(resolve => setTimeout(resolve, 400)); });

// ─── harness: the Data Flow tab inside a root drawer ─────────────────────────────────────────────

function Harness({ databases, onRootClose }: { databases: DataFlowDatabases; onRootClose: () => void }) {
  const rootClose = useRef<HTMLButtonElement>(null);
  const api = useMemo(() => datastoreDrawerApi(SOURCE_ID), []);
  const resource = useMemo<LazyResource<DataFlowDatabases>>(
    () => ({ data: databases, error: null, loading: false, reload: () => {} }),
    [databases],
  );
  return (
    <DrawerShell label="warehouse" onClose={onRootClose} initialFocus={rootClose}>
      <button ref={rootClose} type="button">Close datastore</button>
      <DataFlowTab api={api} row={ROW} databases={resource} />
    </DrawerShell>
  );
}

/** Renders the tab and switches to Graph View once the first schema's tables are listed. */
async function openGraph(user: ReturnType<typeof userEvent.setup>, databases = DATABASES) {
  const onRootClose = vi.fn();
  render(<Harness databases={databases} onRootClose={onRootClose} />);
  await user.click(await screen.findByRole('button', { name: 'Graph View' }));
  return { onRootClose };
}

/** Graph View with the "sales" diagram drawn. */
async function openSales(user: ReturnType<typeof userEvent.setup>) {
  const opened = await openGraph(user);
  await screen.findByRole('group', { name: 'Schema diagram' });
  await screen.findByRole('button', { name: 'orders' });
  return opened;
}

const cardBody = (name: string) => screen.getByRole('button', { name });
const cardOf = (name: string) => cardBody(name).closest<HTMLElement>('[data-erd-card]')!;
const chips = (card: HTMLElement) => [...card.querySelectorAll('[data-chip]')].map(chip => chip.textContent);
const countOf = (card: HTMLElement) => card.querySelector('[data-sensitives]')!;
const pathOf = (id: string) => document.querySelector<SVGPathElement>(`[data-path="${id}"]`)!;
const searchbox = () => screen.getByRole('searchbox', { name: 'Search tables (min 3 characters)' });
const headers = (table: HTMLElement) => within(table).getAllByRole('columnheader').map(cell => cell.textContent);

/** The names of the dimmed cards and boxes, sorted. */
const dimmedNames = () => [...document.querySelectorAll<HTMLElement>('[data-erd-card], [data-erd-external]')]
  .filter(element => element.classList.contains('opacity-35'))
  .map(element => (element.dataset.erdCard !== undefined ? NAMES[element.dataset.erdCard] : element.dataset.erdExternal))
  .sort();

const FOOTER = 'Path: Instance > Database > Schema > Table > Columns. Click a table to open its details. '
  + 'Hover a table to highlight its FK relationships.';

// ─── tests ───────────────────────────────────────────────────────────────────────────────────────

describe('DataFlowGraph', () => {
  it('shows the schema diagram in Graph View with Table View\'s toolbar and sends no table request', async () => {
    const user = userEvent.setup();
    const onRootClose = vi.fn();
    render(<Harness databases={DATABASES} onRootClose={onRootClose} />);
    const graph = await screen.findByRole('button', { name: 'Graph View' });
    expect(graph).not.toHaveAttribute('aria-disabled');
    const tablesBefore = tableRequests().length;

    await user.click(graph);
    expect(await screen.findByRole('group', { name: 'Schema diagram' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Graph View' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('heading', { name: 'sales' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sync schema' })).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('button', { name: /^Column/ })).toHaveTextContent('Column 4');
    expect(searchbox()).toHaveAttribute('maxlength', '200');
    expect(erdRequests()).toEqual([`/api/dspm/data-flow/schemas/${SALES}/erd`]);
    expect(tableRequests()).toHaveLength(tablesBefore);
  });

  it('draws the cards with their FK chips in edge order and the red count, 0 included', async () => {
    const user = userEvent.setup();
    await openSales(user);

    const orders = cardOf('orders');
    expect(chips(orders)).toEqual(['FKcustomer_id', 'FKid', 'FKstore_id', 'FKledger_id']);
    expect(countOf(orders)).toHaveTextContent('4');
    expect(countOf(orders)).toHaveClass('text-rose-600');
    expect(countOf(cardOf('customers'))).toHaveTextContent('0');
    expect(within(orders).getByRole('button', { name: 'Show all columns' })).toHaveAttribute('aria-expanded', 'false');

    const audit = cardOf('audit_trail');
    expect(chips(audit)).toHaveLength(6);
    expect(audit).toHaveTextContent('+2 more');

    // A table of another schema: a dashed box with "schema.table" and no button.
    const external = document.querySelector<HTMLElement>('[data-erd-external="ext:finance.ledgers"]')!;
    expect(external).toHaveTextContent('finance.ledgers');
    expect(external).toHaveClass('border-dashed');
    expect(within(external).queryByRole('button')).toBeNull();
    expect(pathOf('fk-4')).toHaveAttribute('stroke-dasharray');
    expect(pathOf('fk-4')).not.toHaveAttribute('marker-end');
    expect(pathOf('fk-0')).toHaveAttribute('marker-end');
    expect(pathOf('fk-0')).not.toHaveAttribute('stroke-dasharray');

    // A card whose id is not a UUID is display only, with its null count shown as 0.
    const legacy = document.querySelector<HTMLElement>(`[data-erd-card="${LEGACY}"]`)!;
    expect(within(legacy).queryByRole('button')).toBeNull();
    expect(countOf(legacy)).toHaveTextContent('0');
  });

  it('requests each diagram once: a toggle and a schema revisited reuse it', async () => {
    const user = userEvent.setup();
    await openSales(user);
    await user.click(screen.getByRole('button', { name: 'Table View' }));
    await screen.findByRole('table');
    await user.click(screen.getByRole('button', { name: 'Graph View' }));
    expect(await screen.findByRole('button', { name: 'orders' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'ops' }));
    expect(await screen.findByText('No tables to show.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'sales' }));
    expect(await screen.findByRole('button', { name: 'orders' })).toBeInTheDocument();
    expect(erdRequests()).toEqual([`/api/dspm/data-flow/schemas/${SALES}/erd`, `/api/dspm/data-flow/schemas/${OPS}/erd`]);
  });

  it('keeps the search across the toggle, searches nothing in Graph View, and its chooser changes Table View', async () => {
    const user = userEvent.setup();
    await openSales(user);
    const tablesBefore = tableRequests().length;
    fireEvent.change(searchbox(), { target: { value: 'ord' } });
    await afterDebounce();
    expect(tableRequests()).toHaveLength(tablesBefore);

    await user.click(screen.getByRole('button', { name: /^Column/ }));
    await user.click(screen.getByRole('checkbox', { name: 'Rows' }));
    await user.keyboard('{Escape}');
    expect(screen.getByRole('button', { name: /^Column/ })).toHaveTextContent('Column 3');

    await user.click(screen.getByRole('button', { name: 'Table View' }));
    expect(headers(await screen.findByRole('table'))).toEqual(['Table', 'Foreign Keys', 'Sensitives']);
    expect(searchbox()).toHaveValue('ord');
    const sent = tableRequests().slice(tablesBefore);
    expect(sent).toHaveLength(1);
    expect(sent[0].searchParams.get('q')).toBe('ord');
  });

  it('dims the cards that share no connector with the hovered or focused card, and darkens its connectors', async () => {
    const user = userEvent.setup();
    await openSales(user);
    expect(dimmedNames()).toEqual([]);

    await user.hover(cardOf('customers'));
    expect(dimmedNames()).toEqual(['ext:finance.ledgers', 'legacy_view', 'order_items', 'refunds', 'report_cache', 'stores']);
    expect(pathOf('fk-0')).toHaveAttribute('data-active', 'true');
    expect(pathOf('fk-0')).toHaveAttribute('stroke-width', '2');
    expect(pathOf('fk-5')).toHaveAttribute('data-active', 'true');
    expect(pathOf('fk-1')).toHaveClass('opacity-35');
    expect(pathOf('fk-0')).not.toHaveClass('opacity-35');
    await user.unhover(cardOf('customers'));
    expect(dimmedNames()).toEqual([]);

    act(() => cardBody('stores').focus());
    expect(dimmedNames()).toEqual([
      'audit_trail', 'customers', 'ext:finance.ledgers', 'legacy_view', 'order_items', 'refunds', 'report_cache',
    ]);
    act(() => cardBody('stores').blur());
    expect(dimmedNames()).toEqual([]);
  });

  it('dims the cards whose name does not hold a search of 3 or more characters, and none for 2', async () => {
    const user = userEvent.setup();
    await openSales(user);
    fireEvent.change(searchbox(), { target: { value: 'ORD' } });
    expect(dimmedNames()).toEqual([
      'audit_trail', 'customers', 'ext:finance.ledgers', 'legacy_view', 'refunds', 'report_cache', 'stores',
    ]);
    expect(pathOf('fk-3')).not.toHaveClass('opacity-35');   // order_items → orders: both match
    expect(pathOf('fk-0')).toHaveClass('opacity-35');

    fireEvent.change(searchbox(), { target: { value: 'or' } });
    expect(dimmedNames()).toEqual([]);
  });

  it('"Show all columns" lists the columns once, with PK and FK badges and native types, and collapses again', async () => {
    const user = userEvent.setup();
    await openSales(user);
    const orders = cardOf('orders');
    await user.click(within(orders).getByRole('button', { name: 'Show all columns' }));

    const list = await within(orders).findByRole('list');
    expect(within(list).getAllByRole('listitem').map(item => item.textContent)).toEqual([
      'PKidint8', 'FKcustomer_idint8', 'FKstore_idint8', 'totalnumeric', 'placed_attimestamp',
    ]);
    expect(columnRequests(ORDERS)).toHaveLength(1);
    expect(columnRequests(ORDERS)[0].searchParams.get('page')).toBe('1');
    expect(columnRequests(ORDERS)[0].searchParams.get('pageSize')).toBe('100');
    expect(orders).toHaveClass('border-violet-500', 'z-10');
    expect(chips(orders)).toEqual([]);
    const less = within(orders).getByRole('button', { name: '^ Show less' });
    expect(less).toHaveAttribute('aria-expanded', 'true');

    await user.click(less);
    expect(within(orders).queryByRole('list')).toBeNull();
    expect(chips(orders)).toHaveLength(4);
    expect(orders).not.toHaveClass('border-violet-500');
    await user.click(within(orders).getByRole('button', { name: 'Show all columns' }));
    expect(within(orders).getByRole('list')).toBeInTheDocument();
    expect(columnRequests(ORDERS)).toHaveLength(1);

    const audit = cardOf('audit_trail');
    await user.click(within(audit).getByRole('button', { name: 'Show all columns' }));
    const auditList = await within(audit).findByRole('list');
    expect(within(auditList).getAllByRole('listitem')).toHaveLength(101);
    expect(auditList).toHaveTextContent('+30 more columns');
  });

  it('keeps an expanded card\'s failed column list and its Retry outside the body button (R9)', async () => {
    const user = userEvent.setup();
    let failures = 1;
    refundColumns = () => {
      if (failures > 0) {
        failures -= 1;
        throw httpError(502, 'BAD_UPSTREAM_RESPONSE');
      }
      return REFUND_COLUMNS;
    };
    await openSales(user);
    const refunds = cardOf('refunds');
    await user.click(within(refunds).getByRole('button', { name: 'Show all columns' }));

    const retry = await within(refunds).findByRole('button', { name: 'Retry' });
    expect(within(refunds).getByRole('alert')).toHaveTextContent('Matters is not responding right now. Try again shortly.');
    expect(cardBody('refunds').contains(retry)).toBe(false);
    expect(document.querySelector('button button')).toBeNull();

    await user.click(retry);
    const list = await within(refunds).findByRole('list');
    expect(within(list).getAllByRole('listitem').map(item => item.textContent)).toEqual(['PKidint8', 'FKorder_idint8']);
    expect(columnRequests(REFUNDS)).toHaveLength(2);
  });

  it('opens the table panel on a card click, and the panel returns focus to the card', async () => {
    const user = userEvent.setup();
    await openSales(user);
    await user.click(cardBody('orders'));

    const panel = await screen.findByRole('dialog', { name: 'orders' });
    expect(within(panel).getByText('Table')).toBeInTheDocument();
    expect(within(panel).getByRole('tab', { name: 'Columns' })).toHaveAttribute('aria-selected', 'true');
    expect(await within(panel).findByText('Showing 1 - 5 of 5')).toBeInTheDocument();
    expect(panel).toHaveClass('z-[60]');

    await user.click(within(panel).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog', { name: 'orders' })).toBeNull();
    await waitFor(() => expect(cardBody('orders')).toHaveFocus());
  });

  it('keeps the expanded cards across full screen, refetching their columns, and fits the zoom again', async () => {
    const user = userEvent.setup();
    await openSales(user);
    await user.click(within(cardOf('orders')).getByRole('button', { name: 'Show all columns' }));
    await within(cardOf('orders')).findByRole('list');
    await user.click(screen.getByRole('button', { name: 'Zoom in' }));

    await user.click(screen.getByRole('button', { name: 'Full screen' }));
    const frame = await screen.findByRole('dialog', { name: 'sales diagram' });
    const orders = within(frame).getByRole('button', { name: 'orders' }).closest<HTMLElement>('[data-erd-card]')!;
    expect(orders).toHaveClass('border-violet-500');
    expect(await within(orders).findByRole('list')).toBeInTheDocument();
    expect(columnRequests(ORDERS)).toHaveLength(2);
    expect(within(frame).getByText('55%')).toBeInTheDocument();
    expect(within(frame).queryByRole('button', { name: 'Full screen' })).toBeNull();
    expect(within(frame).getByText(FOOTER)).toBeInTheDocument();
  });

  it('opens the panel above full screen, and Escape closes the panel, then full screen, then the drawer (R1)', async () => {
    const user = userEvent.setup();
    const { onRootClose } = await openSales(user);
    await user.click(screen.getByRole('button', { name: 'Full screen' }));
    const frame = await screen.findByRole('dialog', { name: 'sales diagram' });
    await waitFor(() => expect(within(frame).getByRole('button', { name: 'Exit full screen' })).toHaveFocus());

    await user.click(within(frame).getByRole('button', { name: 'orders' }));
    const panel = await screen.findByRole('dialog', { name: 'orders' });
    expect(panel).toHaveClass('z-[80]');
    expect(panel.previousElementSibling).toHaveClass('z-[75]');
    await waitFor(() => expect(within(panel).getByRole('button', { name: 'Close' })).toHaveFocus());

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'orders' })).toBeNull();
    expect(screen.getByRole('dialog', { name: 'sales diagram' })).toBeInTheDocument();
    expect(within(frame).getByRole('button', { name: 'orders' })).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'sales diagram' })).toBeNull();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Full screen' })).toHaveFocus());
    expect(onRootClose).not.toHaveBeenCalled();

    await user.keyboard('{Escape}');
    expect(onRootClose).toHaveBeenCalledTimes(1);
  });

  it('notes a capped diagram above the legend', async () => {
    const user = userEvent.setup();
    salesErd = () => ({ ...SALES_ERD, nodesTruncated: true, edgesTruncated: true });
    await openSales(user);
    const tables = screen.getByText('Showing the first 1,000 tables.');
    const keys = screen.getByText('Showing the first 3,000 relationships.');
    const legend = screen.getByText('Primary key');
    expect(tables.compareDocumentPosition(keys) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(keys.compareDocumentPosition(legend) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('notes only the cap that was hit', async () => {
    const user = userEvent.setup();
    salesErd = () => ({ ...SALES_ERD, edgesTruncated: true });
    await openSales(user);
    expect(screen.getByText('Showing the first 3,000 relationships.')).toBeInTheDocument();
    expect(screen.queryByText('Showing the first 1,000 tables.')).toBeNull();
  });

  it('opens at 55% without a measured width and zooms between 20% and 150%', async () => {
    const user = userEvent.setup();
    await openSales(user);
    const viewport = screen.getByRole('group', { name: 'Schema diagram' });
    const layer = viewport.firstElementChild!.firstElementChild as HTMLElement;
    const zoomIn = screen.getByRole('button', { name: 'Zoom in' });
    const zoomOut = screen.getByRole('button', { name: 'Zoom out' });
    expect(screen.getByText('55%')).toBeInTheDocument();
    expect(layer.style.transform).toBe('scale(0.55)');

    await user.click(zoomIn);
    expect(screen.getByText('65%')).toBeInTheDocument();
    expect(layer.style.transform).toBe('scale(0.65)');
    for (let i = 0; i < 5; i += 1) await user.click(zoomOut);
    expect(screen.getByText('20%')).toBeInTheDocument();
    expect(zoomOut).toBeDisabled();
    for (let i = 0; i < 13; i += 1) await user.click(zoomIn);
    expect(screen.getByText('150%')).toBeInTheDocument();
    expect(zoomIn).toBeDisabled();
  });

  it('shows the legend and the footer verbatim', async () => {
    const user = userEvent.setup();
    await openSales(user);
    expect(['Primary key', 'Foreign key', 'Sensitives', 'FK relationship', 'Table not on this diagram']
      .map(text => screen.getByText(text).textContent))
      .toEqual(['PK Primary key', 'FK Foreign key', 'Sensitives', 'FK relationship', 'Table not on this diagram']);
    expect(screen.getByText(FOOTER)).toBeInTheDocument();
  });

  it('uses the database route for a database without schemas', async () => {
    const user = userEvent.setup();
    await openGraph(user, FLAT_DATABASES);
    expect(await screen.findByRole('button', { name: 'daily_metrics' })).toBeInTheDocument();
    expect(erdRequests()).toEqual([`/api/dspm/data-flow/databases/${FLAT_DB}/erd`]);
    expect(screen.getByRole('dialog', { name: 'warehouse' })).toBeInTheDocument();
  });

  it('shows a missing diagram\'s copy in the right pane only', async () => {
    const user = userEvent.setup();
    salesErd = () => {
      throw httpError(404, 'ASSET_NOT_FOUND');
    };
    await openGraph(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('This datastore could not be found. It may have been removed.');
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
    const tree = screen.getByRole('navigation', { name: 'Asset Hierarchy' });
    expect(within(tree).queryByRole('alert')).toBeNull();
    expect(within(tree).getByRole('button', { name: 'ops' })).toBeInTheDocument();
  });

  it('retries a failed diagram', async () => {
    const user = userEvent.setup();
    let failures = 1;
    salesErd = () => {
      if (failures > 0) {
        failures -= 1;
        throw httpError(502, 'BAD_UPSTREAM_RESPONSE');
      }
      return SALES_ERD;
    };
    await openGraph(user);
    await user.click(await screen.findByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('button', { name: 'orders' })).toBeInTheDocument();
    expect(erdRequests()).toHaveLength(2);
  });
});
