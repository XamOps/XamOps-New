import { describe, expect, it } from 'vitest';
import type { DatastoreRow } from '../../../../types/dspmDatastores';
import { DATASTORE_TAB_CONFIG, type ColumnDef, type ColumnId } from '../datastoreTabConfig';
import {
  EXPORT_MAX_ROWS, EXPORT_PAGE_SIZE, buildDatastoreCsv, exportFields, exportFileName,
} from '../datastoreExport';

function row(over: Partial<DatastoreRow> = {}): DatastoreRow {
  return {
    id: 'asset-1', sourceId: null, name: 'orders-archive', category: 'cloud',
    subtype: 'S3', subtypeLabel: 'S3',
    risk: { score: 8.44, level: 'CRITICAL', levelLabel: 'Critical' },
    sensitives: { recordCount: 1_559, typeLabels: ['Personal', 'Financial'] },
    source: { provider: 'S3', providerLabel: 'S3', accountLabel: 'example-prod', region: 'eu-west-1' },
    scan: { status: 'CLASSIFIED', statusLabel: 'Classified', displayAt: null },
    agent: null,
    ...over,
  };
}

const CLOUD_COLUMNS = DATASTORE_TAB_CONFIG.cloud.columns;
const ENDPOINT_COLUMNS = DATASTORE_TAB_CONFIG.endpoint.columns;
const column = (columns: ColumnDef[], id: ColumnId) => columns.find(c => c.id === id) as ColumnDef;

/** "12 Sep 2026, 8:51 PM" in any time zone: built from local parts. */
const SEP_12_2051 = new Date(2026, 8, 12, 20, 51).getTime() / 1000;

const lines = (csv: string) => csv.split('\r\n');

describe('exportFields', () => {
  it.each<[ColumnId, string[]]>([
    ['name', ['Datastore', 'Type']],
    ['risk', ['Risk Score', 'Risk Level']],
    ['sensitiveData', ['Sensitive Records', 'Sensitive Types']],
    ['sourceAccount', ['Source / Account', 'Region']],
    ['scanStatus', ['Scan Status', 'Scan Time']],
  ])('splits the %s column into %j', (id, headers) => {
    expect(exportFields(column(CLOUD_COLUMNS, id), 'cloud').map(f => f.header)).toEqual(headers);
  });

  it.each<[ColumnId, string[]]>([
    ['name', ['Device', 'OS']],
    ['serialNumber', ['Serial Number']],
    ['agentStatus', ['Agent Status']],
    ['agentVersion', ['Agent Version']],
  ])('gives the Endpoint %s column %j', (id, headers) => {
    expect(exportFields(column(ENDPOINT_COLUMNS, id), 'endpoint').map(f => f.header)).toEqual(headers);
  });

  it('reads each part from the row, exact and absolute', () => {
    const values = (id: ColumnId, r: DatastoreRow, columns = CLOUD_COLUMNS, slug: 'cloud' | 'endpoint' = 'cloud') =>
      exportFields(column(columns, id), slug).map(f => f.value(r));
    const cloud = row({ scan: { status: 'CLASSIFIED', statusLabel: 'Classified', displayAt: SEP_12_2051 } });
    expect(values('name', cloud)).toEqual(['orders-archive', 'S3']);
    expect(values('risk', cloud)).toEqual(['8.4', 'Critical']);
    expect(values('sensitiveData', cloud)).toEqual([1_559, 'Personal, Financial']);
    expect(values('sourceAccount', cloud)).toEqual(['example-prod', 'eu-west-1']);
    expect(values('scanStatus', cloud)).toEqual(['Classified', '12 Sep 2026, 8:51 PM']);

    const device = row({
      category: 'endpoint', name: 'build-laptop-07', subtype: 'MAC', subtypeLabel: 'macOS',
      agent: { serialNumber: 'SN-TEST-0007', status: 'ONLINE', statusLabel: null, version: '9.9.1' },
    });
    expect(values('name', device, ENDPOINT_COLUMNS, 'endpoint')).toEqual(['build-laptop-07', 'macOS']);
    expect(values('serialNumber', device, ENDPOINT_COLUMNS, 'endpoint')).toEqual(['SN-TEST-0007']);
    expect(values('agentStatus', device, ENDPOINT_COLUMNS, 'endpoint')).toEqual(['ONLINE']);   // no label: the status
    expect(values('agentVersion', device, ENDPOINT_COLUMNS, 'endpoint')).toEqual(['9.9.1']);
  });
});

describe('buildDatastoreCsv', () => {
  it('writes ten CSV columns for the five default columns, and nine for the six Endpoint ones', () => {
    expect(lines(buildDatastoreCsv([row()], CLOUD_COLUMNS, 'cloud'))[0]).toBe(
      'Datastore,Type,Risk Score,Risk Level,Sensitive Records,Sensitive Types,Source / Account,Region,Scan Status,Scan Time',
    );
    expect(lines(buildDatastoreCsv([], ENDPOINT_COLUMNS, 'endpoint'))[0]).toBe(
      'Device,OS,Risk Score,Risk Level,Sensitive Records,Sensitive Types,Serial Number,Agent Status,Agent Version',
    );
  });

  it('follows the visible columns in the order given', () => {
    const visible = CLOUD_COLUMNS.filter(c => c.id !== 'sourceAccount');
    expect(lines(buildDatastoreCsv([row()], visible, 'all'))[0]).toBe(
      'Datastore,Type,Risk Score,Risk Level,Sensitive Records,Sensitive Types,Scan Status,Scan Time',
    );
    const reordered = [column(CLOUD_COLUMNS, 'scanStatus'), column(CLOUD_COLUMNS, 'name')];
    expect(lines(buildDatastoreCsv([row()], reordered, 'cloud'))[0]).toBe('Scan Status,Scan Time,Datastore,Type');
  });

  it('writes one row per datastore: exact numbers, a one-decimal score, quoted lists, absolute times', () => {
    const csv = buildDatastoreCsv([
      row({ risk: { score: 3, level: 'MEDIUM', levelLabel: 'Medium' }, scan: { status: 'CLASSIFIED', statusLabel: 'Classified', displayAt: SEP_12_2051 } }),
    ], CLOUD_COLUMNS, 'cloud');
    expect(lines(csv)).toHaveLength(2);
    expect(lines(csv)[1]).toBe(
      'orders-archive,S3,3.0,Medium,1559,"Personal, Financial",example-prod,eu-west-1,Classified,"12 Sep 2026, 8:51 PM"',
    );
  });

  it('leaves missing values empty: null fields, no score, a Not Scanned count, no scan time', () => {
    const empty = row({
      name: null, subtypeLabel: null,
      risk: { score: null, level: null, levelLabel: null },
      sensitives: { recordCount: 0, typeLabels: [] },
      source: { provider: null, providerLabel: null, accountLabel: null, region: null },
      scan: { status: 'NOT_SCANNED', statusLabel: 'Not Scanned', displayAt: null },
    });
    expect(lines(buildDatastoreCsv([empty], CLOUD_COLUMNS, 'cloud'))[1]).toBe(',,,,,,,,Not Scanned,');

    const failed = row({ sensitives: { recordCount: 0, typeLabels: [] }, scan: { status: 'FAILED', statusLabel: 'Failed', displayAt: null } });
    expect(exportFields(column(CLOUD_COLUMNS, 'sensitiveData'), 'cloud')[0].value(failed)).toBe(0);

    const device = row({ category: 'endpoint', agent: null });
    expect(lines(buildDatastoreCsv([device], ENDPOINT_COLUMNS.slice(3), 'endpoint'))[1]).toBe(',,');
  });

  it('guards formula-like text and quotes commas and quotes, through toCsv', () => {
    const csv = buildDatastoreCsv([
      row({ name: '=HYPERLINK("http://x")', source: { provider: 'S3', providerLabel: 'S3', accountLabel: 'acme, inc', region: '-1' } }),
    ], [column(CLOUD_COLUMNS, 'name'), column(CLOUD_COLUMNS, 'sourceAccount')], 'cloud');
    expect(lines(csv)[1]).toBe(`"'=HYPERLINK(""http://x"")",S3,"acme, inc",'-1`);
  });
});

describe('exportFileName', () => {
  it('names the file by tab and local date', () => {
    expect(exportFileName('cloud', new Date(2026, 10, 5, 23, 59))).toBe('datastores-cloud-2026-11-05.csv');
    expect(exportFileName('on-prem', new Date(2027, 0, 9, 0, 1))).toBe('datastores-on-prem-2027-01-09.csv');
  });
});

describe('limits', () => {
  it('pages by 100 and stops at 10,000 rows', () => {
    expect(EXPORT_PAGE_SIZE).toBe(100);
    expect(EXPORT_MAX_ROWS).toBe(10_000);
  });
});
