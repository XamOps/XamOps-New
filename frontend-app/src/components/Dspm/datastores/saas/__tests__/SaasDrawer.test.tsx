import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
vi.mock('../../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
import apiClient from '../../../../../utils/apiClient';
import type {
  SaasDrawerHeader, SaasFilePage, SaasRecordRow, SaasRecords, SaasScanSummary,
} from '../../../../../types/dspmDrawers';
import type { DrawerRow } from '../../drawer/DatastoreDrawer';
import { NOT_SCANNED_TEXT } from '../../drawer/TabParts';
import SaasDrawer from '../SaasDrawer';
import { copies, copy, driveOverview, local, overview } from './saasTestData';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

// ─── synthetic data (shapes only; no captured values) ────────────────────────────────────────────

const SOURCE_ID = 'abcdefabcdefabcdefabcdef';
const UPSTREAM = 'Matters is not responding right now. Try again shortly.';

function saasRow(over: Partial<DrawerRow> = {}): DrawerRow {
  return {
    id: 'saas-asset-1', sourceId: SOURCE_ID, name: 'Pat Example', category: 'saas',
    subtype: 'GMAIL', subtypeLabel: 'Gmail',
    risk: { score: null, level: 'HIGH', levelLabel: 'High' },
    sensitives: { recordCount: 3_210, typeLabels: ['Personal'] },
    source: { provider: 'GOOGLE_WORKSPACE', providerLabel: 'Google Workspace', accountLabel: 'example-workspace', region: null },
    scan: { status: 'CLASSIFIED', statusLabel: 'Classified', displayAt: null },
    agent: null,
    ...over,
  };
}

const HEADER: SaasDrawerHeader = {
  driveType: 'GMAIL', driveTypeLabel: 'Gmail', accountName: 'example-gws',
  backfillState: 'COMPLETED', backfillLabel: 'Completed', incrementalState: 'DISABLED', incrementalLabel: 'Disabled',
};

const record = (name: string, count: number, categoryLabel: string | null): SaasRecordRow => ({ name, count, categoryLabel });

const DATA_ELEMENTS: SaasRecordRow[] = [
  record('EMAIL ADDRESS', 3_210, 'Personal'),
  record('PERSON NAME', 512, 'Personal'),
  record('ADDRESS', 230, 'Personal'),
  record('CREDIT CARD NUMBER', 61, 'Financial'),
  record('IP ADDRESS', 48, 'IT & Security'),
  record('SHA KEY', 9, 'IT & Security'),
  record('HASHED SECRET KEY', 4, 'IT & Security'),
];

const DOCUMENT_CLASSES: SaasRecordRow[] = [
  record('Incident Reports', 640, 'IT & Technical'),
  record('Telecom Reports', 85, 'Telecom Regulatory'),
  record('General Contracts & Agreements', 64, 'Legal'),
  record('Invoices & Receipts', 27, 'Financial'),
  record('KYC Documents', 9, 'Identity & Personal'),
  record('Budgets', 8, 'Financial'),
  record('Procurement Documents', 6, 'Legal'),
  record('Sales Proposals', 5, 'Legal'),
  record('Software Licenses', 3, 'IT & Technical'),
  record('System Documentation', 2, 'IT & Technical'),
  record('Request for Quotation', 1, 'Legal'),
];

const SUMMARY: SaasScanSummary = {
  supported: { count: 9_020, sizeBytes: 15_728_640 },
  unsupported: { count: 180, sizeBytes: 524_288 },
  fileTypes: [
    { extension: 'msg', label: 'Email', count: 8_400 },
    { extension: 'png', label: 'Images', count: 600 },
    { extension: 'pdf', label: 'PDF', count: 20 },
  ],
  properties: { owner: 'pat@example.com', datastoreSizeBytes: 0, createdOn: local(2026, 9, 8, 14, 0), lastScan: local(2026, 9, 11, 22, 9) },
};

/** 24-hex file ids: file n is `f…f<n>` padded. */
const fileId = (n: number) => n.toString(16).padStart(24, 'f');

/** A page of `total` files for the request; file 3 has a vendor id that is not 24 hex. */
function filePage(url: URL, total = 23): SaasFilePage {
  const skip = Number(url.searchParams.get('skip'));
  const limit = Number(url.searchParams.get('limit'));
  const count = Math.max(0, Math.min(limit, total - skip));
  const rows = Array.from({ length: count }, (_, i) => {
    const n = skip + i + 1;
    return { id: n === 3 ? null : fileId(n), name: n === 2 ? 'Re: travel plans' : `report-${n}.pdf`, records: 1_500 - n };
  });
  return { rows, skip, limit, total };
}

type Answer = (url: URL) => unknown;
let answers: Record<string, Answer>;

const httpError = (status: number, code: string) =>
  new Error(`HTTP ${status}: ${JSON.stringify({ code, message: 'synthetic failure' })}`);
const fail = (status: number, code: string): Answer => () => { throw httpError(status, code); };
const pending: Answer = () => new Promise(() => {});

const SAAS_PATH = /^\/api\/dspm\/datastores\/([0-9a-f]{24})\/saas\/([a-z-]+)$/;
const FILE_PATH = /^\/api\/dspm\/saas-files\/([0-9a-f]{24})\/([a-z]+)$/;

const urls = () => get.mock.calls.map(([raw]) => new URL(String(raw), 'http://localhost'));
const saasRequests = (endpoint: string) => urls().filter(url => SAAS_PATH.exec(url.pathname)?.[2] === endpoint);
const recordRequests = (view: string) => saasRequests('records').filter(url => url.searchParams.get('view') === view);
const lastFiles = () => {
  const files = saasRequests('files');
  return files[files.length - 1];
};

beforeEach(() => {
  answers = {
    detail: () => HEADER,
    records: url => ({
      view: url.searchParams.get('view'),
      rows: url.searchParams.get('view') === 'document_classes' ? DOCUMENT_CLASSES : DATA_ELEMENTS,
    } as SaasRecords),
    files: url => filePage(url),
    'scan-summary': () => SUMMARY,
    'file:overview': () => overview(),
    'file:activity': () => ({ entries: [] }),
    'file:lineage': pending,
    'file:copies': () => copies([copy('copy-1', { fileName: 'report-copy.pdf' })], 1),
  };
  get.mockImplementation(async (raw: string) => {
    const url = new URL(raw, 'http://localhost');
    const saas = SAAS_PATH.exec(url.pathname);
    const file = FILE_PATH.exec(url.pathname);
    const answer = saas && saas[1] === SOURCE_ID ? answers[saas[2]] : file ? answers[`file:${file[2]}`] : undefined;
    if (!answer) throw new Error(`HTTP 404: unexpected ${raw}`);
    return answer(url);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

async function renderDrawer(row: DrawerRow = saasRow()) {
  const onClose = vi.fn();
  const view = render(<SaasDrawer row={row} onClose={onClose} />);
  const drawer = await screen.findByRole('dialog', { name: row.name ?? '' });
  return { ...view, onClose, drawer };
}

const stripValue = (drawer: HTMLElement, label: string) =>
  within(drawer).getByText(label, { selector: 'div' }).nextElementSibling as HTMLElement;
/** The outer Sensitives table (the first table of the panel). */
const recordsTable = (drawer: HTMLElement) => within(drawer).getAllByRole('table')[0];
const outerRows = (table: HTMLElement) =>
  ([...table.querySelectorAll(':scope > tbody > tr')] as HTMLTableRowElement[])
    .filter(row => row.cells.length === 2)
    .map(row => [...row.cells].map(cell => cell.textContent));
/** Waits past the 300 ms search debounce. */
const afterDebounce = () => act(async () => { await new Promise(resolve => setTimeout(resolve, 400)); });

async function expand(user: ReturnType<typeof userEvent.setup>, drawer: HTMLElement, name: string) {
  await user.click(within(drawer).getByRole('button', { name: `Expand ${name}` }));
  return within(drawer).findByRole('searchbox', { name: 'Search files' }).then(box => box.closest('[data-file-list]') as HTMLElement);
}

// ─── tests ───────────────────────────────────────────────────────────────────────────────────────

describe('SaasDrawer header and strip', () => {
  it('shows the title, and the strip from the header resource', async () => {
    const { drawer } = await renderDrawer();
    expect(within(drawer).getByRole('heading', { level: 2, name: 'Pat Example' })).toBeInTheDocument();
    expect(within(drawer).getByText('Gmail', { selector: 'p' })).toBeInTheDocument();
    expect(drawer.querySelector('h2')?.parentElement?.previousElementSibling?.querySelector('[data-icon="logos:google-gmail"]')).not.toBeNull();
    expect(within(drawer).getByRole('button', { name: 'Close' })).toHaveFocus();
    expect(within(drawer).queryByRole('button', { name: 'Start Scan' })).toBeNull();

    expect(await within(drawer).findByText('example-gws')).toBeInTheDocument();
    const assetType = stripValue(drawer, 'Asset Type');
    expect(assetType).toHaveTextContent(/^Gmail$/);
    expect(assetType.querySelector('[data-icon="logos:google-gmail"]')).not.toBeNull();
    expect(stripValue(drawer, 'Source/Account').querySelector('[data-icon="logos:google-drive"]')).not.toBeNull();
    const backfill = stripValue(drawer, 'Backfill Scan');
    expect(backfill).toHaveTextContent(/^Completed$/);
    expect(backfill.querySelector('[data-icon="mdi:check-circle"]')).toHaveClass('text-emerald-500');
    const incremental = stripValue(drawer, 'Incremental Scan');
    expect(incremental).toHaveTextContent(/^Disabled$/);
    expect(incremental.querySelector('[data-icon="mdi:circle-half-full"]')).not.toBeNull();

    expect(within(drawer).getAllByRole('tab').map(tab => tab.textContent)).toEqual(['Sensitives', 'Information']);
    expect(saasRequests('detail')).toHaveLength(1);
    expect(recordRequests('data_elements')).toHaveLength(1);
    expect(recordRequests('document_classes')).toHaveLength(0);
    expect(saasRequests('scan-summary')).toHaveLength(0);
  });

  it('leaves the strip on the row when the header fails, with "-" for both scans', async () => {
    answers.detail = fail(502, 'UPSTREAM_UNAVAILABLE');
    const { drawer } = await renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    await waitFor(() => expect(saasRequests('detail')).toHaveLength(1));
    expect(stripValue(drawer, 'Asset Type')).toHaveTextContent(/^Gmail$/);
    expect(stripValue(drawer, 'Source/Account')).toHaveTextContent(/^example-workspace$/);
    expect(stripValue(drawer, 'Backfill Scan')).toHaveTextContent(/^-$/);
    expect(stripValue(drawer, 'Incremental Scan')).toHaveTextContent(/^-$/);
  });

  it('draws the Google Drive logo for a GOOGLE_WORKSPACE row, in the title and the strip', async () => {
    answers.detail = () => ({ ...HEADER, driveType: 'GOOGLE_WORKSPACE', driveTypeLabel: 'Google Drive' });
    const { drawer } = await renderDrawer(saasRow({ subtype: 'GOOGLE_WORKSPACE', subtypeLabel: 'Google Drive' }));
    expect(within(drawer).getByText('Google Drive', { selector: 'p' })).toBeInTheDocument();
    const title = drawer.querySelector('h2')?.parentElement?.previousElementSibling as HTMLElement;
    expect(title.querySelector('[data-icon="logos:google-drive"]')).not.toBeNull();
    expect(title.querySelector('[data-icon="mdi:google"]')).toBeNull();
    await within(drawer).findByText('example-gws');
    const assetType = stripValue(drawer, 'Asset Type');
    expect(assetType).toHaveTextContent(/^Google Drive$/);
    expect(assetType.querySelector('[data-icon="logos:google-drive"]')).not.toBeNull();
  });
});

describe('SaasDrawer Sensitives', () => {
  it('lists the data elements with their chips, paged at 10, and searches them by name', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    const table = recordsTable(drawer);
    expect(within(table).getAllByRole('columnheader').map(th => th.textContent)).toEqual(['Data Elements', 'Sensitives']);
    expect(outerRows(table)).toEqual([
      ['EMAIL ADDRESS', '3.21KPersonal'],
      ['PERSON NAME', '512Personal'],
      ['ADDRESS', '230Personal'],
      ['CREDIT CARD NUMBER', '61Financial'],
      ['IP ADDRESS', '48IT & Security'],
      ['SHA KEY', '9IT & Security'],
      ['HASHED SECRET KEY', '4IT & Security'],
    ]);
    expect(within(drawer).getByText('Showing 1 - 7 of 7')).toBeInTheDocument();

    const search = within(drawer).getByRole('searchbox', { name: 'Search' });
    await user.type(search, 'address');
    expect(outerRows(table).map(row => row[0])).toEqual(['EMAIL ADDRESS', 'ADDRESS', 'IP ADDRESS']);
    await user.clear(search);
    await user.type(search, 'nothing');
    expect(within(table).getByText('No matches')).toBeInTheDocument();
  });

  it('loads the document classes once, on the first switch, pages them and clears the search', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    await user.type(within(drawer).getByRole('searchbox', { name: 'Search' }), 'email');

    await user.click(within(drawer).getByRole('button', { name: 'Document Classes' }));
    expect(within(drawer).getByRole('button', { name: 'Document Classes' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(drawer).getByRole('searchbox', { name: 'Search' })).toHaveValue('');
    await within(drawer).findByText('Incident Reports');
    const table = recordsTable(drawer);
    expect(within(table).getAllByRole('columnheader').map(th => th.textContent)).toEqual(['Document Classes', 'Sensitives']);
    expect(outerRows(table)).toHaveLength(10);
    expect(outerRows(table)[0]).toEqual(['Incident Reports', '640IT & Technical']);
    expect(within(drawer).getByText('Showing 1 - 10 of 11')).toBeInTheDocument();
    await user.click(within(drawer).getByRole('button', { name: '2' }));
    expect(outerRows(table)).toEqual([['Request for Quotation', '1Legal']]);

    await user.click(within(drawer).getByRole('button', { name: 'Data Elements' }));
    await within(drawer).findByText('EMAIL ADDRESS');
    await user.click(within(drawer).getByRole('button', { name: 'Document Classes' }));
    await within(drawer).findByText('Incident Reports');
    expect(recordRequests('document_classes')).toHaveLength(1);
    expect(recordRequests('data_elements')).toHaveLength(1);
  });

  it('greys a "Financial" document category while a "Financial" sensitive category keeps its colour', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderDrawer();
    const sensitive = (await within(drawer).findByText('CREDIT CARD NUMBER')).closest('tr') as HTMLTableRowElement;
    expect(within(sensitive.cells[1]).getByText('Financial')).toHaveClass('bg-violet-50');

    await user.click(within(drawer).getByRole('button', { name: 'Document Classes' }));
    const documentRow = (await within(drawer).findByText('Invoices & Receipts')).closest('tr') as HTMLTableRowElement;
    const documentCategory = within(documentRow.cells[1]).getByText('Financial');
    expect(documentCategory).not.toHaveClass('bg-violet-50');
    expect(documentCategory).toHaveClass('bg-[rgba(0,0,0,0.04)]');
  });

  it('shows build 1 empty row for an empty view (DA18, review fix R13)', async () => {
    const user = userEvent.setup();
    answers.records = url => ({ view: url.searchParams.get('view'), rows: url.searchParams.get('view') === 'document_classes' ? [] : DATA_ELEMENTS });
    const { drawer } = await renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    await user.click(within(drawer).getByRole('button', { name: 'Document Classes' }));
    expect(await within(drawer).findByText(NOT_SCANNED_TEXT)).toBeInTheDocument();
  });

  it('shows the error with Retry when the records fail, keeping the toggle usable', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    answers.records = url => {
      const view = url.searchParams.get('view');
      if (view === 'data_elements' && (attempt += 1) === 1) throw httpError(502, 'UPSTREAM_UNAVAILABLE');
      return { view, rows: view === 'document_classes' ? DOCUMENT_CLASSES : DATA_ELEMENTS };
    };
    const { drawer } = await renderDrawer();
    const table = recordsTable(drawer);
    expect(await within(table).findByText(UPSTREAM)).toBeInTheDocument();
    expect(within(table).getAllByRole('columnheader').map(th => th.textContent)).toEqual(['Data Elements', 'Sensitives']);

    await user.click(within(drawer).getByRole('button', { name: 'Document Classes' }));
    expect(await within(drawer).findByText('Incident Reports')).toBeInTheDocument();
    await user.click(within(drawer).getByRole('button', { name: 'Data Elements' }));
    await user.click(within(recordsTable(drawer)).getByRole('button', { name: 'Retry' }));
    expect(await within(drawer).findByText('EMAIL ADDRESS')).toBeInTheDocument();
    expect(recordRequests('data_elements')).toHaveLength(2);
  });
});

describe('SaasDrawer file lists', () => {
  it('expands one row at a time into its files, requesting limit=5&skip=0 with the data element', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    const list = await expand(user, drawer, 'EMAIL ADDRESS');
    await within(list).findByText('report-1.pdf');
    expect(lastFiles().search).toBe('?dataElement=EMAIL+ADDRESS&limit=5&skip=0');
    const table = within(list).getByRole('table');
    expect(within(table).getAllByRole('columnheader').slice(0, 2).map(th => th.textContent)).toEqual(['File', 'Records']);
    const first = within(table).getAllByRole('row')[1] as HTMLTableRowElement;
    expect([...first.cells].slice(0, 2).map(cell => cell.textContent)).toEqual(['report-1.pdfPDF', '1.5K']);
    expect(within(first.cells[1]).getByText('1.5K')).toHaveAttribute('title', '1,499');
    // An e-mail subject has no extension label.
    expect((within(table).getAllByRole('row')[2] as HTMLTableRowElement).cells[0].textContent).toBe('Re: travel plans');
    expect(within(list).getByText('Showing 1 - 5 of 23')).toBeInTheDocument();
    expect(within(list).getByRole('button', { name: '5' })).toBeInTheDocument();

    await user.click(within(drawer).getByRole('button', { name: 'Expand PERSON NAME' }));
    expect(within(drawer).getByRole('button', { name: 'Collapse PERSON NAME' })).toBeInTheDocument();
    expect(within(drawer).getByRole('button', { name: 'Expand EMAIL ADDRESS' })).toBeInTheDocument();
    expect(within(drawer).getAllByRole('searchbox', { name: 'Search files' })).toHaveLength(1);
    await waitFor(() => expect(lastFiles().searchParams.get('dataElement')).toBe('PERSON NAME'));
  });

  it('requests the files of a document class by its label', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    await user.click(within(drawer).getByRole('button', { name: 'Document Classes' }));
    await within(drawer).findByText('Incident Reports');
    const list = await expand(user, drawer, 'Incident Reports');
    await within(list).findByText('report-1.pdf');
    expect(lastFiles().search).toBe('?documentClass=Incident+Reports&limit=5&skip=0');
  });

  it('pages and searches the nested files on the server', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    const list = await expand(user, drawer, 'EMAIL ADDRESS');
    await within(list).findByText('report-1.pdf');

    await user.click(within(list).getByRole('button', { name: '2' }));
    expect(await within(list).findByText('report-6.pdf')).toBeInTheDocument();
    expect(lastFiles().searchParams.get('skip')).toBe('5');
    expect(within(list).getByText('Showing 6 - 10 of 23')).toBeInTheDocument();

    await user.type(within(list).getByRole('searchbox', { name: 'Search files' }), '  report ');
    await afterDebounce();
    await waitFor(() => expect(lastFiles().search).toBe('?dataElement=EMAIL+ADDRESS&q=report&limit=5&skip=0'));
    expect(await within(list).findByText('Showing 1 - 5 of 23')).toBeInTheDocument();

    await user.selectOptions(within(list).getByRole('combobox'), '10');
    await waitFor(() => expect(lastFiles().searchParams.get('limit')).toBe('10'));
    expect(lastFiles().searchParams.get('skip')).toBe('0');
    expect(within(list).getAllByRole('option').map(option => option.textContent)).toEqual(['5', '10', '25', '50']);
  });

  it('collapses the open row on another search or page', async () => {
    const user = userEvent.setup();
    answers.records = url => ({
      view: url.searchParams.get('view'),
      rows: Array.from({ length: 12 }, (_, i) => record(`ENTITY ${String.fromCharCode(65 + i)}`, 100 - i, 'Personal')),
    });
    const { drawer } = await renderDrawer();
    await within(drawer).findByText('ENTITY A');
    await expand(user, drawer, 'ENTITY A');
    await user.type(within(drawer).getByRole('searchbox', { name: 'Search' }), 'entity');
    expect(within(drawer).queryByRole('searchbox', { name: 'Search files' })).toBeNull();

    const list = await expand(user, drawer, 'ENTITY B');
    await within(list).findByText('report-1.pdf');
    const outerFooter = within(drawer).getByText('Showing 1 - 10 of 12').parentElement as HTMLElement;
    await user.click(within(outerFooter).getByRole('button', { name: '2' }));
    expect(within(drawer).queryByRole('searchbox', { name: 'Search files' })).toBeNull();
    expect(within(drawer).getByText('ENTITY K')).toBeInTheDocument();
  });

  it('opens the file drawer from a file row, and closing it returns focus to the row', async () => {
    const user = userEvent.setup();
    const { drawer, onClose } = await renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    const list = await expand(user, drawer, 'EMAIL ADDRESS');
    const row = (await within(list).findByText('report-1.pdf')).closest('tr') as HTMLTableRowElement;
    expect(row).toHaveAttribute('tabindex', '0');
    await user.click(row);

    const fileDrawer = await screen.findByRole('dialog', { name: 'travel-itinerary.pdf' });
    expect(within(fileDrawer).getByRole('tab', { name: 'Sensitives' })).toHaveAttribute('aria-selected', 'true');
    expect(urls().filter(url => FILE_PATH.test(url.pathname)).map(url => url.pathname))
      .toEqual([`/api/dspm/saas-files/${fileId(1)}/overview`]);
    await user.click(within(fileDrawer).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog', { name: 'travel-itinerary.pdf' })).toBeNull();
    expect(row).toHaveFocus();
    expect(onClose).not.toHaveBeenCalled();
    // The SaaS drawer is as it was: the row is still expanded on its page.
    expect(within(list).getByText('report-1.pdf')).toBeInTheDocument();
  });

  it('opens the file drawer on Lineage from the lineage icon, and Escape returns focus to the row', async () => {
    const user = userEvent.setup();
    const { drawer, onClose } = await renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    const list = await expand(user, drawer, 'EMAIL ADDRESS');
    const icon = await within(list).findByRole('button', { name: 'Open lineage of report-4.pdf' });
    expect(icon.querySelector('[data-icon="mdi:graph-outline"]')).not.toBeNull();
    await user.click(icon);

    const fileDrawer = await screen.findByRole('dialog', { name: 'travel-itinerary.pdf' });
    expect(within(fileDrawer).getByRole('tab', { name: 'Lineage' })).toHaveAttribute('aria-selected', 'true');
    await waitFor(() => expect(urls().some(url => url.pathname === `/api/dspm/saas-files/${fileId(4)}/lineage`)).toBe(true));
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'travel-itinerary.pdf' })).toBeNull();
    expect(icon.closest('tr')).toHaveFocus();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('lists a file whose vendor id is not 24 hex, without opening it or a lineage icon', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    const list = await expand(user, drawer, 'EMAIL ADDRESS');
    const row = (await within(list).findByText('report-3.pdf')).closest('tr') as HTMLTableRowElement;
    expect(row).not.toHaveAttribute('tabindex');
    expect(within(row).queryByRole('button')).toBeNull();
    await user.click(row);
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
  });

  it("passes the row's name and drive type to the file drawer", async () => {
    const user = userEvent.setup();
    answers['file:overview'] = () => driveOverview();
    const { drawer } = await renderDrawer(saasRow({ name: 'Sam Lee', subtype: 'GOOGLE_WORKSPACE', subtypeLabel: 'Google Drive' }));
    await within(drawer).findByText('EMAIL ADDRESS');
    const list = await expand(user, drawer, 'EMAIL ADDRESS');
    await user.click((await within(list).findByText('report-1.pdf')).closest('tr') as HTMLTableRowElement);
    const fileDrawer = await screen.findByRole('dialog', { name: 'Offer Letter - Pat Example' });
    expect(within(fileDrawer).getAllByRole('tab').map(tab => tab.textContent)).toContain('File Copies');
    await user.click(within(fileDrawer).getByRole('tab', { name: 'Related Files' }));
    expect(await within(fileDrawer).findByText('Sam Lee', { selector: 'strong' })).toBeInTheDocument();
  });

  it('shows the error with Retry when a file list fails, and Retry loads it', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    answers.files = url => {
      attempt += 1;
      if (attempt === 1) throw httpError(502, 'UPSTREAM_UNAVAILABLE');
      return filePage(url);
    };
    const { drawer } = await renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    const list = await expand(user, drawer, 'EMAIL ADDRESS');
    expect(await within(list).findByText(UPSTREAM)).toBeInTheDocument();
    await user.click(within(list).getByRole('button', { name: 'Retry' }));
    expect(await within(list).findByText('report-1.pdf')).toBeInTheDocument();
    expect(saasRequests('files')).toHaveLength(2);
  });

  it('reads a removed data element in the request wording, without Retry', async () => {
    const user = userEvent.setup();
    answers.files = fail(400, 'INVALID_REQUEST');
    const { drawer } = await renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    const list = await expand(user, drawer, 'EMAIL ADDRESS');
    expect(await within(list).findByText('This request could not be processed.')).toBeInTheDocument();
    expect(within(list).queryByRole('button', { name: 'Retry' })).toBeNull();
  });
});

describe('SaasDrawer Information', () => {
  it('shows the donut, the legend, the file-type chips and the properties', async () => {
    const user = userEvent.setup();
    const { drawer } = await renderDrawer();
    await within(drawer).findByText('EMAIL ADDRESS');
    expect(saasRequests('scan-summary')).toHaveLength(0);
    await user.click(within(drawer).getByRole('tab', { name: 'Information' }));

    const scan = await within(drawer).findByRole('region', { name: 'Scan Summary' });
    expect(scan.querySelector('[data-donut-centre]')).toHaveTextContent(/^9\.2KDiscovered Files15\.5 MB$/);
    expect(scan.querySelector('[data-legend="Supported Files"]')).toHaveTextContent(/^Supported Files9\.02K\(98\.04%\)15 MB$/);
    expect(scan.querySelector('[data-legend="Unsupported Files"]')).toHaveTextContent(/^Unsupported Files180\(1\.96%\)512 KB$/);
    expect(within(scan.querySelector('[data-legend="Unsupported Files"]') as HTMLElement).getByRole('button', { name: 'More information' }))
      .toBeInTheDocument();
    expect([...scan.querySelectorAll('[data-file-type]')].map(chip => chip.textContent)).toEqual(['Email 8,400', 'Images 600', 'PDF 20']);

    const properties = within(drawer).getByRole('region', { name: 'Properties' });
    expect([...properties.querySelectorAll('dl > div')].map(row => row.textContent)).toEqual([
      'OwnerPpat@example.com',
      'Datastore size-',
      'Created On08 Sep 2026',
      'Last Scan11 Sep 2026, 10:09 PM',
    ]);

    await user.click(within(drawer).getByRole('tab', { name: 'Sensitives' }));
    await user.click(within(drawer).getByRole('tab', { name: 'Information' }));
    expect(saasRequests('scan-summary')).toHaveLength(1);
  });

  it('draws an empty ring and "-" properties without data', async () => {
    const user = userEvent.setup();
    answers['scan-summary'] = () => ({
      supported: { count: 0, sizeBytes: 0 }, unsupported: { count: 0, sizeBytes: 0 }, fileTypes: [],
      properties: { owner: null, datastoreSizeBytes: 2_048, createdOn: null, lastScan: null },
    });
    const { drawer } = await renderDrawer();
    await user.click(within(drawer).getByRole('tab', { name: 'Information' }));
    const scan = await within(drawer).findByRole('region', { name: 'Scan Summary' });
    expect(scan.querySelector('[data-donut-centre]')).toHaveTextContent(/^0Discovered Files0 Bytes$/);
    expect(scan.querySelector('[data-legend="Supported Files"]')).toHaveTextContent('(0%)');
    expect(scan.querySelectorAll('svg circle')).toHaveLength(1);
    const properties = within(drawer).getByRole('region', { name: 'Properties' });
    expect([...properties.querySelectorAll('dl > div')].map(row => row.textContent)).toEqual([
      'Owner-', 'Datastore size2 KB', 'Created On-', 'Last Scan-',
    ]);
  });

  it('shows the error with Retry when the scan summary fails', async () => {
    const user = userEvent.setup();
    answers['scan-summary'] = fail(502, 'BAD_UPSTREAM_RESPONSE');
    const { drawer } = await renderDrawer();
    await user.click(within(drawer).getByRole('tab', { name: 'Information' }));
    const panel = within(drawer).getByRole('tabpanel');
    expect(await within(panel).findByText(UPSTREAM)).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
