import { describe, expect, it } from 'vitest';
import {
  EMPTY_QUERY, FILE_SEARCH_LIMIT, LINEAGE_CATEGORY_IDS, MAX_PAGE, PAGE_SIZE, SEARCH_LIMIT, clickNode, openLevel,
  parseLineageQuery, setPage, setSearch, toSearchParams, type LineageQuery,
} from '../lineageQuery';

const POLICY = '0f8e2c4a-1b3d-4e5f-8a9b-0c1d2e3f4a5b';
const POLICY_2 = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee';
const DATASTORE = 'exposure-0042';
const DATASTORE_2 = 'exposure-0043';
const FILE = '0123456789abcdef01234567';
const FILE_UUID = '11111111-2222-4333-8444-555555555555';

const parse = (text: string) => parseLineageQuery(new URLSearchParams(text));
const write = (query: LineageQuery) => toSearchParams(query).toString();

/** A query with every level chosen, a page and a search on each row. */
const DEEP: LineageQuery = {
  category: 'SENSITIVE_FINDINGS',
  policy: POLICY,
  datastore: DATASTORE,
  file: FILE,
  page: { policy: 1, datastore: 2, file: 3 },
  q: { policy: 'pii', datastore: 'mac', file: 'plan', event: 'created' },
};

describe('constants', () => {
  it('has the console categories in order and the paging limits', () => {
    expect(LINEAGE_CATEGORY_IDS).toEqual([
      'SENSITIVE_FINDINGS', 'EXCESSIVE_EXPOSURE', 'HIGH_VOLUME_ACCESS', 'STALE_DATA', 'RISKY_USER', 'PUBLIC_EXPOSURE',
    ]);
    expect([PAGE_SIZE, SEARCH_LIMIT, FILE_SEARCH_LIMIT, MAX_PAGE]).toEqual([7, 50, 100, 1428]);
    expect(MAX_PAGE * PAGE_SIZE).toBeLessThanOrEqual(10_000);
    expect(write(EMPTY_QUERY)).toBe('');
  });
});

describe('parseLineageQuery', () => {
  it.each([
    ['category=SENSITIVE_FINDINGS', 'category=SENSITIVE_FINDINGS'],
    ['category=sensitive_findings', ''],
    ['category=DATA_RESIDENCY', ''],
    [`category=STALE_DATA&policy=${POLICY.toUpperCase()}`, `category=STALE_DATA&policy=${POLICY}`],
    [`category=STALE_DATA&policy=not-a-uuid`, 'category=STALE_DATA'],
    [`policy=${POLICY}`, ''],
    [`category=RISKY_USER&policy=${POLICY}&datastore=${DATASTORE}`, `category=RISKY_USER&policy=${POLICY}&datastore=${DATASTORE}`],
    [`category=RISKY_USER&policy=${POLICY}&datastore=bad%20id`, `category=RISKY_USER&policy=${POLICY}`],
    [`category=RISKY_USER&policy=${POLICY}&datastore=${'a'.repeat(65)}`, `category=RISKY_USER&policy=${POLICY}`],
    [`category=RISKY_USER&datastore=${DATASTORE}`, 'category=RISKY_USER'],
    [`category=RISKY_USER&policy=${POLICY}&datastore=${DATASTORE}&file=${FILE.toUpperCase()}`,
      `category=RISKY_USER&policy=${POLICY}&datastore=${DATASTORE}&file=${FILE}`],
    [`category=RISKY_USER&policy=${POLICY}&datastore=${DATASTORE}&file=${FILE_UUID}`,
      `category=RISKY_USER&policy=${POLICY}&datastore=${DATASTORE}&file=${FILE_UUID}`],
    [`category=RISKY_USER&policy=${POLICY}&datastore=${DATASTORE}&file=abc`, `category=RISKY_USER&policy=${POLICY}&datastore=${DATASTORE}`],
    [`category=RISKY_USER&policy=${POLICY}&file=${FILE}`, `category=RISKY_USER&policy=${POLICY}`],
    ['category=RISKY_USER&policyPage=2', 'category=RISKY_USER&policyPage=2'],
    ['category=RISKY_USER&policyPage=0', 'category=RISKY_USER'],
    ['category=RISKY_USER&policyPage=1428', 'category=RISKY_USER&policyPage=1428'],
    ['category=RISKY_USER&policyPage=1429', 'category=RISKY_USER'],
    ['category=RISKY_USER&policyPage=12345', 'category=RISKY_USER'],
    ['category=RISKY_USER&policyPage=-1', 'category=RISKY_USER'],
    ['category=RISKY_USER&policyPage=1.5', 'category=RISKY_USER'],
    ['policyPage=2', ''],
    ['category=RISKY_USER&datastorePage=2&filePage=2', 'category=RISKY_USER'],
    [`category=RISKY_USER&policy=${POLICY}&datastorePage=3`, `category=RISKY_USER&policy=${POLICY}&datastorePage=3`],
    [`category=RISKY_USER&policy=${POLICY}&datastore=${DATASTORE}&filePage=4`,
      `category=RISKY_USER&policy=${POLICY}&datastore=${DATASTORE}&filePage=4`],
    ['category=RISKY_USER&policyQ=%20%20pii%09', 'category=RISKY_USER&policyQ=pii'],
    ['category=RISKY_USER&policyQ=%20%20', 'category=RISKY_USER'],
    ['policyQ=pii', ''],
    ['category=RISKY_USER&datastoreQ=mac&fileQ=a&eventQ=b', 'category=RISKY_USER'],
    [`category=RISKY_USER&policy=${POLICY}&datastore=${DATASTORE}&file=${FILE}&eventQ=created`,
      `category=RISKY_USER&policy=${POLICY}&datastore=${DATASTORE}&file=${FILE}&eventQ=created`],
    [`category=RISKY_USER&policy=${POLICY}&datastore=${DATASTORE}&eventQ=created`,
      `category=RISKY_USER&policy=${POLICY}&datastore=${DATASTORE}`],
    ['category=RISKY_USER&category=STALE_DATA', 'category=RISKY_USER'],
    ['category=RISKY_USER&unknown=1', 'category=RISKY_USER'],
  ])('%s → %s', (input, canonical) => {
    expect(write(parse(input))).toBe(canonical);
  });

  it('cuts a search to 200 characters', () => {
    expect(parse(`category=RISKY_USER&policyQ=${'x'.repeat(250)}`).q.policy).toHaveLength(200);
  });

  it('writes the keys in the canonical order, whatever the input order', () => {
    const text = `eventQ=e&fileQ=f&filePage=3&datastoreQ=d&datastorePage=2&policyQ=p&policyPage=1&file=${FILE}`
      + `&datastore=${DATASTORE}&policy=${POLICY}&category=STALE_DATA`;
    expect([...toSearchParams(parse(text)).keys()]).toEqual([
      'category', 'policy', 'datastore', 'file', 'policyPage', 'policyQ', 'datastorePage', 'datastoreQ', 'filePage',
      'fileQ', 'eventQ',
    ]);
    expect(write(parse(write(parse(text))))).toBe(write(parse(text)));
  });
});

describe('openLevel', () => {
  it('is the first level without a choice, null at step 0', () => {
    expect(openLevel(EMPTY_QUERY)).toBeNull();
    expect(openLevel({ ...EMPTY_QUERY, category: 'STALE_DATA' })).toBe('policy');
    expect(openLevel({ ...EMPTY_QUERY, category: 'STALE_DATA', policy: POLICY })).toBe('datastore');
    expect(openLevel({ ...EMPTY_QUERY, category: 'STALE_DATA', policy: POLICY, datastore: DATASTORE })).toBe('file');
    expect(openLevel(DEEP)).toBe('event');
  });
});

describe('clickNode', () => {
  const atDatastore: LineageQuery = { ...DEEP, file: null, q: { ...DEEP.q, event: '' } };
  const atPolicy: LineageQuery = {
    ...atDatastore, datastore: null, page: { ...atDatastore.page, file: 0 }, q: { ...atDatastore.q, file: '' },
  };

  it.each<[string, LineageQuery, 'category' | 'policy' | 'datastore' | 'file', string, string, string[]]>([
    ['category with a policy chosen clears every level below', DEEP, 'category', 'SENSITIVE_FINDINGS',
      'category=SENSITIVE_FINDINGS', ['policy', 'datastore']],
    ['category without a policy does nothing', { ...EMPTY_QUERY, category: 'STALE_DATA', page: { ...EMPTY_QUERY.page, policy: 2 } },
      'category', 'STALE_DATA', 'category=STALE_DATA&policyPage=2', []],
    ['an unchosen policy is chosen; the rows below are cleared', DEEP, 'policy', POLICY_2,
      `category=SENSITIVE_FINDINGS&policy=${POLICY_2}&policyPage=1&policyQ=pii`, ['datastore', 'file']],
    ['the chosen policy with a datastore below stays chosen; below is cleared', atDatastore, 'policy', POLICY,
      `category=SENSITIVE_FINDINGS&policy=${POLICY}&policyPage=1&policyQ=pii`, ['datastore', 'file']],
    ['the deepest chosen policy is unchosen; its page and search stay', atPolicy, 'policy', POLICY,
      'category=SENSITIVE_FINDINGS&policyPage=1&policyQ=pii', ['datastore', 'file']],
    ['an unchosen datastore is chosen; files and events are cleared', DEEP, 'datastore', DATASTORE_2,
      `category=SENSITIVE_FINDINGS&policy=${POLICY}&datastore=${DATASTORE_2}&policyPage=1&policyQ=pii&datastorePage=2&datastoreQ=mac`,
      ['file']],
    ['the chosen datastore with a file below stays chosen', DEEP, 'datastore', DATASTORE,
      `category=SENSITIVE_FINDINGS&policy=${POLICY}&datastore=${DATASTORE}&policyPage=1&policyQ=pii&datastorePage=2&datastoreQ=mac`,
      ['file']],
    ['the deepest chosen datastore is unchosen', atDatastore, 'datastore', DATASTORE,
      `category=SENSITIVE_FINDINGS&policy=${POLICY}&policyPage=1&policyQ=pii&datastorePage=2&datastoreQ=mac`,
      ['file']],
    ['the deepest chosen file is unchosen; its page and search stay, the event search goes', DEEP, 'file', FILE,
      `category=SENSITIVE_FINDINGS&policy=${POLICY}&datastore=${DATASTORE}&policyPage=1&policyQ=pii&datastorePage=2&datastoreQ=mac&filePage=3&fileQ=plan`,
      []],
    ['another file is chosen; the event search goes', DEEP, 'file', FILE_UUID,
      `category=SENSITIVE_FINDINGS&policy=${POLICY}&datastore=${DATASTORE}&file=${FILE_UUID}&policyPage=1&policyQ=pii&datastorePage=2&datastoreQ=mac&filePage=3&fileQ=plan`,
      []],
  ])('%s', (_name, query, level, id, url, cleared) => {
    const t = clickNode(query, level, id);
    expect(write(t.query)).toBe(url);
    expect(t.cleared).toEqual(cleared);
  });

  it('never changes the query it is given', () => {
    const before = JSON.stringify(DEEP);
    clickNode(DEEP, 'category', 'SENSITIVE_FINDINGS');
    clickNode(DEEP, 'policy', POLICY_2);
    expect(JSON.stringify(DEEP)).toBe(before);
  });
});

describe('setPage and setSearch', () => {
  it('clamps a page to 0..MAX_PAGE', () => {
    expect(setPage(DEEP, 'policy', 4).page.policy).toBe(4);
    expect(setPage(DEEP, 'policy', -1).page.policy).toBe(0);
    expect(setPage(DEEP, 'datastore', 5000).page.datastore).toBe(MAX_PAGE);
  });

  it('normalises the search and resets the row page; the event search has no page', () => {
    const next = setSearch(DEEP, 'policy', '  new\ttext ');
    expect(next.q.policy).toBe('new text');
    expect(next.page.policy).toBe(0);
    expect(next.page.datastore).toBe(2);
    expect(setSearch(DEEP, 'file', '   ').q.file).toBe('');
    expect(setSearch(DEEP, 'event', 'x'.repeat(300)).q.event).toHaveLength(200);
    expect(setSearch(DEEP, 'event', 'shared').page).toEqual(DEEP.page);
  });
});
