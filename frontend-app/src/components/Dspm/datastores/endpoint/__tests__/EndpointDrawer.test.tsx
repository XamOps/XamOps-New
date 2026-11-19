import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('../../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));

import apiClient from '../../../../../utils/apiClient';
import type { DatastoreFilterOptions } from '../../../../../types/dspmDatastores';
import type {
  EndpointFilePage, EndpointFileRow, EndpointMetric, EndpointOccurrencePage, EndpointOverview, EndpointScan,
  EndpointSummary,
} from '../../../../../types/dspmDrawers';
import type { DrawerRow } from '../../drawer/DatastoreDrawer';
import { CONSOLE_ONLY_TOOLTIP } from '../../ui/ConsoleOnly';
import EndpointDrawer from '../EndpointDrawer';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

// ─── synthetic data (shapes only; no captured values) ────────────────────────────────────────────

const SOURCE_ID = '0a1b2c3d4e5f0a1b2c3d4e5f';
const UPSTREAM = 'Matters is not responding right now. Try again shortly.';
/** The neutral chip colour of `categoryAccent(null)`. */
const GREY_CHIP = 'bg-[rgba(0,0,0,0.04)]';

/** Epoch seconds of a local time, so the expected texts do not depend on the time zone. */
const local = (year: number, month: number, day: number, hours: number, minutes: number) =>
  new Date(year, month - 1, day, hours, minutes).getTime() / 1000;

function endpointRow(over: Partial<DrawerRow> = {}): DrawerRow {
  return {
    id: 'endpoint-asset-1', sourceId: SOURCE_ID, name: 'build-laptop-07', category: 'endpoint',
    subtype: 'MAC', subtypeLabel: 'macOS',
    risk: { score: null, level: 'MEDIUM', levelLabel: 'Medium' },
    sensitives: { recordCount: 6, typeLabels: ['Personal'] },
    source: { provider: 'ENDPOINT_AGENT', providerLabel: 'Endpoint Agent', accountLabel: null, region: null },
    scan: { status: 'CLASSIFIED', statusLabel: 'Classified', displayAt: null },
    agent: { serialNumber: 'SN-TEST-0007', status: 'OFFLINE', statusLabel: 'Offline', version: '9.9.1' },
    ...over,
  };
}

const OVERVIEW: EndpointOverview = {
  serialNumber: 'SN-TEST-0042',
  lastScanAt: local(2026, 9, 18, 15, 20),
  lastAccessAt: local(2026, 9, 22, 12, 14),
  severityLabel: 'Medium',
  agentStatusLabel: 'Offline',
  agentOnline: false,
  users: ['Shared', 'tester'],
  hostname: 'build-laptop-07.local',
  visibleIp: '192.0.2.10',
  macAddress: '02:00:00:00:00:01',
  deviceTypeLabel: 'Laptop',
  osVersion: '15.1',
  architecture: 'arm64',
  memory: '32 GB',
  cpuUsage: 0.2546,
  memoryUsage: 0.61,
  agentVersion: '9.9.1',
};

const option = (value: string, label: string) => ({ value, label, count: null });

/** Each dimension holds one value the backend would split, trim or reject. */
const FILTER_OPTIONS: DatastoreFilterOptions = {
  dimensions: [
    { key: 'sensitives', options: [option('PII', 'Personal'), option('PCI', 'Financial'), option('pii_legacy', 'Legacy')], hasMore: false },
    {
      key: 'documentClass',
      options: [
        option('incident reports', 'Incident Reports'),
        option('system documentation', 'System Documentation'),
        option('contracts, agreements', 'Contracts, Agreements'),
        option(' padded class ', 'Padded Class'),
      ],
      hasMore: false,
    },
    { key: 'category', options: [option('it & technical', 'IT & Technical'), option('legal, finance', 'Legal, Finance')], hasMore: false },
    { key: 'dataElement', options: [option('EMAIL ADDRESS', 'EMAIL ADDRESS'), option(' PERSON NAME', 'PERSON NAME')], hasMore: false },
  ],
};

const metric = (kind: EndpointMetric['kind'], name: string, categoryLabel: string | null, count: number): EndpointMetric =>
  ({ kind, name, categoryLabel, count });

/** As the backend sends them: count descending, ties in API order. */
const METRICS: EndpointMetric[] = [
  metric('DOCUMENT_CLASS', 'system documentation', 'IT & Technical', 14),
  metric('DOCUMENT_CLASS', 'invoices & receipts', 'Financial', 6),
  metric('ENTITY', 'EMAIL ADDRESS', 'Personal', 4),
  metric('ENTITY', 'CREDIT CARD NUMBER', 'Financial', 3),
  metric('ENTITY', 'PERSON NAME', 'Personal', 2),
  metric('DOCUMENT_CLASS', 'incident reports', 'IT & Technical', 1),
  metric('DOCUMENT_CLASS', 'software licenses', 'IT & Technical', 1),
];

const SUMMARY: EndpointSummary = {
  totalFiles: 4_120,
  extensions: [
    { extension: 'java', label: 'JAVA', count: 1_204 },
    { extension: 'ts', label: 'TS', count: 388 },
    { extension: 'bak3', label: 'Bak3', count: 2 },
    { extension: 'csv', label: 'CSV', count: 1 },
  ],
  totalSensitives: 6,
  labelGroups: [{ label: 'Personal', entities: [{ name: 'EMAIL ADDRESS', count: 4 }, { name: 'PERSON NAME', count: 2 }] }],
  fileCategories: [{
    label: 'Financial',
    documentClasses: [{ name: 'technical manuals', count: 5 }, { name: 'system documentation', count: 14 }],
  }],
};

const range = (url: URL) => ({ skip: Number(url.searchParams.get('skip')), limit: Number(url.searchParams.get('limit')) });

/** An entity's occurrences: 12 in all; the first carries two masked samples and a tag, the second is marked. */
function occurrencePage(url: URL): EndpointOccurrencePage {
  const { skip, limit } = range(url);
  const total = 12;
  const rows = Array.from({ length: Math.max(0, Math.min(limit, total - skip)) }, (_, i) => {
    const n = skip + i + 1;
    return {
      fileName: n === 1 ? 'notes-export.json' : `contacts-${n}.csv`,
      path: n === 1 ? '/Users/tester/projects/demo/notes-export.json' : `/Users/tester/projects/demo/contacts-${n}.csv`,
      tags: n === 1 ? ['confidential'] : [],
      falsePositive: n === 2,
      samples: n === 1 ? ['j***@e******.org', 'p***@e******.org'] : ['********'],
    };
  });
  return { entityName: url.searchParams.get('entityName') ?? '', total, skip, limit, rows };
}

/** The vendor's file UUID of the air4-shaped row; the other File View rows carry none. */
const AIR4_ID = '0a1b2c3d-0000-4000-8000-00000000a4a4';

/** The air4 shape: user as returned, size base 1024, title-cased category and class, no tags. */
const AIR4_SHAPED: EndpointFileRow = {
  id: AIR4_ID, name: 'deploy-checklist.md', extension: 'md', path: '/Users/tester/projects/demo/deploy-checklist.md', user: 'APPLE',
  sizeBytes: 327_062, sensitiveCount: 1, labels: ['Personal'], categoryLabel: 'It & Technical',
  documentClassLabel: 'System Documentation', tags: [], modifiedAt: local(2026, 10, 26, 11, 36), scannedAt: null,
};

function filePage(url: URL, total = 23): EndpointFilePage {
  const { skip, limit } = range(url);
  const rows = Array.from({ length: Math.max(0, Math.min(limit, total - skip)) }, (_, i): EndpointFileRow => {
    const n = skip + i + 1;
    if (n === 1) return AIR4_SHAPED;
    return {
      id: null, name: `report-${n}.txt`, extension: 'txt', path: `/Users/tester/projects/demo/report-${n}.txt`, user: null,
      sizeBytes: null, sensitiveCount: 0, labels: [], categoryLabel: null, documentClassLabel: null, tags: ['internal'],
      modifiedAt: null, scannedAt: null,
    };
  });
  return { rows, skip, limit, total };
}

const scan = (over: Partial<EndpointScan>): EndpointScan => ({
  startTime: local(2026, 9, 18, 15, 20), endTime: local(2026, 9, 19, 22, 32), status: 'COMPLETED',
  statusLabel: 'Completed', sensitiveFiles: 2, totalFiles: 3_120, ...over,
});

type Answer = (url: URL) => unknown;
let answers: Record<string, Answer>;

const httpError = (status: number, code: string) =>
  new Error(`HTTP ${status}: ${JSON.stringify({ code, message: 'synthetic failure' })}`);
const fail = (status: number, code: string): Answer => () => { throw httpError(status, code); };

/** The Endpoint file drawer's answers for the air4-shaped file (O6). */
const FILE_ANSWERS: Record<string, Answer> = {
  overview: () => ({
    name: 'deploy-checklist.md', extension: 'md', owner: 'tester', categoryLabel: 'It & Technical',
    documentClassLabel: 'system documentation', sensitiveRecords: 1, path: AIR4_SHAPED.path, sizeBytes: 327_062,
    device: 'build-laptop-07', modifiedAt: AIR4_SHAPED.modifiedAt, fileCreatedAt: null, accessedAt: null, tags: [],
    sensitives: [{ dataElement: 'EMAIL ADDRESS', categoryLabel: 'Personal', count: 1 }],
  }),
  activity: () => ({ entries: [] }),
  lineage: () => ({ asset: null, copies: { available: true, coverageNote: null, lanes: [], events: [] } }),
  copies: () => ({ available: true, copies: [], locations: 1 }),
};

const ENDPOINT_PATH = /^\/api\/dspm\/datastores\/([0-9a-f]{24})\/endpoint\/([a-z-]+)$/;
const FILE_PATH = /^\/api\/dspm\/endpoint-files\/([0-9a-f-]{36})\/([a-z]+)$/;
const urls = () => get.mock.calls.map(([raw]) => new URL(String(raw), 'http://localhost'));
const requests = (endpoint: string) => urls().filter(url => ENDPOINT_PATH.exec(url.pathname)?.[2] === endpoint);
const fileRequests = (endpoint: string) =>
  urls().filter(url => FILE_PATH.exec(url.pathname)?.[2] === endpoint).map(url => FILE_PATH.exec(url.pathname)?.[1]);
const last = (list: URL[]) => list[list.length - 1];
const params = (url: URL) => [...url.searchParams.entries()];

beforeEach(() => {
  answers = {
    overview: () => OVERVIEW,
    'filter-options': () => FILTER_OPTIONS,
    summary: () => SUMMARY,
    sensitives: () => ({ metrics: METRICS }),
    occurrences: url => occurrencePage(url),
    files: url => filePage(url),
    scans: url => ({ rows: [scan({})], ...range(url), total: 1 }),
  };
  get.mockImplementation(async (raw: string) => {
    const url = new URL(raw, 'http://localhost');
    const file = FILE_PATH.exec(url.pathname);
    if (file && file[1] === AIR4_ID && FILE_ANSWERS[file[2]]) return FILE_ANSWERS[file[2]](url);
    const match = ENDPOINT_PATH.exec(url.pathname);
    const answer = match && match[1] === SOURCE_ID ? answers[match[2]] : undefined;
    if (!answer) throw new Error(`HTTP 404: unexpected ${raw}`);
    return answer(url);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

async function renderDrawer(row: DrawerRow = endpointRow()) {
  const onClose = vi.fn();
  render(<EndpointDrawer row={row} onClose={onClose} />);
  const drawer = await screen.findByRole('dialog', { name: row.name ?? '' });
  return { drawer, onClose };
}

/** The value under a strip label. */
const stripValue = (drawer: HTMLElement, label: string) => within(drawer).getByText(label).nextElementSibling as HTMLElement;

/** Entity View's table, once its rows arrived. */
async function entityTable(drawer: HTMLElement) {
  await within(drawer).findByText('system documentation');
  return within(drawer).getByRole('columnheader', { name: 'Entities / Document Class' }).closest('table') as HTMLTableElement;
}

const bodyRows = (table: HTMLTableElement) => Array.from(table.tBodies[0].rows);
const rowOf = (scope: HTMLElement, text: string) => within(scope).getByText(text).closest('tr') as HTMLTableRowElement;

/** A Properties or summary value: the `dd` after its label. */
const propertyValue = (card: HTMLElement, label: string) => within(card).getByText(label).nextElementSibling as HTMLElement;

// ─── tests ───────────────────────────────────────────────────────────────────────────────────────

describe('EndpointDrawer header and strip', () => {
  it('shows the name, the close X and the strip, falling back to the row until the overview arrives', async () => {
    let release: (value: EndpointOverview) => void = () => {};
    answers.overview = () => new Promise(resolve => { release = resolve; });
    const { drawer, onClose } = await renderDrawer();

    expect(within(drawer).getByRole('heading', { name: 'build-laptop-07' })).toBeInTheDocument();
    expect(within(drawer).getByRole('button', { name: 'Close' })).toHaveFocus();
    expect(stripValue(drawer, 'Device Number')).toHaveTextContent(/^SN-TEST-0007$/);
    expect(stripValue(drawer, 'Last Scan')).toHaveTextContent(/^-$/);
    expect(stripValue(drawer, 'Last Access')).toHaveTextContent(/^-$/);

    await act(async () => { release(OVERVIEW); });
    expect(stripValue(drawer, 'Device Number')).toHaveTextContent(/^SN-TEST-0042$/);
    expect(stripValue(drawer, 'Last Scan')).toHaveTextContent(/^18 Sep 2026, 03:20 PM$/);
    expect(stripValue(drawer, 'Last Access')).toHaveTextContent(/^22 Sep 2026, 12:14 PM$/);

    expect(within(drawer).getAllByRole('tab').map(tab => tab.textContent)).toEqual(['Sensitives', 'Information', 'Scan History']);
    expect(within(drawer).getByRole('tab', { name: 'Sensitives' })).toHaveAttribute('aria-selected', 'true');
    expect(within(drawer).queryByRole('button', { name: /scan/i })).toBeNull();
    expect(requests('overview')).toHaveLength(1);
    expect(requests('filter-options')).toHaveLength(1);
    expect(requests('summary')).toHaveLength(0);
    expect(requests('scans')).toHaveLength(0);

    await userEvent.setup().click(within(drawer).getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('EndpointDrawer Entity View', () => {
  it('lists the metrics as returned, entities in their colour and document categories grey', async () => {
    const { drawer } = await renderDrawer();
    const table = await entityTable(drawer);
    const rows = bodyRows(table);
    expect(rows.map(row => row.cells[0].textContent)).toEqual([
      'system documentation', 'invoices & receipts', 'EMAIL ADDRESS', 'CREDIT CARD NUMBER', 'PERSON NAME',
      'incident reports', 'software licenses',
    ]);
    expect(rows.map(row => row.cells[1].textContent)).toEqual([
      '14IT & Technical', '6Financial', '4Personal', '3Financial', '2Personal', '1IT & Technical', '1IT & Technical',
    ]);
    expect(within(drawer).getByText('Showing 1 - 7 of 7')).toBeInTheDocument();

    // A document category is grey even where a sensitive category of the same name has a colour (A23).
    expect(within(rows[1]).getByText('Financial')).toHaveClass(GREY_CHIP);
    expect(within(rows[3]).getByText('Financial')).toHaveClass('bg-violet-50');
    expect(within(rows[2]).getByText('Personal')).toHaveClass('bg-sky-50');
    // Entities read as entity chips, classes as returned in a grey tag; every row has its chevron.
    expect(within(rows[2]).getByText('EMAIL ADDRESS')).toHaveClass('uppercase');
    expect(within(rows[0]).getByText('system documentation')).not.toHaveClass('uppercase');
    for (const row of rows) expect(within(row).getByRole('button', { name: /^Expand / })).toBeInTheDocument();

    expect(requests('sensitives')).toHaveLength(1);
    expect(params(requests('sensitives')[0])).toEqual([['showFalsePositives', 'false']]);
  });

  it('filters the rows by the search in the browser, with "No matches" when none is left', async () => {
    const { drawer } = await renderDrawer();
    const table = await entityTable(drawer);
    const search = within(drawer).getByRole('searchbox', { name: 'Search entities' });

    fireEvent.change(search, { target: { value: 'address' } });
    expect(bodyRows(table).map(row => row.cells[0].textContent)).toEqual(['EMAIL ADDRESS']);
    fireEvent.change(search, { target: { value: 'nothing-like-this' } });
    expect(within(table).getByText('No matches')).toBeInTheDocument();
    expect(requests('sensitives')).toHaveLength(1);
  });

  it('says "No sensitive entities found" when the agent has no metrics', async () => {
    answers.sensitives = () => ({ metrics: [] });
    const { drawer } = await renderDrawer();
    expect(await within(drawer).findByText('No sensitive entities found')).toBeInTheDocument();
    expect(within(drawer).getByText("Nothing matched the current filters on this agent's last scan.")).toBeInTheDocument();
  });

  it('shows a failed metrics request with Retry, which asks again', async () => {
    const user = userEvent.setup();
    answers.sensitives = fail(502, 'UPSTREAM_UNAVAILABLE');
    const { drawer } = await renderDrawer();
    const alert = await within(drawer).findByRole('alert');
    expect(alert).toHaveTextContent(UPSTREAM);
    answers.sensitives = () => ({ metrics: METRICS });
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await within(drawer).findByText('system documentation')).toBeInTheDocument();
    expect(requests('sensitives')).toHaveLength(2);
  });

  it('expands an entity into its occurrences with masked samples, the path hint and a greyed Mark', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderDrawer();
    await entityTable(drawer);
    await user.click(within(drawer).getByRole('button', { name: 'Expand EMAIL ADDRESS' }));

    const sample = await within(drawer).findByText('j***@e******.org');
    const nested = sample.closest('table') as HTMLTableElement;
    expect(within(nested).getAllByRole('columnheader').map(header => header.textContent))
      .toEqual(['File Name', 'Matters Tags', 'File Path', 'Sample Data', 'False Positive']);
    expect(within(nested).getByRole('columnheader', { name: /File Path/ })).toContainElement(
      within(nested).getByRole('button', { name: 'More information' }),
    );
    expect(params(requests('occurrences')[0])).toEqual([
      ['entityName', 'EMAIL ADDRESS'], ['skip', '0'], ['limit', '10'], ['showFalsePositives', 'false'],
    ]);

    // The samples as returned, through SampleCell: the first one, then "+N".
    const first = rowOf(nested, 'notes-export.json');
    expect(sample).toHaveClass('font-mono');
    expect(within(first).getByText('+1')).toBeInTheDocument();
    expect(within(first).queryByText('p***@e******.org')).toBeNull();
    expect(within(first).getByText('confidential')).toBeInTheDocument();
    expect(within(first).getByText('/Users/tester/projects/demo/notes-export.json')).toBeInTheDocument();
    expect(within(first).getByRole('button', { name: 'Copy path' })).toBeInTheDocument();
    expect(within(first).queryByText('False positive')).toBeNull();
    const second = rowOf(nested, 'contacts-2.csv');
    expect(within(second).getByText('False positive')).toBeInTheDocument();
    expect(second.cells[1]).toHaveTextContent(/^-$/);

    const marks = within(nested).getAllByRole('button', { name: 'Mark' });
    expect(marks).toHaveLength(10);
    for (const mark of marks) expect(mark).toHaveAttribute('aria-disabled', 'true');
    fireEvent.mouseEnter(marks[0]);
    expect(document.getElementById(marks[0].getAttribute('aria-describedby') ?? '')).toHaveTextContent(CONSOLE_ONLY_TOOLTIP);

    // Paged on the server.
    const box = nested.closest('div.overflow-hidden') as HTMLElement;
    expect(within(box).getByText('Showing 1 - 10 of 12')).toBeInTheDocument();
    await user.click(within(box).getByRole('button', { name: '2' }));
    await waitFor(() => expect(last(requests('occurrences')).searchParams.get('skip')).toBe('10'));
    expect(await within(drawer).findByText('Showing 11 - 12 of 12')).toBeInTheDocument();

    // Collapsing discards the samples.
    await user.click(within(drawer).getByRole('button', { name: 'Collapse EMAIL ADDRESS' }));
    expect(within(drawer).queryByText('********')).toBeNull();
  });

  it('expands a document class into its files through the documentClass filter alone', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderDrawer();
    await entityTable(drawer);
    await user.click(within(drawer).getByRole('button', { name: 'Expand system documentation' }));

    const nested = (await within(drawer).findByText('deploy-checklist.md')).closest('table') as HTMLTableElement;
    expect(within(nested).getAllByRole('columnheader').map(header => header.textContent))
      .toEqual(['File Name', 'Matters Tags', 'File Path']);
    expect(params(requests('files')[0])).toEqual([['documentClass', 'system documentation'], ['skip', '0'], ['limit', '10']]);
    expect(rowOf(nested, 'deploy-checklist.md').cells[1]).toHaveTextContent(/^-$/);
    expect(within(rowOf(nested, 'report-2.txt')).getByText('internal')).toBeInTheDocument();
    expect(within(nested).queryByText('Sample Data')).toBeNull();

    // One row open at a time.
    await user.click(within(drawer).getByRole('button', { name: 'Expand EMAIL ADDRESS' }));
    expect(await within(drawer).findByText('notes-export.json')).toBeInTheDocument();
    expect(within(drawer).queryByText('deploy-checklist.md')).toBeNull();
  });

  it('gives a document class with a comma or edge spaces no chevron and never requests its files (GAP-21)', async () => {
    const user = userEvent.setup();
    answers.sensitives = () => ({
      metrics: [
        metric('DOCUMENT_CLASS', 'contracts, agreements', 'Legal', 3),
        metric('DOCUMENT_CLASS', ' padded class ', 'Legal', 2),
        metric('DOCUMENT_CLASS', 'incident reports', 'IT & Technical', 1),
      ],
    });
    const { drawer } = await renderDrawer();
    const comma = (await within(drawer).findByText('contracts, agreements')).closest('tr') as HTMLTableRowElement;
    const padded = rowOf(drawer, 'padded class');
    for (const row of [comma, padded]) {
      expect(within(row).queryByRole('button', { name: /^Expand/ })).toBeNull();
      await user.click(row);
    }
    expect(requests('files')).toHaveLength(0);
    expect(within(drawer).queryByRole('columnheader', { name: 'File Name' })).toBeNull();
    expect(within(drawer).getByRole('button', { name: 'Expand incident reports' })).toBeInTheDocument();
  });
});

describe('EndpointDrawer filters', () => {
  it('offers no Include / Exclude and sends the chosen values comma-joined, to both views', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderDrawer();
    await entityTable(drawer);

    await user.click(within(drawer).getByRole('button', { name: /^Sensitives/ }));
    const sensitives = screen.getByRole('dialog', { name: 'Sensitives' });
    expect(within(sensitives).queryByRole('combobox', { name: 'Include or exclude' })).toBeNull();
    await user.click(within(sensitives).getByRole('checkbox', { name: /^Personal/ }));
    await waitFor(() => expect(last(requests('sensitives')).searchParams.get('sensitives')).toBe('PII'));
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Sensitives' })).toBeNull();
    expect(screen.getByRole('dialog', { name: 'build-laptop-07' })).toBeInTheDocument();
    expect(within(drawer).getByRole('button', { name: /^Sensitives/ })).toHaveTextContent(/1/);

    await user.click(within(drawer).getByRole('button', { name: /^Document Class/ }));
    const classes = screen.getByRole('dialog', { name: 'Document Class' });
    expect(within(classes).queryByRole('combobox', { name: 'Include or exclude' })).toBeNull();
    await user.click(within(classes).getByRole('checkbox', { name: /^Incident Reports/ }));
    await user.click(within(classes).getByRole('checkbox', { name: /^System Documentation/ }));
    await waitFor(() => expect(last(requests('sensitives')).searchParams.get('documentClass'))
      .toBe('incident reports,system documentation'));
    expect(last(requests('sensitives')).searchParams.get('sensitives')).toBe('PII');
    expect(last(requests('sensitives')).searchParams.get('showFalsePositives')).toBe('false');
    await user.keyboard('{Escape}');

    await user.click(within(drawer).getByRole('button', { name: 'File View' }));
    await waitFor(() => expect(requests('files')).toHaveLength(1));
    expect(params(requests('files')[0])).toEqual([
      ['sensitives', 'PII'], ['documentClass', 'incident reports,system documentation'], ['skip', '0'], ['limit', '10'],
    ]);
  });

  it('disables a value with a comma, edge spaces or a lower-case code, in the dropdowns and in "+ More filters"', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderDrawer();
    await entityTable(drawer);

    await user.click(within(drawer).getByRole('button', { name: 'More filters' }));
    const more = screen.getByRole('dialog', { name: 'More filters' });
    const list = within(more).getByRole('list', { name: 'Filters' });
    expect(within(list).getAllByRole('button').map(button => button.textContent?.trim()))
      .toEqual(['Sensitives', 'Document Class', 'Category', 'Data Element']);
    expect(within(more).queryByRole('combobox', { name: 'Include or exclude' })).toBeNull();
    expect(within(more).getByRole('checkbox', { name: /^Personal/ })).toBeEnabled();
    expect(within(more).getByRole('checkbox', { name: /^Legacy/ })).toBeDisabled();
    await user.click(within(list).getByRole('button', { name: 'Document Class' }));
    expect(within(more).getByRole('checkbox', { name: /^Incident Reports/ })).toBeEnabled();
    expect(within(more).getByRole('checkbox', { name: /^Contracts, Agreements/ })).toBeDisabled();
    expect(within(more).getByRole('checkbox', { name: /^Padded Class/ })).toBeDisabled();
    await user.click(within(list).getByRole('button', { name: 'Category' }));
    expect(within(more).getByRole('checkbox', { name: /^IT & Technical/ })).toBeEnabled();
    expect(within(more).getByRole('checkbox', { name: /^Legal, Finance/ })).toBeDisabled();
    await user.click(within(list).getByRole('button', { name: 'Data Element' }));
    expect(within(more).getByRole('checkbox', { name: /^EMAIL ADDRESS/ })).toBeEnabled();
    expect(within(more).getByRole('checkbox', { name: /^PERSON NAME/ })).toBeDisabled();
    await user.click(within(more).getByRole('checkbox', { name: /^EMAIL ADDRESS/ }));
    await waitFor(() => expect(last(requests('sensitives')).searchParams.get('dataElement')).toBe('EMAIL ADDRESS'));
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'More filters' })).toBeNull();
    expect(screen.getByRole('dialog', { name: 'build-laptop-07' })).toBeInTheDocument();

    await user.click(within(drawer).getByRole('button', { name: /^Document Class/ }));
    const classes = screen.getByRole('dialog', { name: 'Document Class' });
    expect(within(classes).getByRole('checkbox', { name: /^Contracts, Agreements/ })).toBeDisabled();
    expect(within(classes).getByRole('checkbox', { name: /^Padded Class/ })).toBeDisabled();
    // "Select all" takes only the usable values.
    await user.click(within(classes).getByRole('checkbox', { name: 'Select all' }));
    await waitFor(() => expect(last(requests('sensitives')).searchParams.get('documentClass'))
      .toBe('incident reports,system documentation'));
    await user.keyboard('{Escape}');

    await user.click(within(drawer).getByRole('button', { name: /^Sensitives/ }));
    expect(within(screen.getByRole('dialog', { name: 'Sensitives' })).getByRole('checkbox', { name: /^Legacy/ })).toBeDisabled();
  });

  it('says "Could not load options" in the dropdowns when the filter options fail', async () => {
    const user = userEvent.setup();
    answers['filter-options'] = fail(502, 'UPSTREAM_UNAVAILABLE');
    const { drawer } = await renderDrawer();
    await entityTable(drawer);
    await user.click(within(drawer).getByRole('button', { name: /^Sensitives/ }));
    expect(within(screen.getByRole('dialog', { name: 'Sensitives' })).getByText('Could not load options')).toBeInTheDocument();
  });
});

describe('EndpointDrawer File View', () => {
  async function openFileView() {
    const user = userEvent.setup();
    const { drawer, onClose } = await renderDrawer();
    await entityTable(drawer);
    await user.click(within(drawer).getByRole('button', { name: 'File View' }));
    const table = (await within(drawer).findByText('deploy-checklist.md')).closest('table') as HTMLTableElement;
    return { user, drawer, table, onClose };
  }

  it('shows "Columns 9", the unlabelled lineage column after File Name and two sticky cells', async () => {
    const { user, drawer, table } = await openFileView();
    const headers = within(table).getAllByRole('columnheader');
    expect(headers.map(header => header.textContent)).toEqual([
      'File Name', '', 'File Path', 'Users', 'File Size', 'Sensitives', 'Category', 'Document Class', 'Matters Tags',
      'Last Modified',
    ]);
    expect(headers[1]).toHaveAttribute('aria-label', 'Lineage');
    expect(headers[0]).toHaveClass('sticky', 'left-0', 'z-10');
    expect(headers[1]).toHaveClass('sticky', 'left-56', 'z-10');

    const row = rowOf(table, 'deploy-checklist.md');
    const cells = Array.from(row.cells);
    expect(cells[0]).toHaveClass('sticky', 'left-0', 'z-10', 'bg-white');
    expect(cells[1]).toHaveClass('sticky', 'left-56', 'z-10', 'bg-white');
    expect(cells[2]).not.toHaveClass('sticky');
    // O6: the row with a file id has the lineage icon and opens; the rows without one have an empty cell.
    const lineage = within(cells[1]).getByRole('button', { name: 'View lineage for deploy-checklist.md' });
    expect(lineage).toHaveAttribute('title', 'View lineage');
    expect(lineage).not.toHaveAttribute('aria-disabled');
    expect(within(table).getAllByRole('button', { name: /lineage/i })).toHaveLength(1);
    expect(row).toHaveAttribute('tabindex', '0');
    expect(rowOf(table, 'report-2.txt')).not.toHaveAttribute('tabindex');

    // The air4 shape.
    expect(cells[2]).toHaveTextContent('/Users/tester/projects/demo/deploy-checklist.md');
    expect(cells[3]).toHaveTextContent(/^APPLE$/);
    expect(cells[4]).toHaveTextContent(/^319\.4 KB$/);
    expect(cells[5]).toHaveTextContent(/^1Personal$/);
    expect(cells[6]).toHaveTextContent(/^It & Technical$/);
    expect(cells[7]).toHaveTextContent(/^System Documentation$/);
    expect(cells[8]).toHaveTextContent(/^-$/);
    expect(cells[9]).toHaveTextContent(/^26 Oct 2026, 11:36 AM$/);
    expect(within(cells[6]).getByText('It & Technical').querySelector('span')).toHaveClass('bg-sky-500');
    expect(within(cells[7]).getByText('System Documentation').querySelector('span')).toHaveClass('bg-rose-400');
    const other = Array.from(rowOf(table, 'report-2.txt').cells);
    expect(other.slice(3, 5).map(cell => cell.textContent)).toEqual(['-', '-']);

    // Nine entries in the menu, File Name always shown; the lineage column is not among them.
    const columns = within(drawer).getByRole('button', { name: /^Columns/ });
    expect(columns).toHaveTextContent('9');
    await user.click(columns);
    const menu = columns.parentElement as HTMLElement;
    expect(within(menu).getAllByRole('checkbox')).toHaveLength(9);
    expect(within(menu).getByRole('checkbox', { name: /File Name/ })).toBeDisabled();
    expect(within(menu).queryByText('Lineage')).toBeNull();
    await user.click(within(menu).getByRole('checkbox', { name: /Users/ }));
    expect(columns).toHaveTextContent('8');
    expect(within(table).queryByText('APPLE')).toBeNull();

    // A row without a file id opens nothing; paging is the server's.
    await user.click(rowOf(table, 'report-2.txt'));
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    expect(within(drawer).getByText('Showing 1 - 10 of 23')).toBeInTheDocument();
    await user.click(within(drawer).getByRole('button', { name: '3' }));
    await waitFor(() => expect(last(requests('files')).searchParams.get('skip')).toBe('20'));
  });

  it('opens the Endpoint file drawer over it from a row and the lineage icon; Escape closes it first, then the drawer', async () => {
    const { user, table, onClose } = await openFileView();
    await user.click(rowOf(table, 'deploy-checklist.md'));
    const fileDrawer = await screen.findByRole('dialog', { name: 'deploy-checklist.md' });
    expect(screen.getAllByRole('dialog')).toHaveLength(2);
    expect(within(fileDrawer).getByRole('tab', { name: 'Sensitives' })).toHaveAttribute('aria-selected', 'true');
    expect(await within(fileDrawer).findByText('EMAIL ADDRESS')).toBeInTheDocument();
    expect(fileRequests('overview')).toEqual([AIR4_ID]);
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'deploy-checklist.md' })).toBeNull();
    expect(rowOf(table, 'deploy-checklist.md')).toHaveFocus();
    expect(onClose).not.toHaveBeenCalled();

    await user.click(within(table).getByRole('button', { name: 'View lineage for deploy-checklist.md' }));
    const onLineage = await screen.findByRole('dialog', { name: 'deploy-checklist.md' });
    expect(within(onLineage).getByRole('tab', { name: 'Lineage' })).toHaveAttribute('aria-selected', 'true');
    expect(await within(onLineage).findByText('No lineage recorded for this file.')).toBeInTheDocument();
    expect(fileRequests('lineage')).toEqual([AIR4_ID]);
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'deploy-checklist.md' })).toBeNull();
    expect(rowOf(table, 'deploy-checklist.md')).toHaveFocus();
    expect(onClose).not.toHaveBeenCalled();
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('sends the search debounced and returns to page 1', async () => {
    const { user, drawer } = await openFileView();
    await user.click(within(drawer).getByRole('button', { name: '2' }));
    await waitFor(() => expect(last(requests('files')).searchParams.get('skip')).toBe('10'));
    fireEvent.change(within(drawer).getByRole('searchbox', { name: 'Search files' }), { target: { value: 'checklist' } });
    await waitFor(() => expect(last(requests('files')).searchParams.get('q')).toBe('checklist'));
    expect(last(requests('files')).searchParams.get('skip')).toBe('0');
  });

  it('sends no request when Show False Positives changes there, and applies it back in Entity View', async () => {
    const { user, drawer } = await openFileView();
    const files = requests('files').length;
    const metrics = requests('sensitives').length;
    const toggle = within(drawer).getByRole('switch', { name: 'Show False Positives' });

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 350)); });
    expect(requests('files')).toHaveLength(files);
    expect(requests('sensitives')).toHaveLength(metrics);

    await user.click(within(drawer).getByRole('button', { name: 'Entity View' }));
    await waitFor(() => expect(requests('sensitives')).toHaveLength(metrics + 1));
    expect(last(requests('sensitives')).searchParams.get('showFalsePositives')).toBe('true');
    await user.click(await within(drawer).findByRole('button', { name: 'Expand EMAIL ADDRESS' }));
    await waitFor(() => expect(requests('occurrences')).toHaveLength(1));
    expect(requests('occurrences')[0].searchParams.get('showFalsePositives')).toBe('true');
    expect(requests('files').every(url => !url.searchParams.has('showFalsePositives'))).toBe(true);
  });
});

describe('EndpointDrawer Information', () => {
  async function openInformation() {
    const user = userEvent.setup();
    const result = await renderDrawer();
    await user.click(within(result.drawer).getByRole('tab', { name: 'Information' }));
    return { user, ...result };
  }

  it('shows the summary cards and the Properties of an offline agent', async () => {
    const { user, drawer } = await openInformation();
    const fileSummary = await within(drawer).findByRole('region', { name: 'File Summary' });
    expect(propertyValue(fileSummary, 'Total Files')).toHaveTextContent(/^4\.12K$/);
    expect(Array.from(fileSummary.querySelectorAll('[data-extension]')).map(chip => chip.textContent))
      .toEqual(['JAVA 1,204', 'TS 388', 'Bak3 2', 'CSV 1']);

    const sensitives = within(drawer).getByRole('region', { name: 'Sensitives' });
    expect(propertyValue(sensitives, 'Total Sensitive Records')).toHaveTextContent(/^6$/);
    expect(within(sensitives).getByText('Personal')).toHaveClass('bg-sky-50');
    expect(within(sensitives).getAllByRole('listitem').map(item => item.textContent)).toEqual(['Email address4', 'Person name2']);

    const categories = within(drawer).getByRole('region', { name: 'File Categories' });
    expect(within(categories).getByText('Financial')).toHaveClass(GREY_CHIP);
    expect(within(categories).getAllByRole('listitem').map(item => item.textContent))
      .toEqual(['technical manuals5', 'system documentation14']);

    const properties = within(drawer).getByRole('region', { name: 'Properties' });
    expect(Array.from(properties.querySelectorAll('dt')).map(term => term.textContent)).toEqual([
      'Severity', 'Agent Status', 'Users', 'Hostname', 'Visible IP', 'MAC Address', 'Device Type', 'OS Version',
      'Architecture', 'Memory', 'CPU Utilization', 'Memory Utilization', 'Agent Version',
    ]);
    expect(propertyValue(properties, 'Severity')).toHaveTextContent(/^Medium$/);
    expect(propertyValue(properties, 'Agent Status')).toHaveTextContent(/^Offline$/);
    expect(propertyValue(properties, 'Users')).toHaveTextContent(/^Shared, tester$/);
    expect(propertyValue(properties, 'Visible IP')).toHaveTextContent(/^192\.0\.2\.10$/);
    expect(propertyValue(properties, 'Device Type')).toHaveTextContent(/^Laptop$/);
    expect(propertyValue(properties, 'CPU Utilization')).toHaveTextContent(/^-$/);
    expect(propertyValue(properties, 'Memory Utilization')).toHaveTextContent(/^-$/);
    expect(propertyValue(properties, 'Agent Version')).toHaveTextContent(/^9\.9\.1$/);

    // Loaded once, kept across tab switches.
    await user.click(within(drawer).getByRole('tab', { name: 'Sensitives' }));
    await user.click(within(drawer).getByRole('tab', { name: 'Information' }));
    expect(requests('summary')).toHaveLength(1);
    expect(requests('overview')).toHaveLength(1);
  });

  it("shows an online agent's utilization as a whole percent and a missing property as \"-\"", async () => {
    answers.overview = () => ({ ...OVERVIEW, agentStatusLabel: 'Online', agentOnline: true, hostname: null, users: [] });
    const { drawer } = await openInformation();
    const properties = await within(drawer).findByRole('region', { name: 'Properties' });
    expect(propertyValue(properties, 'Agent Status')).toHaveTextContent(/^Online$/);
    expect(propertyValue(properties, 'CPU Utilization')).toHaveTextContent(/^25%$/);
    expect(propertyValue(properties, 'Memory Utilization')).toHaveTextContent(/^61%$/);
    expect(propertyValue(properties, 'Hostname')).toHaveTextContent(/^-$/);
    expect(propertyValue(properties, 'Users')).toHaveTextContent(/^-$/);
  });

  it('keeps Properties when the summary fails, with its own Retry', async () => {
    answers.summary = fail(502, 'UPSTREAM_UNAVAILABLE');
    const { user, drawer } = await openInformation();
    const alert = await within(drawer).findByRole('alert');
    expect(alert).toHaveTextContent(UPSTREAM);
    expect(within(drawer).getByRole('region', { name: 'Properties' })).toBeInTheDocument();
    expect(within(drawer).queryByRole('region', { name: 'File Summary' })).toBeNull();

    answers.summary = () => SUMMARY;
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await within(drawer).findByRole('region', { name: 'File Summary' })).toBeInTheDocument();
    expect(requests('summary')).toHaveLength(2);
    expect(requests('overview')).toHaveLength(1);
  });

  it('keeps File Summary when the overview fails, with its own Retry, and the strip on the row', async () => {
    answers.overview = fail(502, 'UPSTREAM_UNAVAILABLE');
    const { user, drawer } = await openInformation();
    expect(await within(drawer).findByRole('region', { name: 'File Summary' })).toBeInTheDocument();
    const alert = within(drawer).getByRole('alert');
    expect(alert).toHaveTextContent(UPSTREAM);
    expect(within(drawer).queryByRole('region', { name: 'Properties' })).toBeNull();
    expect(stripValue(drawer, 'Device Number')).toHaveTextContent(/^SN-TEST-0007$/);

    answers.overview = () => OVERVIEW;
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await within(drawer).findByRole('region', { name: 'Properties' })).toBeInTheDocument();
    expect(stripValue(drawer, 'Device Number')).toHaveTextContent(/^SN-TEST-0042$/);
    expect(requests('summary')).toHaveLength(1);
  });
});

describe('EndpointDrawer Scan History', () => {
  it('lists the runs with "Columns 5" and "-" for a missing end time and a negative or missing total', async () => {
    const user = userEvent.setup();
    answers.scans = url => ({
      rows: [
        scan({}),
        scan({ startTime: local(2026, 10, 2, 9, 5), endTime: null, status: 'IN_PROGRESS', statusLabel: 'In Progress', sensitiveFiles: null, totalFiles: -1 }),
        scan({ startTime: local(2026, 10, 3, 9, 5), endTime: null, status: 'IN_PROGRESS', statusLabel: 'In Progress', sensitiveFiles: 0, totalFiles: null }),
      ],
      ...range(url),
      total: 3,
    });
    const { drawer } = await renderDrawer();
    await user.click(within(drawer).getByRole('tab', { name: 'Scan History' }));
    const panel = within(drawer).getByRole('tabpanel');
    const table = (await within(panel).findByText('18 Sep 2026, 03:20 PM')).closest('table') as HTMLTableElement;

    expect(within(drawer).getByRole('button', { name: /^Columns/ })).toHaveTextContent('5');
    expect(within(table).getAllByRole('columnheader').map(header => header.textContent))
      .toEqual(['Start Time', 'End Time', 'Scan Status', 'Sensitive Files', 'Total Files']);
    const rows = bodyRows(table).map(row => Array.from(row.cells).map(cell => cell.textContent));
    expect(rows).toEqual([
      ['18 Sep 2026, 03:20 PM', '19 Sep 2026, 10:32 PM', 'Completed', '2', '3.12K'],
      ['02 Oct 2026, 09:05 AM', '-', 'In Progress', '-', '-'],
      ['03 Oct 2026, 09:05 AM', '-', 'In Progress', '0', '-'],
    ]);
    expect(params(requests('scans')[0])).toEqual([['skip', '0'], ['limit', '10']]);
  });

  it('says "No scan history yet" without runs', async () => {
    const user = userEvent.setup();
    answers.scans = url => ({ rows: [], ...range(url), total: 0 });
    const { drawer } = await renderDrawer();
    await user.click(within(drawer).getByRole('tab', { name: 'Scan History' }));
    expect(await within(drawer).findByText('No scan history yet')).toBeInTheDocument();
    expect(within(drawer).getByText('Runs appear here once this device has completed a scan.')).toBeInTheDocument();
  });
});
