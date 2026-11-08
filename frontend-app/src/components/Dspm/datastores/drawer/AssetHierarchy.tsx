import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DspmError } from '../../../../types/dspm';
import type { DataFlowDatabase, DataFlowDatabases, DataFlowSchema, DataFlowSchemas, DatastoreRow } from '../../../../types/dspmDatastores';
import type { DatastoreDrawerApi } from '../../../../hooks/useDspmDatastoreDrawer';
import { toDspmError } from '../../../../hooks/useDspmDatastores';
import { DspmProviderIcon } from '../../DspmIcons';
import { MAX_SEARCH } from '../datastoreFilters';
import { SearchInput } from '../ui/SearchInput';

/** What the tables pane lists: a schema's tables, or a database's when it has no schemas. */
export interface TableParent {
  /** The search gate's `parentKey`: `s:<schemaId>`, or `d:<databaseId>` for a database without schemas. */
  key: string;
  database: DataFlowDatabase;
  /** Null when the database itself holds the tables. */
  schema: DataFlowSchema | null;
}

const databaseParent = (database: DataFlowDatabase, id: string): TableParent => ({ key: `d:${id}`, database, schema: null });
const schemaParent = (database: DataFlowDatabase, schema: DataFlowSchema, id: string): TableParent => ({
  key: `s:${id}`, database, schema,
});

type SchemaLoad = { status: 'loading' } | { status: 'loaded'; result: DataFlowSchemas } | { status: 'failed' };

const TRUNCATED_NOTE = 'Showing the first 1,000.';

const NODE = 'flex w-full min-w-0 items-center gap-1.5 rounded-md px-1.5 py-1 text-left text-xs transition-colors';
const nodeTone = (selected: boolean) => (selected
  ? 'bg-[rgba(0,0,0,0.06)] font-semibold text-[rgba(0,0,0,0.88)]'
  : 'text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.03)]');

interface Props {
  api: DatastoreDrawerApi;
  row: Pick<DatastoreRow, 'name' | 'source'>;
  /** Null while the databases load or after they failed (the right pane says why). */
  databases: DataFlowDatabases | null;
  selectedKey: string | null;
  /** Should be stable. */
  onSelect: (parent: TableParent) => void;
  /** A schema list failed; the right pane shows the error with `retry`. Should be stable. */
  onError: (error: DspmError, retry: () => void) => void;
}

/**
 * The Data Flow tree, 240 px wide: the datastore, its databases, and each database's schemas, loaded
 * when the database is first expanded. A schema-less database, or one whose schema list came back
 * empty, is selectable itself and lists its tables directly.
 *
 * On first load the first database expands and its first schema (or the database itself when it is
 * schema-less) is selected (DA1). The search filters the nodes already loaded, never requesting more:
 * a database shows with all its loaded schemas when its name matches, or with the matching schemas
 * when only they match, and the branches shown are expanded.
 */
const AssetHierarchy: React.FC<Props> = ({ api, row, databases, selectedKey, onSelect, onError }) => {
  const [loads, setLoads] = useState<Readonly<Record<string, SchemaLoad>>>({});
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set());
  const [search, setSearch] = useState('');
  /** The database whose first schema gets selected once its schemas arrive (DA1). */
  const autoSelect = useRef<string | null>(null);
  const started = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  const loadSchemas: (database: DataFlowDatabase, id: string) => void = useCallback((database: DataFlowDatabase, id: string) => {
    setLoads(prev => ({ ...prev, [id]: { status: 'loading' } }));
    api.schemas(id).then(
      result => {
        if (!mounted.current) return;
        setLoads(prev => ({ ...prev, [id]: { status: 'loaded', result } }));
        if (autoSelect.current !== id) return;
        autoSelect.current = null;
        const first = result.items.find(schema => schema.id !== null);
        if (result.items.length === 0) onSelect(databaseParent(database, id));
        else if (first?.id) onSelect(schemaParent(database, first, first.id));
      },
      err => {
        if (!mounted.current) return;
        setLoads(prev => ({ ...prev, [id]: { status: 'failed' } }));
        onError(toDspmError(err), () => loadSchemas(database, id));
      },
    );
  }, [api, onSelect, onError]);

  useEffect(() => {
    if (started.current || !databases || databases.items.length === 0) return;
    started.current = true;
    const first = databases.items[0];
    if (first.id === null) return;
    if (first.schemaless) {
      onSelect(databaseParent(first, first.id));
      return;
    }
    autoSelect.current = first.id;
    setExpanded(new Set([first.id]));
    loadSchemas(first, first.id);
  }, [databases, loadSchemas, onSelect]);

  const toggle = (database: DataFlowDatabase, id: string) => {
    const opening = !expanded.has(id);
    setExpanded(prev => {
      const next = new Set(prev);
      if (opening) next.add(id); else next.delete(id);
      return next;
    });
    const load = loads[id];
    if (opening && (!load || load.status === 'failed')) loadSchemas(database, id);
  };

  const needle = search.trim().toLowerCase();
  const matches = (name: string | null) => (name ?? '').toLowerCase().includes(needle);

  const renderDatabase = (database: DataFlowDatabase, index: number) => {
    const id = database.id;
    const load = id === null ? undefined : loads[id];
    const loaded = load?.status === 'loaded' ? load.result : null;
    let schemas = loaded?.items ?? [];
    let open = id !== null && expanded.has(id);
    if (needle) {
      const own = matches(database.name);
      if (!own) schemas = schemas.filter(schema => matches(schema.name));
      if (!own && schemas.length === 0) return null;
      open = schemas.length > 0;
    }
    const label = (
      <>
        <Icon icon="mdi:database-outline" width={14} className="flex-shrink-0 text-[rgba(0,0,0,0.45)]" />
        <span className="truncate" title={database.name ?? undefined}>{database.name ?? '-'}</span>
      </>
    );

    let node: React.ReactNode;
    if (id === null) {
      node = <span className={`${NODE} pl-6 text-[rgba(0,0,0,0.4)]`}>{label}</span>;
    } else if (database.schemaless || (loaded !== null && loaded.items.length === 0)) {
      const key = `d:${id}`;
      node = (
        <button
          type="button"
          aria-current={selectedKey === key ? 'true' : undefined}
          onClick={() => onSelect(databaseParent(database, id))}
          className={`${NODE} pl-6 ${nodeTone(selectedKey === key)}`}
        >
          {label}
        </button>
      );
    } else {
      node = (
        <button type="button" aria-expanded={open} onClick={() => toggle(database, id)} className={`${NODE} ${nodeTone(false)}`}>
          <Icon icon={open ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={14} className="flex-shrink-0 text-[rgba(0,0,0,0.4)]" />
          {label}
        </button>
      );
    }

    return (
      <li key={id ?? `db-${index}`}>
        {node}
        {open && load?.status === 'loading' && <p className="py-1 pl-9 text-[11px] text-[rgba(0,0,0,0.4)]">Loading…</p>}
        {open && schemas.length > 0 && (
          <ul className="pl-5">
            {schemas.map((schema, position) => {
              const schemaId = schema.id;
              const schemaLabel = (
                <>
                  <Icon icon="mdi:folder-outline" width={14} className="flex-shrink-0 text-[rgba(0,0,0,0.45)]" />
                  <span className="truncate" title={schema.name ?? undefined}>{schema.name ?? '-'}</span>
                </>
              );
              const selected = schemaId !== null && selectedKey === `s:${schemaId}`;
              return (
                <li key={schemaId ?? `schema-${position}`}>
                  {schemaId === null ? (
                    <span className={`${NODE} pl-6 text-[rgba(0,0,0,0.4)]`}>{schemaLabel}</span>
                  ) : (
                    <button
                      type="button"
                      aria-current={selected ? 'true' : undefined}
                      onClick={() => onSelect(schemaParent(database, schema, schemaId))}
                      className={`${NODE} pl-6 ${nodeTone(selected)}`}
                    >
                      {schemaLabel}
                    </button>
                  )}
                </li>
              );
            })}
          </ul>
        )}
        {open && loaded?.truncated && <p className="py-1 pl-11 text-[10px] text-[rgba(0,0,0,0.4)]">{TRUNCATED_NOTE}</p>}
      </li>
    );
  };

  return (
    <nav aria-label="Asset Hierarchy" className="w-60 flex-shrink-0 space-y-2 rounded-xl border border-[rgba(0,0,0,0.08)] p-2">
      <p className="px-1 text-[11px] font-semibold text-[rgba(0,0,0,0.55)]">Asset Hierarchy</p>
      <SearchInput value={search} onChange={setSearch} placeholder="Search databases, schema…" maxLength={MAX_SEARCH} />
      <div className={`${NODE} font-semibold text-[rgba(0,0,0,0.8)]`}>
        <DspmProviderIcon provider={row.source.provider} size={14} />
        <span className="truncate" title={row.name ?? undefined}>{row.name ?? '-'}</span>
      </div>
      {databases && (
        <ul className="pl-3">
          {databases.items.map(renderDatabase)}
        </ul>
      )}
      {databases?.truncated && <p className="px-1 text-[10px] text-[rgba(0,0,0,0.4)]">{TRUNCATED_NOTE}</p>}
    </nav>
  );
};

export default AssetHierarchy;
