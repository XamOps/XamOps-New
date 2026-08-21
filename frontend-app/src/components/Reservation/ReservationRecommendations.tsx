import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type {
  PurchaseRecommendation,
  RecommendationFilters,
  SavingsPlanRecommendation,
  SpRecommendationFilters,
} from '../../types/reservation';

interface ReservationRecommendationsProps {
  recommendations: PurchaseRecommendation[];
  filters: RecommendationFilters;
  isLoading: boolean;
  /** True when at least one reservable service could not be queried. */
  isIncomplete?: boolean;
  onFilterChange: (filters: RecommendationFilters) => void;
  onFetchRecommendations: () => void;
  spRecommendations: SavingsPlanRecommendation[];
  spFilters: SpRecommendationFilters;
  isSpLoading: boolean;
  onSpFilterChange: (filters: SpRecommendationFilters) => void;
  onFetchSpRecommendations: () => void;
}

type RecTab = 'ri' | 'sp';

/**
 * Renders the term carried by a recommendation. Cost Explorer returns the enum name, but
 * older cached rows may hold a bare year count, so both shapes are handled.
 */
const formatTerm = (term?: string): string => {
  if (!term) return '—';
  const normalized = term.toUpperCase();
  if (normalized.includes('THREE') || normalized === '3') return '3 yr';
  if (normalized.includes('ONE') || normalized === '1') return '1 yr';
  return term;
};

const formatPaymentOption = (paymentOption: string): string =>
  paymentOption.replace(/_/g, ' ').toLowerCase();

const ReservationRecommendations: React.FC<ReservationRecommendationsProps> = ({
  recommendations,
  filters,
  isLoading,
  isIncomplete = false,
  onFilterChange,
  onFetchRecommendations,
  spRecommendations,
  spFilters,
  isSpLoading,
  onSpFilterChange,
  onFetchSpRecommendations,
}) => {
  const [recTab, setRecTab] = useState<RecTab>('ri');

  const formatCurrency = (val: string, isHourly = true): string => {
    if (!val) return 'N/A';
    const numStr = String(val).replace(/[^0-9.]/g, '');
    const num = parseFloat(numStr);
    if (isNaN(num)) return val;
    const decimals = isHourly && num < 1 ? 4 : 2;
    return (
      '$' +
      num.toLocaleString(undefined, {
        minimumFractionDigits: decimals,
        maximumFractionDigits: decimals,
      }) +
      (isHourly ? '/hr' : '')
    );
  };

  const handleRiFilterUpdate = (key: keyof RecommendationFilters, value: string) => {
    onFilterChange({ ...filters, [key]: value });
  };

  const handleSpFilterUpdate = (key: keyof SpRecommendationFilters, value: string) => {
    onSpFilterChange({ ...spFilters, [key]: value });
  };

  const spTypeLabel = (t: string) => {
    if (t === 'EC2_INSTANCE_SP') return 'EC2 Instance SP';
    if (t === 'SAGEMAKER_SP') return 'SageMaker SP';
    return 'Compute SP';
  };

  const selectCls = 'block w-full text-sm rounded-md bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] py-2 font-medium focus:outline-none focus:border-[rgba(0,0,0,0.2)]';
  const labelCls = 'block text-[10px] font-bold text-[rgba(0,0,0,0.5)] uppercase tracking-wider mb-1.5';

  return (
    <>
      {/* RI / SP sub-tab toggle */}
      <div className="flex bg-[rgba(0,0,0,0.04)] p-1 rounded-xl border border-[rgba(0,0,0,0.08)] w-fit mb-4">
        <button
          onClick={() => setRecTab('ri')}
          className={`px-5 py-2 text-xs font-bold rounded-lg transition-all flex items-center gap-1.5 ${
            recTab === 'ri' ? 'bg-[rgba(0,0,0,0.88)] text-white' : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.7)]'
          }`}
        >
          <Icon icon="mdi:server-outline" width={13} />
          RI Recommendations
        </button>
        <button
          onClick={() => setRecTab('sp')}
          className={`px-5 py-2 text-xs font-bold rounded-lg transition-all flex items-center gap-1.5 ${
            recTab === 'sp' ? 'bg-[rgba(0,0,0,0.88)] text-white' : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.7)]'
          }`}
        >
          <Icon icon="mdi:piggy-bank-outline" width={13} />
          Savings Plans
        </button>
      </div>

      {/* RI Tab */}
      {recTab === 'ri' && (
        <>
          <div className="rounded-xl border border-[rgba(0,0,0,0.08)] p-5 mb-4">
            <div className="flex items-center justify-between mb-4">
              <div>
                <h3 className="text-base font-bold text-[rgba(0,0,0,0.88)] flex items-center gap-2">
                  <Icon icon="mdi:tune-variant" className="text-purple-600" width={18} />
                  Recommendation Parameters
                </h3>
                <p className="text-xs text-[rgba(0,0,0,0.5)] mt-1">Customize your RI purchase recommendations</p>
              </div>
              {isLoading && (
                <div className="flex items-center gap-2 px-3 py-1.5 bg-indigo-50 rounded-lg">
                  <Icon icon="mdi:loading" className="animate-spin text-indigo-600" width={14} />
                  <span className="text-xs font-semibold text-indigo-700">Recalculating...</span>
                </div>
              )}
            </div>
            <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
              <div>
                <label className={labelCls}>Term Length</label>
                <select value={filters.term} onChange={(e) => { handleRiFilterUpdate('term', e.target.value); onFetchRecommendations(); }} className={selectCls}>
                  <option value="ONE_YEAR">1 Year</option>
                  <option value="THREE_YEARS">3 Years</option>
                </select>
              </div>
              <div>
                <label className={labelCls}>Payment Option</label>
                <select value={filters.paymentOption} onChange={(e) => { handleRiFilterUpdate('paymentOption', e.target.value); onFetchRecommendations(); }} className={selectCls}>
                  <option value="NO_UPFRONT">No Upfront</option>
                  <option value="PARTIAL_UPFRONT">Partial Upfront</option>
                  <option value="ALL_UPFRONT">All Upfront</option>
                </select>
              </div>
              <div>
                <label className={labelCls}>Lookback Period</label>
                <select value={filters.lookback} onChange={(e) => { handleRiFilterUpdate('lookback', e.target.value); onFetchRecommendations(); }} className={selectCls}>
                  <option value="SEVEN_DAYS">Past 7 Days</option>
                  <option value="THIRTY_DAYS">Past 30 Days</option>
                  <option value="SIXTY_DAYS">Past 60 Days</option>
                </select>
              </div>
              <div>
                <label className={labelCls}>Offering Class</label>
                <select value={filters.offeringClass} onChange={(e) => { handleRiFilterUpdate('offeringClass', e.target.value); onFetchRecommendations(); }} className={selectCls}>
                  <option value="STANDARD">Standard</option>
                  <option value="CONVERTIBLE">Convertible</option>
                </select>
              </div>
            </div>
          </div>

          <div className="rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
            <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
              <h3 className="text-base font-bold text-[rgba(0,0,0,0.88)] flex items-center gap-2">
                <Icon icon="mdi:lightbulb-outline" className="text-green-600" width={18} />
                RI Purchase Recommendations
              </h3>
              <p className="text-xs text-[rgba(0,0,0,0.5)] mt-0.5">Optimized Reserved Instance purchase suggestions</p>
            </div>
            <table className="w-full text-sm text-left pro-table">
              <thead className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                <tr>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Instance Family</th>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Region</th>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Platform</th>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Terms</th>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">On-Demand Cost</th>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Est. Monthly Cost</th>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Potential Savings</th>
                </tr>
              </thead>
              <tbody>
                {isLoading ? (
                  <tr>
                    <td colSpan={7} className="text-center py-16">
                      <div className="flex flex-col items-center gap-3">
                        <Icon icon="mdi:loading" className="animate-spin text-[rgba(0,0,0,0.4)]" width={24} />
                        <div className="text-[rgba(0,0,0,0.6)] font-semibold text-sm">Analyzing usage patterns...</div>
                        <div className="text-[rgba(0,0,0,0.4)] text-xs">This may take a moment</div>
                      </div>
                    </td>
                  </tr>
                ) : recommendations && recommendations.length > 0 ? (
                  recommendations.map((rec) => (
                    <tr key={`${rec.instanceFamily}-${rec.region}`} className="hover:bg-[rgba(0,0,0,0.025)] border-b border-[rgba(0,0,0,0.04)]">
                      <td className="px-6 py-4">
                        <div className="font-bold text-[rgba(0,0,0,0.88)]">{rec.instanceFamily}</div>
                        <div className="text-xs text-[rgba(0,0,0,0.5)] mt-0.5 flex items-center gap-1">
                          <Icon icon="mdi:server-outline" width={10} />
                          <span>{rec.recommendedInstances} instances</span>
                        </div>
                      </td>
                      <td className="px-6 py-4 font-medium text-[rgba(0,0,0,0.7)]">{rec.region}</td>
                      <td className="px-6 py-4 font-medium text-[rgba(0,0,0,0.7)]">{rec.platform}</td>
                      {/* Term comes from the recommendation itself, not the filter state. Reading
                          the filters here meant the label always agreed with the dropdowns and
                          could never reveal a mismatch — and during a fetch it labelled the
                          previous result set with the newly chosen filters. */}
                      <td className="px-6 py-4">
                        <span className="text-xs font-semibold bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.7)] rounded-md px-2.5 py-1 inline-block">
                          {formatTerm(rec.term)} / {formatPaymentOption(filters.paymentOption)}
                        </span>
                      </td>
                      <td className="px-6 py-4 line-through text-[rgba(0,0,0,0.35)] font-medium">{formatCurrency(rec.onDemandCost, false)}</td>
                      <td className="px-6 py-4 font-bold text-[rgba(0,0,0,0.88)]">{formatCurrency(rec.estimatedMonthlyCost, false)}</td>
                      <td className="px-6 py-4">
                        <div className="flex items-center gap-1.5">
                          <Icon icon="mdi:arrow-down" className="text-green-600" width={14} />
                          <span className="font-bold text-green-600">{formatCurrency(rec.monthlySavings, false)}</span>
                        </div>
                        <div className="text-[10px] font-bold text-green-700 bg-green-50 border border-green-100 px-2 py-0.5 rounded-full inline-block mt-1">PER MONTH</div>
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={7} className="text-center py-16">
                      {/* An empty result is only an all-clear when every service was reachable.
                          Presenting a failed lookup as "Fully Optimized" hid real savings. */}
                      {isIncomplete ? (
                        <div className="flex flex-col items-center">
                          <div className="w-12 h-12 bg-amber-50 border border-amber-200 rounded-xl flex items-center justify-center mb-3">
                            <Icon icon="mdi:alert-outline" className="text-amber-600" width={24} aria-hidden="true" />
                          </div>
                          <p className="text-[rgba(0,0,0,0.88)] font-bold">Couldn&apos;t check all services</p>
                          <p className="text-[rgba(0,0,0,0.5)] text-xs mt-1 max-w-sm text-center">
                            AWS Cost Explorer didn&apos;t answer for every reservable service, so this
                            isn&apos;t a complete picture. Hit Refresh to try again.
                          </p>
                        </div>
                      ) : (
                        <div className="flex flex-col items-center">
                          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mb-3">
                            <Icon icon="mdi:check-circle-outline" className="text-green-600" width={24} aria-hidden="true" />
                          </div>
                          <p className="text-[rgba(0,0,0,0.88)] font-bold">Fully Optimized</p>
                          <p className="text-[rgba(0,0,0,0.5)] text-xs mt-1 max-w-sm text-center">No additional RI opportunities found. Try adjusting filters to explore other scenarios.</p>
                        </div>
                      )}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}

      {/* SP Tab */}
      {recTab === 'sp' && (
        <>
          <div className="rounded-xl border border-[rgba(0,0,0,0.08)] p-5 mb-4">
            <div className="flex items-center justify-between mb-4">
              <div>
                <h3 className="text-base font-bold text-[rgba(0,0,0,0.88)] flex items-center gap-2">
                  <Icon icon="mdi:tune-variant" className="text-amber-600" width={18} />
                  Savings Plans Parameters
                </h3>
                <p className="text-xs text-[rgba(0,0,0,0.5)] mt-1">Customize your Savings Plans recommendations</p>
              </div>
              {isSpLoading && (
                <div className="flex items-center gap-2 px-3 py-1.5 bg-amber-50 rounded-lg">
                  <Icon icon="mdi:loading" className="animate-spin text-amber-600" width={14} />
                  <span className="text-xs font-semibold text-amber-700">Recalculating...</span>
                </div>
              )}
            </div>
            <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
              <div>
                <label className={labelCls}>Plan Type</label>
                <select value={spFilters.spType} onChange={(e) => { handleSpFilterUpdate('spType', e.target.value); onFetchSpRecommendations(); }} className={selectCls}>
                  <option value="COMPUTE_SP">Compute SP</option>
                  <option value="EC2_INSTANCE_SP">EC2 Instance SP</option>
                  <option value="SAGEMAKER_SP">SageMaker SP</option>
                </select>
              </div>
              <div>
                <label className={labelCls}>Term Length</label>
                <select value={spFilters.term} onChange={(e) => { handleSpFilterUpdate('term', e.target.value); onFetchSpRecommendations(); }} className={selectCls}>
                  <option value="ONE_YEAR">1 Year</option>
                  <option value="THREE_YEARS">3 Years</option>
                </select>
              </div>
              <div>
                <label className={labelCls}>Payment Option</label>
                <select value={spFilters.paymentOption} onChange={(e) => { handleSpFilterUpdate('paymentOption', e.target.value); onFetchSpRecommendations(); }} className={selectCls}>
                  <option value="NO_UPFRONT">No Upfront</option>
                  <option value="PARTIAL_UPFRONT">Partial Upfront</option>
                  <option value="ALL_UPFRONT">All Upfront</option>
                </select>
              </div>
              <div>
                <label className={labelCls}>Lookback Period</label>
                <select value={spFilters.lookback} onChange={(e) => { handleSpFilterUpdate('lookback', e.target.value); onFetchSpRecommendations(); }} className={selectCls}>
                  <option value="SEVEN_DAYS">Past 7 Days</option>
                  <option value="THIRTY_DAYS">Past 30 Days</option>
                  <option value="SIXTY_DAYS">Past 60 Days</option>
                </select>
              </div>
            </div>
          </div>

          <div className="rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
            <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
              <h3 className="text-base font-bold text-[rgba(0,0,0,0.88)] flex items-center gap-2">
                <Icon icon="mdi:piggy-bank-outline" className="text-amber-600" width={18} />
                Savings Plans Purchase Recommendations
              </h3>
              <p className="text-xs text-[rgba(0,0,0,0.5)] mt-0.5">Recommended commitment-based discount plans based on your usage</p>
            </div>
            <table className="w-full text-sm text-left pro-table">
              <thead className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                <tr>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Plan Type</th>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Region</th>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Instance Family</th>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Recommended $/hr</th>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Current On-Demand</th>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Est. Monthly Cost</th>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Est. Monthly Savings</th>
                  <th className="px-6 py-3 text-[rgba(0,0,0,0.4)]">Savings %</th>
                </tr>
              </thead>
              <tbody>
                {isSpLoading ? (
                  <tr>
                    <td colSpan={8} className="text-center py-16">
                      <div className="flex flex-col items-center gap-3">
                        <Icon icon="mdi:loading" className="animate-spin text-[rgba(0,0,0,0.4)]" width={24} />
                        <div className="text-[rgba(0,0,0,0.6)] font-semibold text-sm">Analyzing usage patterns...</div>
                        <div className="text-[rgba(0,0,0,0.4)] text-xs">This may take a moment</div>
                      </div>
                    </td>
                  </tr>
                ) : spRecommendations && spRecommendations.length > 0 ? (
                  spRecommendations.map((rec, idx) => (
                    <tr key={`${rec.savingsPlanType}-${rec.region}-${idx}`} className="hover:bg-[rgba(0,0,0,0.025)] border-b border-[rgba(0,0,0,0.04)]">
                      <td className="px-6 py-4">
                        <span className="text-xs font-semibold bg-amber-50 border border-amber-100 text-amber-700 rounded-md px-2.5 py-1 inline-block">
                          {spTypeLabel(rec.savingsPlanType)}
                        </span>
                      </td>
                      <td className="px-6 py-4 font-medium text-[rgba(0,0,0,0.7)]">{rec.region || 'All Regions'}</td>
                      <td className="px-6 py-4 font-medium text-[rgba(0,0,0,0.7)]">{rec.instanceFamily || '—'}</td>
                      <td className="px-6 py-4 font-bold text-[rgba(0,0,0,0.88)]">{formatCurrency(rec.recommendedCommitment)}</td>
                      <td className="px-6 py-4 text-[rgba(0,0,0,0.5)] font-medium">{formatCurrency(rec.currentAverageHourlyOnDemandSpend)}</td>
                      <td className="px-6 py-4 font-bold text-[rgba(0,0,0,0.88)]">{formatCurrency(rec.estimatedMonthlyCost, false)}</td>
                      <td className="px-6 py-4">
                        <div className="flex items-center gap-1.5">
                          <Icon icon="mdi:arrow-down" className="text-green-600" width={14} />
                          <span className="font-bold text-green-600">{formatCurrency(rec.estimatedMonthlySavings, false)}</span>
                        </div>
                        <div className="text-[10px] font-bold text-green-700 bg-green-50 border border-green-100 px-2 py-0.5 rounded-full inline-block mt-1">PER MONTH</div>
                      </td>
                      <td className="px-6 py-4 font-bold text-green-600">
                        {parseFloat(rec.estimatedSavingsPercentage || '0').toFixed(1)}%
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={8} className="text-center py-16">
                      <div className="flex flex-col items-center">
                        <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mb-3">
                          <Icon icon="mdi:check-circle-outline" className="text-green-600" width={24} />
                        </div>
                        <p className="text-[rgba(0,0,0,0.88)] font-bold">Fully Optimized</p>
                        <p className="text-[rgba(0,0,0,0.5)] text-xs mt-1 max-w-sm text-center">No Savings Plans opportunities found. Try adjusting filters to explore other scenarios.</p>
                      </div>
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}
    </>
  );
};

export default ReservationRecommendations;
