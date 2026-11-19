import { describe, expect, it } from 'vitest';
import type { DatastoreRow } from '../../../../types/dspmDatastores';
import type { AffectedDatastore, MisconfigurationRow } from '../../../../types/dspmMisconfigurations';
import { formatListDate } from '../../datastores/datastoreFormat';
import {
  ACCOUNT, AFFECTED_COLUMNS, CLOUD_ACCOUNT, FRAMEWORK, MISCONFIGURATION_COLUMNS, NO_FRAMEWORK, SEVERITY, SOURCE,
  affectedAccountOptions, affectedCsv, affectedFileName, dimensionLabel, filterAffected, filterMisconfigurations,
  groupMisconfigurations, groupSizes, isOpenableDatastore, labelMemoryOf, misconfigurationCsv,
  misconfigurationFilterOptions, misconfigurationsFileName, severityRank, sourceLabel,
} from '../misconfigurationModel';

function policy(over: Partial<MisconfigurationRow> & Pick<MisconfigurationRow, 'policyId' | 'title'>): MisconfigurationRow {
  return {
    source: 'MATTERS', severity: 'MEDIUM', severityLabel: 'Medium', affectedResources: 1, sensitiveRecords: 0,
    labels: [], frameworks: [], cloudAccounts: [],
    ...over,
  };
}

const PUBLIC = policy({
  policyId: 'p-00000000000000a1', title: 'Bucket allows public read', source: 'UPWIND', severity: 'HIGH',
  severityLabel: 'High', affectedResources: 3, sensitiveRecords: 4_700, labels: ['Personal', 'IT & Security'],
  frameworks: ['SOC2', 'NIST'], cloudAccounts: ['example-prod'],
});
const LOGGING = policy({
  policyId: 'p-00000000000000b2', title: 'Access logging is off', affectedResources: 5, sensitiveRecords: 12,
  labels: ['Personal'], frameworks: ['NIST'], cloudAccounts: ['example-prod', 'example-stg'],
});
const BACKUP = policy({ policyId: 'backup_rule', title: 'Backups are not encrypted', source: 'MATTERS' });
const ROWS = [PUBLIC, LOGGING, BACKUP];

function datastore(over: Partial<DatastoreRow> & Pick<DatastoreRow, 'name'>): DatastoreRow {
  return {
    id: null, sourceId: null, category: 'cloud', subtype: 'S3', subtypeLabel: 'S3',
    risk: { score: null, level: null, levelLabel: null },
    sensitives: { recordCount: 0, typeLabels: [] },
    source: { provider: 'S3', providerLabel: 'S3', accountLabel: null, region: null },
    scan: { status: 'CLASSIFIED', statusLabel: 'Classified', displayAt: null },
    agent: null,
    ...over,
  };
}

const AFFECTED: AffectedDatastore[] = [
  {
    datastore: datastore({
      name: 'orders-archive', sourceId: '0123456789abcdef01234567',
      sensitives: { recordCount: 1_559, typeLabels: ['Personal'] },
      source: { provider: 'S3', providerLabel: 'S3', accountLabel: 'example-prod', region: 'eu-west-1' },
    }),
    lastScannedAt: 1_790_000_000,
  },
  {
    datastore: datastore({
      name: 'ledger_db', category: 'on_prem', subtype: 'SELF_HOSTED_POSTGRES', subtypeLabel: 'PostgreSQL',
      source: { provider: 'SELF_HOSTED_POSTGRES', providerLabel: 'PostgreSQL', accountLabel: 'ledger_db', region: null },
    }),
    lastScannedAt: null,
  },
  {
    datastore: datastore({
      name: 'exports-stg', sourceId: 'abcdefabcdefabcdefabcdef',
      source: { provider: 'S3', providerLabel: 'S3', accountLabel: 'example-prod', region: null },
    }),
    lastScannedAt: null,
  },
];

describe('severity and labels', () => {
  it('ranks Critical > High > Medium > Low > other', () => {
    expect(['LOW', null, 'CRITICAL', 'MEDIUM', 'HIGH', 'INFO'].map(severityRank)).toEqual([1, 0, 4, 2, 3, 0]);
  });

  it('names the dimensions and the sources', () => {
    expect([SEVERITY, SOURCE, FRAMEWORK, CLOUD_ACCOUNT].map(dimensionLabel))
      .toEqual(['Severity', 'Source', 'Framework', 'Cloud Account']);
    expect(['UPWIND', 'MATTERS', 'OTHER_VENDOR'].map(sourceLabel)).toEqual(['Upwind', 'Matters', 'OTHER_VENDOR']);
  });
});

describe('misconfigurationFilterOptions', () => {
  const options = misconfigurationFilterOptions(ROWS);
  const dimension = (key: string) => options.dimensions.find(d => d.key === key)!;

  it('lists severities highest first and the other dimensions A to Z, counting policies', () => {
    expect(options.dimensions.map(d => d.key)).toEqual([SEVERITY, SOURCE, FRAMEWORK, CLOUD_ACCOUNT]);
    expect(dimension(SEVERITY).options).toEqual([
      { value: 'HIGH', label: 'High', count: 1 }, { value: 'MEDIUM', label: 'Medium', count: 2 },
    ]);
    expect(dimension(SOURCE).options).toEqual([
      { value: 'MATTERS', label: 'Matters', count: 2 }, { value: 'UPWIND', label: 'Upwind', count: 1 },
    ]);
    expect(dimension(FRAMEWORK).options).toEqual([
      { value: 'NIST', label: 'NIST', count: 2 }, { value: 'SOC2', label: 'SOC2', count: 1 },
    ]);
    expect(dimension(CLOUD_ACCOUNT).options).toEqual([
      { value: 'example-prod', label: 'example-prod', count: 2 }, { value: 'example-stg', label: 'example-stg', count: 1 },
    ]);
    expect(options.dimensions.every(d => !d.hasMore)).toBe(true);
  });

  it('remembers every label by dimension', () => {
    expect(labelMemoryOf(options).get(SOURCE)?.get('UPWIND')).toBe('Upwind');
  });
});

describe('filterMisconfigurations', () => {
  const titles = (rows: MisconfigurationRow[]) => rows.map(row => row.title);
  const include = (values: string[]) => ({ mode: 'include' as const, values });

  it('searches titles case-insensitively', () => {
    expect(titles(filterMisconfigurations(ROWS, '  LOGGING ', {}))).toEqual(['Access logging is off']);
    expect(filterMisconfigurations(ROWS, '', {})).toEqual(ROWS);
  });

  it.each([
    [SEVERITY, ['HIGH'], ['Bucket allows public read']],
    [SOURCE, ['MATTERS'], ['Access logging is off', 'Backups are not encrypted']],
    [FRAMEWORK, ['NIST'], ['Bucket allows public read', 'Access logging is off']],
    [CLOUD_ACCOUNT, ['example-stg'], ['Access logging is off']],
  ])('filters %s by any selected value', (dimension, values, expected) => {
    expect(titles(filterMisconfigurations(ROWS, '', { [dimension]: include(values) }))).toEqual(expected);
  });

  it('combines dimensions with AND and ignores an empty selection', () => {
    const filters = { [FRAMEWORK]: include(['NIST']), [SOURCE]: include(['MATTERS']), [SEVERITY]: include([]) };
    expect(titles(filterMisconfigurations(ROWS, '', filters))).toEqual(['Access logging is off']);
    expect(filterMisconfigurations(ROWS, 'bucket', filters)).toEqual([]);
  });
});

describe('groupMisconfigurations', () => {
  it('keeps the rows ungrouped by default', () => {
    expect(groupMisconfigurations(ROWS, 'none')).toEqual(ROWS.map(row => ({ row, groupKey: null, groupLabel: null })));
  });

  it('groups by severity, highest first, rows in order within each', () => {
    const items = groupMisconfigurations([LOGGING, PUBLIC, BACKUP], 'severity');
    expect(items.map(item => [item.groupLabel, item.row.title])).toEqual([
      ['High', 'Bucket allows public read'], ['Medium', 'Access logging is off'], ['Medium', 'Backups are not encrypted'],
    ]);
    expect(groupSizes(items)).toEqual(new Map([['HIGH', 1], ['MEDIUM', 2]]));
  });

  it('puts a policy under each of its frameworks and those without one last under "-"', () => {
    const items = groupMisconfigurations(ROWS, 'framework');
    expect(items.map(item => [item.groupKey, item.row.title])).toEqual([
      ['NIST', 'Bucket allows public read'], ['NIST', 'Access logging is off'],
      ['SOC2', 'Bucket allows public read'], [NO_FRAMEWORK, 'Backups are not encrypted'],
    ]);
  });
});

describe('CSV', () => {
  it('writes the given columns of the rows with joined labels and raw numbers', () => {
    const columns = MISCONFIGURATION_COLUMNS.filter(c => c.id !== 'severity');
    expect(misconfigurationCsv([PUBLIC, BACKUP], columns).split('\r\n')).toEqual([
      'Misconfiguration,Affected Resources,Sensitives,Labels,Frameworks',
      'Bucket allows public read,3,4700,"Personal, IT & Security","SOC2, NIST"',
      'Backups are not encrypted,1,0,,',
    ]);
  });

  it('writes the affected datastores without the Jira column', () => {
    const lines = affectedCsv(AFFECTED.slice(0, 2), AFFECTED_COLUMNS).split('\r\n');
    expect(lines[0]).toBe('Affected Datastore,Datastore Type,Account Name,Sensitives,Labels,Last scanned time');
    expect(lines[1]).toBe(`orders-archive,S3,example-prod,1559,Personal,"${formatListDate(1_790_000_000)}"`);
    expect(lines[2]).toBe('ledger_db,PostgreSQL,ledger_db,0,,');
  });

  it('names the files by local date', () => {
    const now = new Date(2026, 10, 9, 23, 30);
    expect(misconfigurationsFileName(now)).toBe('misconfigurations-2026-11-09.csv');
    expect(affectedFileName('p-00000000000000a1', now)).toBe('misconfiguration-p-00000000000000a1-affected-datastores-2026-11-09.csv');
  });
});

describe('Affected Datastores', () => {
  it('offers the account labels with their datastore counts', () => {
    expect(affectedAccountOptions(AFFECTED)).toEqual([
      { value: 'example-prod', label: 'example-prod', count: 2 }, { value: 'ledger_db', label: 'ledger_db', count: 1 },
    ]);
  });

  it('filters by name and account', () => {
    const names = (items: AffectedDatastore[]) => items.map(item => item.datastore.name);
    expect(names(filterAffected(AFFECTED, 'EXPORTS', {}))).toEqual(['exports-stg']);
    expect(names(filterAffected(AFFECTED, '', { [ACCOUNT]: { mode: 'include', values: ['example-prod'] } })))
      .toEqual(['orders-archive', 'exports-stg']);
  });

  it('opens only rows with a 24-hex source id', () => {
    expect(AFFECTED.map(item => isOpenableDatastore(item.datastore))).toEqual([true, false, true]);
  });
});
