import React, { useId } from 'react';
import { Icon } from '@iconify/react';
import type {
  DatastoreFileSummary, DatastoreOverview, DatastoreProperty, DatastoreSecurityConfiguration, DatastoreSensitiveRecords,
} from '../../../../types/dspmDatastores';
import { abbreviate, formatBytes } from '../datastoreFormat';
import { fileTypeIcon } from '../datastoreVisuals';
import { CategoryChip, EntityChip } from '../ui/Chips';

const Card: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => {
  const headingId = useId();
  return (
    <section aria-labelledby={headingId} className="rounded-xl border border-[rgba(0,0,0,0.08)] p-4">
      <h4 id={headingId} className="mb-3 border-b border-[rgba(0,0,0,0.06)] pb-2 text-[11px] font-bold text-[rgba(0,0,0,0.75)]">
        {title}
      </h4>
      {children}
    </section>
  );
};

/** A headline figure: its label, then the value in large type. */
const Figure: React.FC<{ label: string; value: string }> = ({ label, value }) => (
  <dl>
    <dt className="text-[10px] text-[rgba(0,0,0,0.45)]">{label}</dt>
    <dd className="mt-1 text-2xl font-bold tabular-nums text-[rgba(0,0,0,0.88)]">{value}</dd>
  </dl>
);

/** "Total Files" and one chip per file type, in the backend's order; types with no files are greyed. */
const FileSummaryCard: React.FC<{ summary: DatastoreFileSummary }> = ({ summary }) => (
  <Card title="File Summary">
    <Figure label="Total Files" value={summary.totalFiles === null ? '-' : abbreviate(summary.totalFiles)} />
    {summary.types.length > 0 && (
      <div className="mt-3 flex flex-wrap gap-1.5">
        {summary.types.map(type => {
          const empty = type.count === 0;
          return (
            <span
              key={type.key}
              data-file-type={type.key}
              data-empty={empty || undefined}
              className={`inline-flex items-center gap-1.5 rounded-lg border px-2 py-1 text-[11px] ${
                empty
                  ? 'border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)] text-[rgba(0,0,0,0.35)]'
                  : 'border-[rgba(0,0,0,0.1)] bg-white text-[rgba(0,0,0,0.7)]'
              }`}
            >
              <Icon icon={fileTypeIcon(type.key)} width={13} />
              {type.label}{' '}
              <span className="font-semibold tabular-nums">{abbreviate(type.count)}</span>
            </span>
          );
        })}
      </div>
    )}
  </Card>
);

/** "Total Sensitive Records", then each category's entities in API order. */
const SensitivesCard: React.FC<{ records: DatastoreSensitiveRecords }> = ({ records }) => (
  <Card title="Sensitives">
    <Figure label="Total Sensitive Records" value={records.total === null ? '-' : abbreviate(records.total)} />
    {records.groups.length === 0 ? (
      <p className="mt-2 text-xs text-[rgba(0,0,0,0.45)]">No sensitive records found</p>
    ) : (
      records.groups.map(group => (
        <div key={group.code} className="mt-3">
          <CategoryChip label={group.label ?? group.code} />
          <div className="mt-2 flex flex-wrap gap-1.5">
            {group.entities.map((entity, index) => (
              <span key={`${index}:${entity.name}`} className="inline-flex items-center gap-1">
                <EntityChip name={entity.name} categoryLabel={group.label} />{' '}
                <span className="text-[11px] font-semibold tabular-nums text-[rgba(0,0,0,0.7)]">{abbreviate(entity.count)}</span>
              </span>
            ))}
          </div>
        </div>
      ))
    )}
  </Card>
);

/** Region, Datastore Size, then the rest in API order. Values wrap, so an ARN or a UUID never overflows. */
const PropertiesCard: React.FC<{ properties: DatastoreProperty[] }> = ({ properties }) => (
  <Card title="Properties">
    {properties.length === 0 ? (
      <p className="text-xs text-[rgba(0,0,0,0.45)]">-</p>
    ) : (
      <dl className="space-y-2.5">
        {properties.map(property => (
          <div key={property.key} className="flex items-start justify-between gap-3">
            <dt className="flex-shrink-0 text-[11px] text-[rgba(0,0,0,0.45)]">{property.label}</dt>
            <dd className="min-w-0 break-all text-right text-xs font-semibold text-[rgba(0,0,0,0.8)]">
              {property.bytes !== null ? formatBytes(property.bytes) : (property.text ?? '-')}
            </dd>
          </div>
        ))}
      </dl>
    )}
  </Card>
);

/** Each setting with a dot: green only for "Enabled", red for every other value ("Disabled", "Sse S3"). */
const SecurityCard: React.FC<{ items: DatastoreSecurityConfiguration[] }> = ({ items }) => (
  <Card title="Security Configurations">
    <dl className="space-y-2.5">
      {items.map(item => {
        const enabled = item.value === 'Enabled';
        return (
          <div key={item.key} className="flex items-center justify-between gap-3">
            <dt className="text-[11px] text-[rgba(0,0,0,0.45)]">{item.label}</dt>
            <dd className="inline-flex items-center gap-1.5 text-xs font-semibold text-[rgba(0,0,0,0.8)]">
              <span
                aria-hidden="true"
                data-dot={enabled ? 'green' : 'red'}
                className={`h-1.5 w-1.5 flex-shrink-0 rounded-full ${enabled ? 'bg-emerald-500' : 'bg-rose-500'}`}
              />
              {item.value}
            </dd>
          </div>
        );
      })}
    </dl>
  </Card>
);

/**
 * The Information tab, from the overview the drawer loaded on open. The File Summary is hidden when
 * the overview has none and Security Configurations when it has none or an empty list, so a database
 * shows only Sensitives and Properties (FR-INF-5).
 */
const InformationTab: React.FC<{ overview: DatastoreOverview }> = ({ overview }) => {
  const security = overview.securityConfigurations;
  return (
    <div className="grid grid-cols-1 items-start gap-4 md:grid-cols-[1fr_minmax(260px,340px)]">
      <div className="min-w-0 space-y-4">
        {overview.fileSummary && <FileSummaryCard summary={overview.fileSummary} />}
        <SensitivesCard records={overview.sensitiveRecords} />
      </div>
      <div className="min-w-0 space-y-4">
        <PropertiesCard properties={overview.properties} />
        {security && security.length > 0 && <SecurityCard items={security} />}
      </div>
    </div>
  );
};

export default InformationTab;
