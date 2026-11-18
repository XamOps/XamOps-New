import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { DspmPage } from '../../types/dspm';
import type { DatastoreRow } from '../../types/dspmDatastores';

vi.mock('../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
vi.mock('../../utils/csvExport', async importOriginal => ({
  ...(await importOriginal<typeof import('../../utils/csvExport')>()),
  downloadCsv: vi.fn(),
}));

import apiClient from '../../utils/apiClient';
import { downloadCsv } from '../../utils/csvExport';
import { DATASTORE_TAB_CONFIG } from '../../components/Dspm/datastores/datastoreTabConfig';
import { useDatastoreExport, type DatastoreExportRequest } from '../useDatastoreExport';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const download = downloadCsv as unknown as ReturnType<typeof vi.fn>;

function row(n: number): DatastoreRow {
  return {
    id: `asset-${n}`, sourceId: null, name: `payments-${n}`, category: 'cloud',
    subtype: 'S3', subtypeLabel: 'S3',
    risk: { score: 3, level: 'MEDIUM', levelLabel: 'Medium' },
    sensitives: { recordCount: n, typeLabels: ['Personal'] },
    source: { provider: 'S3', providerLabel: 'S3', accountLabel: 'example-prod', region: 'eu-west-1' },
    scan: { status: 'CLASSIFIED', statusLabel: 'Classified', displayAt: null },
    agent: null,
  };
}

/** Pages of `total` rows at 100 per page; `totalPages` and the rows of a page can be overridden. */
function pages(total: number, over: { totalPages?: number; rowsOf?: (page: number) => number } = {}) {
  const totalPages = over.totalPages ?? Math.ceil(total / 100);
  return (raw: string): DspmPage<DatastoreRow> => {
    const page = Number(new URL(raw, 'http://localhost').searchParams.get('page'));
    const count = over.rowsOf?.(page) ?? Math.max(0, Math.min(100, total - (page - 1) * 100));
    return {
      data: Array.from({ length: count }, (_, i) => row((page - 1) * 100 + i + 1)),
      page, pageSize: 100, total, totalPages,
    };
  };
}

const REQUEST: DatastoreExportRequest = {
  apiTab: 'cloud',
  slug: 'cloud',
  search: 'pay',
  filterParams: [['provider_exclude', 'EBS']],
  sort: 'name',
  columns: DATASTORE_TAB_CONFIG.cloud.columns,
};

const requested = () => get.mock.calls.map(([raw]) => new URL(String(raw), 'http://localhost'));
const savedCsv = () => String(download.mock.calls[0][1]);
const dataRows = (csv: string) => csv.split('\r\n').length - 1;

/** A promise the test settles by hand. */
function deferred<T>() {
  let resolve: (value: T) => void = () => {};
  let reject: (reason: unknown) => void = () => {};
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

beforeEach(() => {
  get.mockReset();
  download.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('useDatastoreExport', () => {
  it('reads every page with the table\'s tab, search, filters and sort, then saves one CSV', async () => {
    get.mockImplementation(async (raw: string) => pages(254)(raw));
    const { result } = renderHook(() => useDatastoreExport());
    await act(async () => { await result.current.start(REQUEST); });

    expect(requested().map(u => u.pathname)).toEqual(['/api/dspm/datastores', '/api/dspm/datastores', '/api/dspm/datastores']);
    expect(requested().map(u => [...u.searchParams.entries()])).toEqual([1, 2, 3].map(page => [
      ['tab', 'cloud'], ['page', String(page)], ['pageSize', '100'], ['sort', 'name'], ['q', 'pay'], ['provider_exclude', 'EBS'],
    ]));
    expect(download).toHaveBeenCalledTimes(1);
    expect(download.mock.calls[0][0]).toMatch(/^datastores-cloud-\d{4}-\d{2}-\d{2}\.csv$/);
    const csv = savedCsv();
    expect(dataRows(csv)).toBe(254);
    expect(csv.split('\r\n')[0]).toBe(
      'Datastore,Type,Risk Score,Risk Level,Sensitive Records,Sensitive Types,Source / Account,Region,Scan Status,Scan Time',
    );
    expect(csv.split('\r\n')[254]).toBe('payments-254,S3,3.0,Medium,254,Personal,example-prod,eu-west-1,Classified,');
    expect(result.current).toMatchObject({ exporting: false, error: null, capped: null });
  });

  it('sends no q without a search', async () => {
    get.mockImplementation(async (raw: string) => pages(3)(raw));
    const { result } = renderHook(() => useDatastoreExport());
    await act(async () => { await result.current.start({ ...REQUEST, search: null, filterParams: [] }); });
    expect([...requested()[0].searchParams.keys()]).toEqual(['tab', 'page', 'pageSize', 'sort']);
  });

  it('stops at totalPages', async () => {
    get.mockImplementation(async (raw: string) => pages(500, { totalPages: 2 })(raw));
    const { result } = renderHook(() => useDatastoreExport());
    await act(async () => { await result.current.start(REQUEST); });
    expect(get).toHaveBeenCalledTimes(2);
    expect(dataRows(savedCsv())).toBe(200);
  });

  it('stops at an empty page', async () => {
    get.mockImplementation(async (raw: string) => pages(500, { rowsOf: page => (page === 2 ? 0 : 100) })(raw));
    const { result } = renderHook(() => useDatastoreExport());
    await act(async () => { await result.current.start(REQUEST); });
    expect(get).toHaveBeenCalledTimes(2);
    expect(dataRows(savedCsv())).toBe(100);
  });

  it('stops at 10,000 rows and says how many there were', async () => {
    get.mockImplementation(async (raw: string) => pages(25_000)(raw));
    const { result } = renderHook(() => useDatastoreExport());
    await act(async () => { await result.current.start(REQUEST); });
    expect(get).toHaveBeenCalledTimes(100);
    expect(dataRows(savedCsv())).toBe(10_000);
    expect(result.current.capped).toEqual({ exported: 10_000, total: 25_000 });
  });

  it('saves nothing when a page fails, and keeps the error', async () => {
    get.mockImplementation(async (raw: string) => {
      if (new URL(raw, 'http://localhost').searchParams.get('page') === '2') {
        throw new Error('HTTP 502: {"code":"UPSTREAM_UNAVAILABLE","message":"down"}');
      }
      return pages(254)(raw);
    });
    const { result } = renderHook(() => useDatastoreExport());
    await act(async () => { await result.current.start(REQUEST); });
    expect(get).toHaveBeenCalledTimes(2);
    expect(download).not.toHaveBeenCalled();
    expect(result.current.error).toMatchObject({ code: 'UPSTREAM_UNAVAILABLE' });
    expect(result.current.exporting).toBe(false);
  });

  it('reads "exporting" while it runs and ignores a second start', async () => {
    const first = deferred<DspmPage<DatastoreRow>>();
    get.mockImplementation(() => first.promise);
    const { result } = renderHook(() => useDatastoreExport());
    let running: Promise<void> = Promise.resolve();
    act(() => { running = result.current.start(REQUEST); });
    expect(result.current.exporting).toBe(true);
    act(() => { void result.current.start(REQUEST); });
    expect(get).toHaveBeenCalledTimes(1);

    await act(async () => {
      first.resolve(pages(3)('/api/dspm/datastores?page=1'));
      await running;
    });
    expect(result.current.exporting).toBe(false);
    expect(download).toHaveBeenCalledTimes(1);
  });

  it('saves nothing and asks for no further page when unmounted mid-export', async () => {
    const second = deferred<DspmPage<DatastoreRow>>();
    get.mockImplementation(async (raw: string) =>
      (new URL(raw, 'http://localhost').searchParams.get('page') === '2' ? second.promise : pages(300)(raw)));
    const { result, unmount } = renderHook(() => useDatastoreExport());
    let running: Promise<void> = Promise.resolve();
    act(() => { running = result.current.start(REQUEST); });
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2));

    unmount();
    second.resolve(pages(300)('/api/dspm/datastores?page=2'));
    await running;
    expect(get).toHaveBeenCalledTimes(2);
    expect(download).not.toHaveBeenCalled();
  });

  it('clears the error and the cap note when the next export starts, and on dismiss (review fix R12)', async () => {
    get.mockImplementation(async () => { throw new Error('HTTP 502: {"code":"UPSTREAM_UNAVAILABLE","message":"down"}'); });
    const { result } = renderHook(() => useDatastoreExport());
    await act(async () => { await result.current.start(REQUEST); });
    expect(result.current.error).not.toBeNull();
    act(() => result.current.dismiss());
    expect(result.current.error).toBeNull();

    get.mockImplementation(async (raw: string) => pages(10_050)(raw));
    await act(async () => { await result.current.start(REQUEST); });
    expect(result.current.capped).toEqual({ exported: 10_000, total: 10_050 });
    act(() => result.current.dismiss());
    expect(result.current.capped).toBeNull();

    // A failed export, then a capped one, then a new start: both clear before its first request answers.
    get.mockImplementation(async () => { throw new Error('HTTP 502: {"code":"UPSTREAM_UNAVAILABLE","message":"down"}'); });
    await act(async () => { await result.current.start(REQUEST); });
    get.mockImplementation(async (raw: string) => pages(10_050)(raw));
    await act(async () => { await result.current.start(REQUEST); });
    expect(result.current.error).toBeNull();
    expect(result.current.capped).not.toBeNull();

    const pending = deferred<DspmPage<DatastoreRow>>();
    get.mockImplementation(() => pending.promise);
    let running: Promise<void> = Promise.resolve();
    act(() => { running = result.current.start(REQUEST); });
    expect(result.current).toMatchObject({ exporting: true, error: null, capped: null });
    await act(async () => {
      pending.resolve(pages(1)('/api/dspm/datastores?page=1'));
      await running;
    });
  });
});
