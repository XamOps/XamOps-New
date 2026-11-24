import { describe, expect, it } from 'vitest';
import {
  DATASTORE_ID, EMPTY_PUBLIC_QUERY, clickPublicNode, datastoreKind, fileIdOk, parsePublicQuery, publicOpenLevel,
  setPublicPage, setPublicSearch, toPublicSearchParams, type PublicLineageQuery,
} from '../publicLineageQuery';
import { MAX_PAGE } from '../lineageQuery';

const EP = `ep-${'a1'.repeat(12)}`;
const DR = `dr-${'b2'.repeat(12)}-Person_01-x`;
const UUID = '0f8e2c4a-1b3d-4e5f-8a9b-0c1d2e3f4a5b';
const DOC = '1AbC-dEf_ghIJklmnOPqrstUVwxyz0123';

const parse = (query: string) => parsePublicQuery(new URLSearchParams(query));
const write = (query: PublicLineageQuery) => toPublicSearchParams(query).toString();
const roundTrip = (query: string) => write(parse(query));

const query = (over: Partial<Omit<PublicLineageQuery, 'page' | 'q'>> & {
  page?: Partial<PublicLineageQuery['page']>; q?: Partial<PublicLineageQuery['q']>;
} = {}): PublicLineageQuery => ({
  ...EMPTY_PUBLIC_QUERY,
  ...over,
  page: { ...EMPTY_PUBLIC_QUERY.page, ...over.page },
  q: { ...EMPTY_PUBLIC_QUERY.q, ...over.q },
});

describe('publicLineageQuery, ids', () => {
  it('knows both datastore id formats and their kind', () => {
    expect(DATASTORE_ID.test(EP)).toBe(true);
    expect(DATASTORE_ID.test(DR)).toBe(true);
    expect(DATASTORE_ID.test(`ep-${'a'.repeat(23)}`)).toBe(false);
    expect(DATASTORE_ID.test(`dr-${'b'.repeat(24)}-${'r'.repeat(65)}`)).toBe(false);
    expect(datastoreKind(EP)).toBe('ENDPOINT');
    expect(datastoreKind(DR)).toBe('DRIVE');
  });

  it('accepts a file id by the datastore kind', () => {
    expect(fileIdOk(EP, UUID.toUpperCase())).toBe(UUID);
    expect(fileIdOk(EP, DOC)).toBeNull();
    expect(fileIdOk(DR, DOC)).toBe(DOC);
    expect(fileIdOk(DR, UUID)).toBeNull();
    expect(fileIdOk(DR, 'a'.repeat(129))).toBeNull();
    expect(fileIdOk(DR, 'has space')).toBeNull();
  });
});

describe('parsePublicQuery', () => {
  it('reads every key under its parent', () => {
    const q = parse(`datastore=${EP}&file=${UUID}&datastorePage=2&datastoreQ=mac&filePage=3&fileQ=rep&eventQ=mod`);
    expect(q).toEqual({
      datastore: EP, file: UUID, page: { datastore: 2, file: 3 }, q: { datastore: 'mac', file: 'rep', event: 'mod' },
    });
  });

  it('lower-cases the hex parts and keeps a resource and doc id as sent', () => {
    expect(parse(`datastore=ep-${'A1'.repeat(12)}`).datastore).toBe(EP);
    expect(parse(`datastore=dr-${'B2'.repeat(12)}-Person_01-x&file=${DOC}`)).toMatchObject({ datastore: DR, file: DOC });
    expect(parse(`datastore=${EP}&file=${UUID.toUpperCase()}`).file).toBe(UUID);
  });

  it('drops a cross-kind file, children without their parent, and build 4\'s keys', () => {
    expect(parse(`datastore=${DR}&file=${UUID}`).file).toBeNull();
    expect(parse(`datastore=${EP}&file=${DOC}`).file).toBeNull();
    expect(parse(`file=${UUID}&filePage=2&fileQ=x&eventQ=y`)).toEqual(EMPTY_PUBLIC_QUERY);
    expect(parse(`datastore=${EP}&eventQ=y`).q.event).toBe('');
    expect(roundTrip(`category=SENSITIVE_FINDINGS&policy=${UUID}&datastore=exposure-1&policyPage=2`)).toBe('');
  });

  it('keeps the datastore row\'s page and search without a choice, within the page rules', () => {
    expect(parse('datastorePage=4&datastoreQ=%20mac%20').page.datastore).toBe(4);
    expect(parse('datastoreQ=%20mac%20').q.datastore).toBe('mac');
    expect(parse(`datastorePage=${MAX_PAGE + 1}`).page.datastore).toBe(0);
    expect(parse('datastorePage=-1').page.datastore).toBe(0);
    expect(parse('datastorePage=12345').page.datastore).toBe(0);
    expect(parse(`datastoreQ=${'x'.repeat(250)}`).q.datastore).toHaveLength(200);
  });
});

describe('toPublicSearchParams', () => {
  it('writes the canonical order without zero pages or empty searches', () => {
    const q = query({ datastore: EP, file: UUID, page: { datastore: 1, file: 2 }, q: { datastore: 'a', file: 'b', event: 'c' } });
    expect(write(q)).toBe(`datastore=${EP}&file=${UUID}&datastorePage=1&datastoreQ=a&filePage=2&fileQ=b&eventQ=c`);
    expect(write(EMPTY_PUBLIC_QUERY)).toBe('');
    expect(roundTrip(`eventQ=c&file=${UUID}&datastore=${EP}&filePage=0`)).toBe(`datastore=${EP}&file=${UUID}&eventQ=c`);
  });
});

describe('publicOpenLevel', () => {
  it('is the first level without a choice', () => {
    expect(publicOpenLevel(EMPTY_PUBLIC_QUERY)).toBe('datastore');
    expect(publicOpenLevel(query({ datastore: EP }))).toBe('file');
    expect(publicOpenLevel(query({ datastore: EP, file: UUID }))).toBe('event');
  });
});

describe('clickPublicNode', () => {
  const deep = query({
    datastore: EP, file: UUID, page: { datastore: 1, file: 2 }, q: { datastore: 'a', file: 'b', event: 'c' },
  });
  const OTHER_EP = `ep-${'c3'.repeat(12)}`;
  const OTHER_FILE = '11111111-2222-4333-8444-555555555555';

  it.each<[string, PublicLineageQuery, 'root' | 'datastore' | 'file', string, PublicLineageQuery]>([
    ['root with a datastore: everything cleared', deep, 'root', 'root', EMPTY_PUBLIC_QUERY],
    ['root without a datastore: unchanged', query({ page: { datastore: 3 }, q: { datastore: 'a' } }), 'root', 'root',
      query({ page: { datastore: 3 }, q: { datastore: 'a' } })],
    ['an unchosen datastore: chosen, below cleared', deep, 'datastore', OTHER_EP,
      query({ datastore: OTHER_EP, page: { datastore: 1 }, q: { datastore: 'a' } })],
    ['the chosen datastore with a file: kept, below cleared', deep, 'datastore', EP,
      query({ datastore: EP, page: { datastore: 1 }, q: { datastore: 'a' } })],
    ['the deepest chosen datastore: unchosen, its row kept', query({ datastore: EP, page: { datastore: 1, file: 2 }, q: { datastore: 'a', file: 'b' } }),
      'datastore', EP, query({ page: { datastore: 1 }, q: { datastore: 'a' } })],
    ['an unchosen file: chosen, the event search cleared', deep, 'file', OTHER_FILE,
      query({ ...deep, file: OTHER_FILE, q: { ...deep.q, event: '' } })],
    ['the deepest chosen file: unchosen, its row kept', deep, 'file', UUID,
      query({ ...deep, file: null, q: { ...deep.q, event: '' } })],
  ])('%s', (_, from, level, id, expected) => {
    expect(clickPublicNode(from, level, id)).toEqual(expected);
  });
});

describe('setPublicPage and setPublicSearch', () => {
  it('clamps the page to 0..MAX_PAGE', () => {
    expect(setPublicPage(EMPTY_PUBLIC_QUERY, 'datastore', -1).page.datastore).toBe(0);
    expect(setPublicPage(EMPTY_PUBLIC_QUERY, 'datastore', MAX_PAGE + 5).page.datastore).toBe(MAX_PAGE);
    expect(setPublicPage(query({ datastore: EP }), 'file', 2).page).toEqual({ datastore: 0, file: 2 });
  });

  it('normalises the search and resets the row\'s page, not on the event level', () => {
    const from = query({ datastore: EP, file: UUID, page: { datastore: 2, file: 3 } });
    expect(setPublicSearch(from, 'datastore', '  mac\u0007 ')).toMatchObject({ q: { datastore: 'mac' }, page: { datastore: 0, file: 3 } });
    expect(setPublicSearch(from, 'file', 'rep').page).toEqual({ datastore: 2, file: 0 });
    expect(setPublicSearch(from, 'event', 'x')).toMatchObject({ q: { event: 'x' }, page: { datastore: 2, file: 3 } });
  });
});
