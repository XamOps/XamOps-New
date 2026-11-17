import { describe, expect, it } from 'vitest';
import type { DatastoreFilterDimension, DatastoreFilterOptions, DatastoreKpis } from '../../../../types/dspmDatastores';
import type { DatastoreTabSlug } from '../../../../constants/xamlensTabs';
import {
  DATASTORE_TAB_CONFIG, kpiCardTrend, kpiCardValue, moreFiltersDimensions, type KpiCardId,
} from '../datastoreTabConfig';
import { mergeSelectedOptions } from '../datastoreFilters';

const SLUGS: DatastoreTabSlug[] = ['all', 'cloud', 'saas', 'endpoint', 'on-prem'];
const config = (slug: DatastoreTabSlug) => DATASTORE_TAB_CONFIG[slug];
const cardLabels = (slug: DatastoreTabSlug) => config(slug).kpis.map(card => card.label);

const dimension = (key: string, values: string[]): DatastoreFilterDimension => ({
  key, options: values.map(value => ({ value, label: value, count: 1 })), hasMore: false,
});
const response = (...dimensions: DatastoreFilterDimension[]): DatastoreFilterOptions => ({ dimensions });

/** Every returned dimension of an Endpoint tab, in a vendor-like order, with an empty integration_id. */
const ENDPOINT_RESPONSE = response(
  dimension('integration_id', []),
  dimension('provider', ['ENDPOINT_AGENT']),
  dimension('scan_status', ['CLASSIFIED', 'NOT_SCANNED']),
  dimension('severity', ['MEDIUM', 'LOW']),
  dimension('sensitive_type', ['PII']),
  dimension('data_element', ['EMAIL_ADDRESS']),
  dimension('os', ['MAC', 'WINDOWS']),
  dimension('agent_status', ['OFFLINE', 'ONLINE']),
  dimension('device_type', ['laptop']),
  dimension('serial_number', ['SN-0001', 'SN-0002']),
  dimension('hostname', ['host-a']),
  dimension('user', ['user-a']),
);

const TOOLTIPS: Record<KpiCardId, string> = {
  totalSensitiveRecords: 'Sensitive records found in these datastores. The badge shows the change across the last 14 daily readings.',
  assetCount: 'Datastores on this tab. The badge shows the change across the last 14 daily readings.',
  classified: 'Datastores whose last scan finished classifying data.',
  scheduled: 'Datastores with a scan scheduled.',
  failedStopped: 'Datastores whose last scan failed or was stopped.',
  failed: 'Datastores whose last scan failed.',
  criticalHigh: 'Datastores with a Critical or High risk level.',
};

describe('KPI cards', () => {
  it('follow the console per tab', () => {
    expect(cardLabels('all')).toEqual([
      'Total Sensitive Records', 'Total Assets', 'Classified', 'Scheduled', 'Failed / Stopped', 'Critical / High',
    ]);
    expect(cardLabels('cloud')).toEqual([
      'Total Sensitive Records', 'Total Cloud Datastores', 'Classified', 'Scheduled', 'Failed / Stopped', 'Critical / High',
    ]);
    expect(cardLabels('saas')).toEqual([
      'Total Sensitive Records', 'Total SaaS Assets', 'Classified', 'Failed', 'Critical / High',
    ]);
    expect(cardLabels('endpoint')).toEqual([
      'Total Sensitive Records', 'Total Endpoints', 'Classified', 'Failed / Stopped', 'Critical / High',
    ]);
    expect(cardLabels('on-prem')).toEqual([
      'Total Sensitive Records', 'Total On-Prem Datastores', 'Classified', 'Scheduled', 'Failed / Stopped', 'Critical / High',
    ]);
  });

  it('has no Scheduled card on SaaS and Endpoint, and SaaS counts "Failed" only', () => {
    expect(cardLabels('saas')).not.toContain('Scheduled');
    expect(cardLabels('endpoint')).not.toContain('Scheduled');
    expect(config('saas').kpis.map(card => card.id)).toContain('failed');
    expect(config('saas').kpis.map(card => card.id)).not.toContain('failedStopped');
  });

  it('badges only the first two cards: a percentage, then a delta', () => {
    for (const slug of SLUGS) {
      expect(config(slug).kpis.map(card => card.badge ?? null)).toEqual(
        ['percent', 'delta', ...Array(config(slug).kpis.length - 2).fill(null)],
      );
    }
  });

  it('gives every card its tooltip, none of which speaks of scans or of "14 days ago"', () => {
    for (const slug of SLUGS) {
      for (const card of config(slug).kpis) {
        expect(card.tooltip).toBe(TOOLTIPS[card.id]);
        expect(card.tooltip).not.toMatch(/Scans/);
        expect(card.tooltip).not.toMatch(/14 days ago/);
      }
    }
  });

  it('sums failed and stopped for Failed / Stopped and reads failures alone for Failed', () => {
    const kpis: DatastoreKpis = {
      tab: 'saas', tabCounts: {},
      totalSensitiveRecords: { value: 1_200, trend: [{ date: '2026-10-01', value: 1_000 }] },
      assetCount: { value: 40, trend: [] },
      classified: 12, scheduled: 0, failed: 2, stopped: 3, criticalHigh: 5,
    };
    expect(kpiCardValue('failedStopped', kpis)).toBe(5);
    expect(kpiCardValue('failed', kpis)).toBe(2);
    expect(kpiCardValue('totalSensitiveRecords', kpis)).toBe(1_200);
    expect(kpiCardTrend('totalSensitiveRecords', kpis)).toEqual([{ date: '2026-10-01', value: 1_000 }]);
    expect(kpiCardTrend('classified', kpis)).toBeNull();
  });
});

describe('columns and quick filters', () => {
  it('has 5 chooser columns on the datastore tabs and 6 on Endpoint', () => {
    for (const slug of ['all', 'cloud', 'saas', 'on-prem'] as const) {
      expect(config(slug).columns.map(c => c.label)).toEqual(
        ['Datastore', 'Risk', 'Sensitive Data', 'Source / Account', 'Scan Status'],
      );
      expect(config(slug).selectable).toBe(false);
    }
    expect(config('endpoint').columns.map(c => c.label)).toEqual(
      ['Device', 'Risk', 'Sensitive Data', 'Serial Number', 'Agent Status', 'Agent Version'],
    );
    expect(config('endpoint').selectable).toBe(true);
  });

  it('sorts only by name, severity and sensitive record count', () => {
    for (const slug of SLUGS) {
      expect(config(slug).columns.filter(c => c.sortField).map(c => c.sortField))
        .toEqual(['name', 'severity', 'sensitive_record_count']);
    }
  });

  it('offers the console quick filters', () => {
    for (const slug of ['all', 'cloud', 'saas', 'on-prem'] as const) {
      expect(config(slug).quickFilters).toEqual(['integration_id', 'provider', 'scan_status']);
    }
    expect(config('endpoint').quickFilters).toEqual(['provider', 'scan_status', 'os', 'agent_status']);
  });

  it('shows the header "⋯" on Endpoint and On-Prem only', () => {
    expect(SLUGS.filter(slug => config(slug).headerOverflow)).toEqual(['endpoint', 'on-prem']);
  });
});

describe('moreFiltersDimensions', () => {
  it('orders Endpoint by its leading list, then the rest in response order, skipping an empty integration_id', () => {
    expect(moreFiltersDimensions(config('endpoint'), ENDPOINT_RESPONSE, {})).toEqual([
      'provider', 'scan_status', 'os', 'agent_status', 'severity', 'sensitive_type', 'data_element',
      'device_type', 'serial_number', 'hostname', 'user',
    ]);
  });

  it('keeps the fixed order on the datastore tabs and adds Selected last on SaaS', () => {
    const all = response(
      dimension('data_element', ['EMAIL_ADDRESS']), dimension('provider', ['S3']), dimension('integration_id', ['a'.repeat(24)]),
      dimension('scan_status', ['CLASSIFIED']), dimension('severity', ['HIGH']), dimension('sensitive_type', ['PII']),
      dimension('selected', ['yes']),
    );
    expect(moreFiltersDimensions(config('cloud'), all, {})).toEqual(
      ['integration_id', 'provider', 'scan_status', 'severity', 'sensitive_type', 'data_element'],
    );
    expect(moreFiltersDimensions(config('saas'), all, {})).toEqual(
      ['integration_id', 'provider', 'scan_status', 'severity', 'sensitive_type', 'data_element', 'selected'],
    );
  });

  it('keeps Region listed when every value is excluded, and its values come back checked', () => {
    const options = response(
      dimension('integration_id', ['a'.repeat(24)]), dimension('provider', ['S3']), dimension('region', []),
      dimension('scan_status', ['CLASSIFIED']),
    );
    const state = { region: { mode: 'exclude' as const, values: ['eu-west-1', 'us-east-2'] } };
    expect(moreFiltersDimensions(config('cloud'), options, state)).toEqual(
      ['integration_id', 'provider', 'region', 'scan_status'],
    );
    const memory = new Map([['eu-west-1', 'eu-west-1'], ['us-east-2', 'us-east-2']]);
    expect(mergeSelectedOptions(options.dimensions[2].options, state.region, memory)).toEqual([
      { value: 'eu-west-1', label: 'eu-west-1', count: null, checked: true },
      { value: 'us-east-2', label: 'us-east-2', count: null, checked: true },
    ]);
  });

  it('places a dimension only the state holds after the returned ones', () => {
    const withoutHostname = response(...ENDPOINT_RESPONSE.dimensions.filter(d => d.key !== 'hostname' && d.key !== 'user'));
    const state = { hostname: { mode: 'include' as const, values: ['host-a'] } };
    const listed = moreFiltersDimensions(config('endpoint'), withoutHostname, state);
    expect(listed[listed.length - 1]).toBe('hostname');
    expect(listed).toEqual([
      'provider', 'scan_status', 'os', 'agent_status', 'severity', 'sensitive_type', 'data_element',
      'device_type', 'serial_number', 'hostname',
    ]);
  });

  it('skips an empty Endpoint integration_id without a selection, but not one with a selection', () => {
    expect(moreFiltersDimensions(config('endpoint'), ENDPOINT_RESPONSE, {})).not.toContain('integration_id');
    const state = { integration_id: { mode: 'include' as const, values: ['b'.repeat(24)] } };
    expect(moreFiltersDimensions(config('endpoint'), ENDPOINT_RESPONSE, state)).toContain('integration_id');
  });

  it('lists nothing before the first response when nothing is selected', () => {
    expect(moreFiltersDimensions(config('all'), null, {})).toEqual([]);
  });
});
