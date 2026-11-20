/**
 * The Files tabs' filters (design 6.4 as changed by plan C1 to C6). Dimension keys are the backend's
 * parameter names. Every dimension outside `single` is a multi-select whose Include / Exclude select has
 * Exclude disabled ("Disabled by admin"), because no Files route takes an exclude parameter.
 */
export interface FilesFilterConfig {
  /** The dropdowns, left to right. */
  quick: readonly string[];
  /** "More filters": the full left-list order. */
  more: readonly string[];
  labels: Readonly<Record<string, string>>;
  /** Single-choice dimensions. */
  single: ReadonlySet<string>;
  /** A single-choice dimension's "no choice" option, listed first. */
  anyLabel: Readonly<Record<string, string>>;
  /** Dimensions whose option labels are drawn as category chips. */
  chips: ReadonlySet<string>;
}

export const SAAS_FILTERS: FilesFilterConfig = {
  quick: ['sensitives', 'documentClass', 'sharing'],
  more: ['sensitives', 'category', 'appType', 'documentClass', 'owner', 'internalUser', 'externalUser', 'sharing'],
  labels: {
    sensitives: 'Sensitives', category: 'Category', appType: 'App Type', documentClass: 'Document Class',
    owner: 'Owners', internalUser: 'Internal Users', externalUser: 'External Users', sharing: 'Sharing Mode',
  },
  single: new Set(['documentClass', 'sharing']),
  anyLabel: { sharing: 'Any Access' },
  chips: new Set(['sensitives']),
};

export const ENDPOINT_FILTERS: FilesFilterConfig = {
  quick: ['sensitives', 'documentClass', 'dataElement'],
  more: ['category', 'sensitives', 'documentClass', 'dataElement', 'tag', 'uid', 'hostname', 'lastModified'],
  labels: {
    category: 'Category', sensitives: 'Sensitives', documentClass: 'Document Class', dataElement: 'Data Elements',
    tag: 'Matters Tags', uid: 'UID', hostname: 'Host Name', lastModified: 'Last Modified',
  },
  single: new Set(['lastModified']),
  anyLabel: {},
  chips: new Set(['sensitives']),
};
