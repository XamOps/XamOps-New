import React, { useId } from 'react';
import type { DataFlowRelation, DataFlowRelations } from '../../../../../types/dspmDrawers';
import { EmptyState } from '../../ui/EmptyState';
import { TH } from '../TabParts';

interface RelationRow {
  key: string;
  /** This table's column. */
  column: string;
  /** The other table and its column: "schema.table · column". */
  other: string;
}

const DASH = '-';
const TD = 'px-3 py-2.5 align-middle text-xs text-[rgba(0,0,0,0.75)]';

const tableLabel = (relation: DataFlowRelation) =>
  relation.schemaName ? `${relation.schemaName}.${relation.tableName}` : relation.tableName;

/**
 * One row per column pair. `own` picks this table's side of the pair: the source column of an outgoing
 * key, the target column of an incoming one. A relation without pairs still shows its table.
 */
function rowsOf(relations: readonly DataFlowRelation[], own: 'sourceColumn' | 'targetColumn'): RelationRow[] {
  const other = own === 'sourceColumn' ? 'targetColumn' : 'sourceColumn';
  return relations.flatMap((relation, r) => {
    const pairs = relation.columns.length > 0 ? relation.columns : [{ sourceColumn: null, targetColumn: null }];
    return pairs.map((pair, p) => ({
      key: `${r}-${p}`,
      column: pair[own] ?? DASH,
      other: `${tableLabel(relation)} · ${pair[other] ?? DASH}`,
    }));
  });
}

const Section: React.FC<{ title: string; otherHeader: string; rows: RelationRow[] }> = ({ title, otherHeader, rows }) => {
  const heading = useId();
  return (
    <section aria-labelledby={heading} className="space-y-1.5">
      <h3 id={heading} className="text-xs font-bold text-[rgba(0,0,0,0.8)]">{title}</h3>
      {rows.length === 0 ? (
        <p className="text-xs text-[rgba(0,0,0,0.45)]">None</p>
      ) : (
        <div className="overflow-hidden rounded-xl border border-[rgba(0,0,0,0.08)]">
          <table className="w-full text-left">
            <thead>
              <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                <th scope="col" className={TH}>Column</th>
                <th scope="col" className={TH}>{otherHeader}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(row => (
                <tr key={row.key} className="border-b border-[rgba(0,0,0,0.04)] last:border-b-0">
                  <td className={`${TD} font-semibold text-[rgba(0,0,0,0.85)]`}>{row.column}</td>
                  <td className={TD}>{row.other}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
};

/**
 * The table panel's Relationships tab (A32 [O3]: the console's layout is unseen). "Outgoing" lists the
 * keys of this table ("Column | References": the column, then the referenced table and column), then
 * "Incoming" the keys pointing at it ("Column | Referenced by"), both in API order. An empty section
 * reads "None"; a table without any key shows one empty state.
 */
const TableRelations: React.FC<{ relations: DataFlowRelations }> = ({ relations }) => {
  if (relations.outgoing.length === 0 && relations.incoming.length === 0) {
    return <EmptyState title="No relationships found." />;
  }
  return (
    <div className="space-y-5">
      <Section title="Outgoing" otherHeader="References" rows={rowsOf(relations.outgoing, 'sourceColumn')} />
      <Section title="Incoming" otherHeader="Referenced by" rows={rowsOf(relations.incoming, 'targetColumn')} />
    </div>
  );
};

export default TableRelations;
