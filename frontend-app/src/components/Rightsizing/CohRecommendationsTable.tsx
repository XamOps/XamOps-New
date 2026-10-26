import React, { useMemo, useState, useCallback } from 'react';
import { motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { DialogRoot, DialogPanel } from '../ui';
import {
  fetchCohRecommendationDetail,
  type CohRecommendation,
  type CohRecommendationDetail,
} from '../../api/cohApi';

interface CohRecommendationsTableProps {
  recommendations: CohRecommendation[];
  isLoading: boolean;
  accountId: string;
}

type SortDir = 'asc' | 'desc';

const fmtMoney = (value: number | null | undefined, currency?: string | null): string => {
  if (value === null || value === undefined) return '—';
  const symbol = currency && currency !== 'USD' ? `${currency} ` : '$';
  return `${symbol}${value.toFixed(2)}`;
};

const fmtPct = (value: number | null | undefined): string => {
  if (value === null || value === undefined) return '—';
  return `${value.toFixed(1)}%`;
};

/** A resource's short identifier: the id when present, else the ARN tail, else the ARN. */
const resourceLabel = (rec: CohRecommendation): string => {
  if (rec.resourceId) return rec.resourceId;
  if (rec.resourceArn) {
    const tail = rec.resourceArn.split('/').pop() || rec.resourceArn.split(':').pop();
    return tail || rec.resourceArn;
  }
  return '—';
};

const EFFORT_META: Record<string, { label: string; className: string }> = {
  verylow: { label: 'Very Low', className: 'bg-emerald-50 text-emerald-700 border-emerald-200' },
  low: { label: 'Low', className: 'bg-emerald-50 text-emerald-700 border-emerald-200' },
  medium: { label: 'Medium', className: 'bg-amber-50 text-amber-700 border-amber-200' },
  high: { label: 'High', className: 'bg-rose-50 text-rose-700 border-rose-200' },
  veryhigh: { label: 'Very High', className: 'bg-rose-50 text-rose-700 border-rose-200' },
};

const EffortBadge: React.FC<{ effort: string | null }> = ({ effort }) => {
  if (!effort) return <span className="text-[rgba(0,0,0,0.3)]">—</span>;
  const meta = EFFORT_META[effort.toLowerCase().replace(/[^a-z]/g, '')] ?? {
    label: effort,
    className: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.1)]',
  };
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold border ${meta.className}`}>
      {meta.label}
    </span>
  );
};

const CohRecommendationsTable: React.FC<CohRecommendationsTableProps> = ({
  recommendations,
  isLoading,
  accountId,
}) => {
  const [sortDir, setSortDir] = useState<SortDir>('desc');
  const [selected, setSelected] = useState<CohRecommendation | null>(null);
  const [detail, setDetail] = useState<CohRecommendationDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const sorted = useMemo(() => {
    const rows = [...recommendations];
    rows.sort((a, b) => {
      const av = a.estimatedMonthlySavings ?? -Infinity;
      const bv = b.estimatedMonthlySavings ?? -Infinity;
      return sortDir === 'desc' ? bv - av : av - bv;
    });
    return rows;
  }, [recommendations, sortDir]);

  const openDetail = useCallback(async (rec: CohRecommendation) => {
    setSelected(rec);
    setDetail(null);
    setDetailLoading(true);
    try {
      const result = await fetchCohRecommendationDetail(accountId, rec.recommendationId);
      setDetail(result);
    } catch (err) {
      console.error('Failed to load Cost Optimization Hub detail:', err);
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  }, [accountId]);

  const closeDetail = useCallback(() => {
    setSelected(null);
    setDetail(null);
  }, []);

  const toggleSort = useCallback(() => setSortDir((d) => (d === 'desc' ? 'asc' : 'desc')), []);

  if (isLoading) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
        <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
          <div className="h-2.5 w-56 bg-[rgba(0,0,0,0.06)] rounded" />
        </div>
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="px-5 py-4 border-t border-[rgba(0,0,0,0.04)] animate-pulse flex items-center gap-4">
            <div className="w-8 h-8 rounded-lg bg-[rgba(0,0,0,0.06)] flex-shrink-0" />
            <div className="flex-1 space-y-2">
              <div className="h-2.5 w-40 bg-[rgba(0,0,0,0.06)] rounded" />
              <div className="h-2 w-24 bg-[rgba(0,0,0,0.04)] rounded" />
            </div>
            <div className="h-2.5 w-20 bg-[rgba(0,0,0,0.06)] rounded" />
          </div>
        ))}
      </div>
    );
  }

  return (
    <>
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden widget-panel-hover">
        {/* Subheader */}
        <div className="flex items-center gap-2 px-5 py-3 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
          <Icon icon="mdi:aws" width={14} color="#FF9900" />
          <span className="text-[11px] text-[rgba(0,0,0,0.4)] italic">
            Cross-service recommendations from AWS Cost Optimization Hub — click a row for detail
          </span>
          <span className="ml-auto text-[11px] font-semibold text-[rgba(0,0,0,0.4)]">
            {recommendations.length} item{recommendations.length !== 1 ? 's' : ''}
          </span>
        </div>

        {/* Column headers */}
        <div className="grid grid-cols-12 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)] border-b border-[rgba(0,0,0,0.06)]">
          <div className="col-span-2">Action</div>
          <div className="col-span-3">Resource</div>
          <div className="col-span-2">Region</div>
          <button
            onClick={toggleSort}
            className="col-span-2 flex items-center gap-1 text-left uppercase tracking-wide hover:text-[rgba(0,0,0,0.7)] transition-colors"
            title="Sort by estimated monthly savings"
          >
            Savings / mo
            <Icon icon={sortDir === 'desc' ? 'mdi:arrow-down' : 'mdi:arrow-up'} width={12} />
          </button>
          <div className="col-span-1 text-right">Savings %</div>
          <div className="col-span-2 text-right">Effort</div>
        </div>

        {/* Rows */}
        {sorted.map((rec, idx) => (
          <motion.div
            key={rec.recommendationId}
            role="button"
            tabIndex={0}
            onClick={() => openDetail(rec)}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openDetail(rec); } }}
            className="grid grid-cols-12 items-center px-5 py-3.5 border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)] transition-colors text-[13px] cursor-pointer outline-none focus:bg-[rgba(0,0,0,0.03)]"
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.15, delay: Math.min(idx * 0.04, 0.6), ease: 'easeOut' }}
          >
            {/* Action */}
            <div className="col-span-2 min-w-0">
              <span className="inline-flex items-center px-2 py-0.5 rounded-md text-[11px] font-semibold bg-indigo-50 text-indigo-700 border border-indigo-100 truncate max-w-full">
                {rec.actionType || 'Optimize'}
              </span>
            </div>

            {/* Resource */}
            <div className="col-span-3 min-w-0">
              <p className="font-semibold text-[rgba(0,0,0,0.82)] truncate" title={rec.currentResourceType || undefined}>
                {rec.currentResourceType || '—'}
              </p>
              <p
                className="text-[11px] font-mono text-[rgba(0,0,0,0.4)] truncate"
                title={rec.resourceArn || rec.resourceId || undefined}
              >
                {resourceLabel(rec)}
              </p>
              <div className="flex items-center gap-1.5 mt-1">
                {rec.restartNeeded && (
                  <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-semibold bg-amber-50 text-amber-700 border border-amber-200">
                    <Icon icon="mdi:restart" width={10} /> Restart needed
                  </span>
                )}
                {rec.rollbackPossible && (
                  <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-semibold bg-sky-50 text-sky-700 border border-sky-200">
                    <Icon icon="mdi:backup-restore" width={10} /> Rollback possible
                  </span>
                )}
              </div>
            </div>

            {/* Region */}
            <div className="col-span-2 text-[12px] text-[rgba(0,0,0,0.6)] truncate">{rec.region || '—'}</div>

            {/* Savings / mo */}
            <div className="col-span-2">
              <p className="font-semibold text-emerald-600 text-[13px]">{fmtMoney(rec.estimatedMonthlySavings, rec.currencyCode)}</p>
              {rec.estimatedMonthlyCost !== null && rec.estimatedMonthlyCost !== undefined && (
                <p className="text-[11px] text-[rgba(0,0,0,0.4)]">of {fmtMoney(rec.estimatedMonthlyCost, rec.currencyCode)}</p>
              )}
            </div>

            {/* Savings % */}
            <div className="col-span-1 text-right text-[12px] text-[rgba(0,0,0,0.6)]">{fmtPct(rec.estimatedSavingsPercentage)}</div>

            {/* Effort */}
            <div className="col-span-2 flex justify-end">
              <EffortBadge effort={rec.implementationEffort} />
            </div>
          </motion.div>
        ))}
      </div>

      {/* Detail drill-down */}
      <DialogRoot isOpen={selected !== null} onClose={closeDetail} maxWidth="max-w-4xl">
        {selected && (
          <DialogPanel
            title={
              <div className="flex items-center gap-3 min-w-0">
                <div className="w-10 h-10 bg-[#FF990014] border border-[#FF990033] rounded-lg flex items-center justify-center flex-shrink-0">
                  <Icon icon="mdi:aws" width={18} color="#FF9900" />
                </div>
                <div className="min-w-0">
                  <p className="text-[15px] font-bold text-[rgba(0,0,0,0.88)] truncate">
                    {selected.actionType || 'Optimization'} · {selected.currentResourceType || 'Resource'}
                  </p>
                  <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5 truncate font-mono" title={selected.resourceArn || selected.resourceId || undefined}>
                    {selected.resourceArn || resourceLabel(selected)}
                  </p>
                </div>
              </div>
            }
            onClose={closeDetail}
            footer={
              <button
                onClick={closeDetail}
                className="ml-auto px-5 py-2.5 text-[13px] font-semibold text-[rgba(0,0,0,0.65)] bg-white border border-[rgba(0,0,0,0.12)] rounded-xl hover:bg-[rgba(0,0,0,0.02)] transition-colors"
              >
                Close
              </button>
            }
          >
            <div className="overflow-y-auto max-h-[62vh] space-y-5">
              {detailLoading && (
                <div className="flex items-center justify-center py-10 text-[13px] text-[rgba(0,0,0,0.45)]">
                  <Icon icon="mdi:loading" className="animate-spin mr-2" width={18} />
                  Loading detail…
                </div>
              )}

              {!detailLoading && !detail && (
                <div className="text-center py-10">
                  <Icon icon="mdi:information-outline" width={24} color="rgba(0,0,0,0.3)" className="mx-auto mb-2" />
                  <p className="text-[13px] text-[rgba(0,0,0,0.5)]">Detailed breakdown is not available for this recommendation.</p>
                </div>
              )}

              {!detailLoading && detail && (
                <>
                  {/* Cost summary */}
                  <div className="grid grid-cols-3 gap-3">
                    <div className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-xl p-4">
                      <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-[0.07em] mb-1">Current / mo</p>
                      <p className="font-bold text-rose-600 text-[15px]">{fmtMoney(detail.estimatedMonthlyCost, detail.currencyCode)}</p>
                    </div>
                    <div className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-xl p-4">
                      <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-[0.07em] mb-1">Savings / mo</p>
                      <p className="font-bold text-emerald-600 text-[15px]">{fmtMoney(detail.estimatedMonthlySavings, detail.currencyCode)}</p>
                    </div>
                    <div className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-xl p-4">
                      <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-[0.07em] mb-1">Savings %</p>
                      <p className="font-bold text-[rgba(0,0,0,0.88)] text-[15px]">{fmtPct(detail.estimatedSavingsPercentage)}</p>
                    </div>
                  </div>

                  {/* Meta badges */}
                  <div className="flex items-center flex-wrap gap-2">
                    {detail.implementationEffort && (
                      <span className="inline-flex items-center gap-1 text-[12px] text-[rgba(0,0,0,0.6)]">
                        Effort: <EffortBadge effort={detail.implementationEffort} />
                      </span>
                    )}
                    {detail.restartNeeded && (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold bg-amber-50 text-amber-700 border border-amber-200">
                        <Icon icon="mdi:restart" width={11} /> Restart needed
                      </span>
                    )}
                    {detail.rollbackPossible && (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold bg-sky-50 text-sky-700 border border-sky-200">
                        <Icon icon="mdi:backup-restore" width={11} /> Rollback possible
                      </span>
                    )}
                    {detail.recommendationLookbackPeriodInDays !== null && detail.recommendationLookbackPeriodInDays !== undefined && (
                      <span className="text-[11px] text-[rgba(0,0,0,0.45)]">
                        Lookback: {detail.recommendationLookbackPeriodInDays}d
                      </span>
                    )}
                  </div>

                  {/* Current vs recommended config */}
                  <div className="grid grid-cols-2 gap-4">
                    <ConfigCard
                      title="Current"
                      summary={detail.currentResourceSummary}
                      resourceType={detail.currentResourceType}
                      details={detail.currentResourceDetails}
                      accent="rose"
                    />
                    <ConfigCard
                      title="Recommended"
                      summary={detail.recommendedResourceSummary}
                      resourceType={detail.recommendedResourceType}
                      details={detail.recommendedResourceDetails}
                      accent="emerald"
                    />
                  </div>

                  {/* Tags */}
                  {detail.tags && Object.keys(detail.tags).length > 0 && (
                    <div>
                      <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-[0.07em] mb-2">Tags</p>
                      <div className="flex items-center flex-wrap gap-2">
                        {Object.entries(detail.tags).map(([k, v]) => (
                          <span key={k} className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.65)]">
                            <span className="font-semibold">{k}</span>
                            {v ? <span>: {v}</span> : null}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}

                  {detail.lastRefreshTimestamp && (
                    <p className="text-[11px] text-[rgba(0,0,0,0.4)]">
                      Last refreshed: {detail.lastRefreshTimestamp}
                    </p>
                  )}
                </>
              )}
            </div>
          </DialogPanel>
        )}
      </DialogRoot>
    </>
  );
};

interface ConfigCardProps {
  title: string;
  summary: string | null;
  resourceType: string | null;
  details: Record<string, string>;
  accent: 'rose' | 'emerald';
}

const ConfigCard: React.FC<ConfigCardProps> = ({ title, summary, resourceType, details, accent }) => {
  const accentClass = accent === 'rose' ? 'text-rose-600' : 'text-emerald-600';
  const entries = Object.entries(details || {});
  return (
    <div className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-xl p-4">
      <p className={`text-[11px] font-semibold uppercase tracking-[0.07em] mb-1 ${accentClass}`}>{title}</p>
      <p className="font-bold text-[rgba(0,0,0,0.88)] text-[13px] mb-1">{resourceType || '—'}</p>
      {summary && <p className="text-[12px] text-[rgba(0,0,0,0.55)] mb-3">{summary}</p>}
      {entries.length > 0 ? (
        <dl className="space-y-1.5">
          {entries.map(([k, v]) => (
            <div key={k} className="flex items-start justify-between gap-3 text-[12px]">
              <dt className="text-[rgba(0,0,0,0.45)] font-mono truncate" title={k}>{k}</dt>
              <dd className="text-[rgba(0,0,0,0.8)] text-right break-all">{v}</dd>
            </div>
          ))}
        </dl>
      ) : (
        <p className="text-[12px] text-[rgba(0,0,0,0.35)] italic">No configuration detail reported.</p>
      )}
    </div>
  );
};

export default CohRecommendationsTable;
