import { describe, expect, it } from 'vitest';
import {
  DIMENSION_VALUE_RULES, EXCLUDABLE_DIMENSIONS, MAX_FILTER_VALUES, MAX_FREE_TEXT, MAX_SEARCH, buildFilterParams,
  clearDimension, isSelectable, mergeSelectedOptions, normalizeSearch, selectAllVisible, setMode, toggleValue,
  type DatastoreFilterState,
} from '../datastoreFilters';
import { DATASTORE_TAB_CONFIG, DIMENSION_LABELS } from '../datastoreTabConfig';

const codes = (n: number) => Array.from({ length: n }, (_, i) => ({ value: `PROVIDER_${String(i).padStart(2, '0')}` }));

describe('limits mirrored from the backend', () => {
  it('match the request validation', () => {
    expect(MAX_FILTER_VALUES).toBe(50);
    expect(MAX_FREE_TEXT).toBe(128);
    expect(MAX_SEARCH).toBe(200);
  });
});

describe('buildFilterParams', () => {
  it('sends Include values as <dim> and Exclude values as <dim>_exclude, never both', () => {
    let state = toggleValue({}, 'provider', 'S3');
    state = toggleValue(state, 'provider', 'EBS');
    state = toggleValue(state, 'scan_status', 'FAILED');
    expect(buildFilterParams(state)).toEqual([['provider', 'S3,EBS'], ['scan_status', 'FAILED']]);

    state = setMode(state, 'provider', 'exclude');
    const params = buildFilterParams(state);
    expect(params).toEqual([['provider_exclude', 'S3,EBS'], ['scan_status', 'FAILED']]);
    expect(params.map(([key]) => key)).not.toContain('provider');
  });

  it('sends nothing for a mode without values', () => {
    expect(buildFilterParams(setMode({}, 'provider', 'exclude'))).toEqual([]);
    expect(buildFilterParams(toggleValue(toggleValue({}, 'provider', 'S3'), 'provider', 'S3'))).toEqual([]);
  });
});

describe('setMode', () => {
  it('keeps the values when switching between Include and Exclude', () => {
    const included = toggleValue(toggleValue({}, 'region', 'eu-west-1'), 'region', 'us-east-2');
    const excluded = setMode(included, 'region', 'exclude');
    expect(excluded.region).toEqual({ mode: 'exclude', values: ['eu-west-1', 'us-east-2'] });
    expect(setMode(excluded, 'region', 'include').region).toEqual({ mode: 'include', values: ['eu-west-1', 'us-east-2'] });
  });

  it('refuses Exclude on a dimension the backend cannot exclude', () => {
    const state = toggleValue({}, 'os', 'MAC');
    expect(EXCLUDABLE_DIMENSIONS.has('os')).toBe(false);
    expect(setMode(state, 'os', 'exclude')).toBe(state);
  });
});

describe('mergeSelectedOptions', () => {
  it('keeps an excluded value visible and checked after the vendor drops it', () => {
    const state = setMode(toggleValue({}, 'provider', 'S3'), 'provider', 'exclude');
    const options = [{ value: 'EBS', label: 'EBS', count: 4 }];
    const memory = new Map([['S3', 'S3'], ['EBS', 'EBS']]);
    expect(mergeSelectedOptions(options, state.provider, memory)).toEqual([
      { value: 'EBS', label: 'EBS', count: 4, checked: false },
      { value: 'S3', label: 'S3', count: null, checked: true },
    ]);
  });

  it('falls back to the value when no label is remembered', () => {
    expect(mergeSelectedOptions([], { mode: 'include', values: ['abc'] }, new Map())).toEqual([
      { value: 'abc', label: 'abc', count: null, checked: true },
    ]);
  });
});

describe('selectAllVisible', () => {
  it('adds the visible, selectable, unchecked options in list order', () => {
    const state: DatastoreFilterState = { data_element: { mode: 'include', values: ['PHONE_NUMBER'] } };
    const visible = [{ value: 'EMAIL_ADDRESS' }, { value: '' }, { value: 'PHONE_NUMBER' }, { value: 'IP_ADDRESS' }];
    const result = selectAllVisible(state, 'data_element', visible);
    expect(result.state.data_element.values).toEqual(['PHONE_NUMBER', 'EMAIL_ADDRESS', 'IP_ADDRESS']);
    expect(result.stoppedShort).toBe(false);
  });

  it('selects 50 of 60 selectable options and reports that it stopped short', () => {
    const result = selectAllVisible({}, 'provider', codes(60));
    expect(result.state.provider.values).toHaveLength(50);
    expect(result.state.provider.values[0]).toBe('PROVIDER_00');
    expect(result.state.provider.values[49]).toBe('PROVIDER_49');
    expect(result.stoppedShort).toBe(true);
  });

  it('keeps the mode of the dimension', () => {
    const state = setMode({}, 'provider', 'exclude');
    expect(selectAllVisible(state, 'provider', codes(2)).state.provider).toEqual({
      mode: 'exclude', values: ['PROVIDER_00', 'PROVIDER_01'],
    });
  });
});

describe('toggleValue', () => {
  it('refuses a 51st value but still removes one', () => {
    const full = selectAllVisible({}, 'provider', codes(50)).state;
    expect(full.provider.values).toHaveLength(50);
    expect(toggleValue(full, 'provider', 'PROVIDER_99')).toBe(full);
    expect(toggleValue(full, 'provider', 'PROVIDER_00').provider.values).toHaveLength(49);
  });
});

describe('clearDimension', () => {
  it('drops the values and the mode', () => {
    const state = setMode(toggleValue({}, 'provider', 'S3'), 'provider', 'exclude');
    expect(clearDimension(state, 'provider')).toEqual({});
    expect(clearDimension(state, 'region')).toBe(state);
  });
});

describe('isSelectable', () => {
  it('rejects values the backend would refuse', () => {
    expect(isSelectable('data_element', { value: '' })).toBe(false);
    expect(isSelectable('region', { value: 'x'.repeat(129) })).toBe(false);
    expect(isSelectable('provider', { value: 'ebs' })).toBe(false);
    expect(isSelectable('region', { value: 'eu-west-1,us-east-2' })).toBe(false);
    expect(isSelectable('region', { value: 'eu-west-1 ' })).toBe(false);
    expect(isSelectable('hostname', { value: 'host\u0007a' })).toBe(false);
    expect(isSelectable('integration_id', { value: 'not-hex' })).toBe(false);
    expect(isSelectable('scan_status', { value: 'classified' })).toBe(false);
    expect(isSelectable('unknown_dimension', { value: 'anything' })).toBe(false);
  });

  it('accepts values that match their dimension rule', () => {
    expect(isSelectable('region', { value: 'x'.repeat(128) })).toBe(true);
    expect(isSelectable('provider', { value: 'SELF_HOSTED_POSTGRES' })).toBe(true);
    expect(isSelectable('integration_id', { value: '0123456789abcdefABCDEF01' })).toBe(true);
    expect(isSelectable('scan_status', { value: 'SCHEDULED' })).toBe(true);
    expect(isSelectable('severity', { value: 'NONE' })).toBe(true);
    expect(isSelectable('user', { value: 'pat@example.com' })).toBe(true);
  });
});

describe('normalizeSearch', () => {
  it('turns a tab and a line break into spaces and trims', () => {
    expect(normalizeSearch('\torders\narchive  ')).toBe('orders archive');
  });

  it('treats blank text as no search', () => {
    expect(normalizeSearch(' \t\r\n ')).toBeNull();
    expect(normalizeSearch('')).toBeNull();
  });
});

describe('DIMENSION_VALUE_RULES', () => {
  it('covers every dimension named in datastoreTabConfig.ts', () => {
    const named = new Set<string>(Object.keys(DIMENSION_LABELS));
    for (const config of Object.values(DATASTORE_TAB_CONFIG)) {
      config.quickFilters.forEach(key => named.add(key));
      const more = config.moreFilters.mode === 'fixed' ? config.moreFilters.order : config.moreFilters.leading;
      more.forEach(key => named.add(key));
    }
    EXCLUDABLE_DIMENSIONS.forEach(key => named.add(key));
    for (const key of named) {
      expect(DIMENSION_VALUE_RULES[key], key).toBeDefined();
    }
  });
});

describe('the Data Lineage filter keys', () => {
  it('accepts datastoreName and label as free text, in both modes', () => {
    expect(DIMENSION_VALUE_RULES.datastoreName).toEqual({ kind: 'freeText' });
    expect(DIMENSION_VALUE_RULES.label).toEqual({ kind: 'freeText' });
    expect(EXCLUDABLE_DIMENSIONS.has('datastoreName')).toBe(true);
    expect(EXCLUDABLE_DIMENSIONS.has('label')).toBe(true);
    expect(isSelectable('datastoreName', { value: 'Example Laptop (Gmail)' })).toBe(true);
    expect(isSelectable('label', { value: 'a,b' })).toBe(false);
    const state = setMode({ label: { mode: 'include', values: ['PII'] } }, 'label', 'exclude');
    expect(buildFilterParams(state)).toEqual([['label_exclude', 'PII']]);
  });
});
