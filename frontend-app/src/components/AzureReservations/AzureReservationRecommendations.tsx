import React from 'react';
import type { AzureReservationRecommendation } from '../../types/azureReservations';

interface AzureReservationRecommendationsProps {
  recommendations: AzureReservationRecommendation[];
  /** Set when Azure could not be asked. Distinguishes "nothing to save" from "never asked". */
  unavailableReason?: string | null;
  /** Priced monthly total, excluding anything flagged indicativeOnly. */
  totalMonthlySavings?: number;
  /** How many recommendations Azure returned without a usable savings amount. */
  indicativeRecommendationCount?: number;
  /**
   * Why the headline total should not be trusted as a comparable figure.
   *
   * Set when Azure reported more than one currency, or when this enrollment bills in something other
   * than the USD the cross-provider savings total assumes. Without it a total gets shown that is
   * either summed across currencies or in the wrong unit.
   */
  savingsUnavailableReason?: string | null;
}

/**
 * Formats an amount in the currency Azure reported.
 *
 * Azure's legacy response shape carries no currency code, and the figures are in the enrollment's
 * billing currency — which could be USD, INR, EUR or anything else. This component used to print a
 * hardcoded ₹ on every card, so a USD recommendation was labelled as rupees, an ~85x
 * misstatement. When the code is unknown the number is shown bare and captioned instead.
 */
const formatAmount = (value: number, currency?: string | null): string => {
  const safe = Number.isFinite(value) ? value : 0;
  if (currency) {
    try {
      return new Intl.NumberFormat(undefined, {
        style: 'currency',
        currency,
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      }).format(safe);
    } catch {
      // Unrecognised ISO code — fall through to the bare number rather than inventing a symbol.
    }
  }
  return safe.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
};

/** "P3Y" / "P1Y" as Azure sends them, spelled out for the card. */
const termLabel = (term?: string | null): string => {
  if (!term) return 'Term not stated';
  const match = /^P(\d+)([YM])$/.exec(term.trim().toUpperCase());
  if (!match) return term;
  const count = Number(match[1]);
  const unit = match[2] === 'Y' ? 'year' : 'month';
  return `${count} ${unit}${count === 1 ? '' : 's'}`;
};

const EmptyState: React.FC<{ unavailableReason?: string | null }> = ({ unavailableReason }) => {
  const blocked = Boolean(unavailableReason);

  return (
    <div className="glass-card rounded-xl p-12 text-center shadow-sm">
      <div className="flex flex-col items-center">
        <div
          className={`w-20 h-20 rounded-full flex items-center justify-center mb-5 shadow-sm ${
            blocked
              ? 'bg-gradient-to-br from-amber-100 to-amber-200'
              : 'bg-gradient-to-br from-gray-100 to-gray-200'
          }`}
        >
          <i
            className={`text-3xl ${
              blocked ? 'fas fa-triangle-exclamation text-amber-600' : 'fas fa-circle-check text-gray-400'
            }`}
          ></i>
        </div>
        <h3 className="text-xl font-black text-gray-800 mb-2">
          {blocked ? 'Recommendations Unavailable' : 'No Reservation Savings Found'}
        </h3>
        <p className="text-gray-500 text-sm max-w-lg">
          {blocked
            ? unavailableReason
            : 'Azure analysed this subscription\u2019s usage and did not find workloads running steadily ' +
              'enough for a reservation to pay off. Recommendations appear once a resource has run ' +
              'consistently for several days.'}
        </p>
      </div>
    </div>
  );
};

const AzureReservationRecommendations: React.FC<AzureReservationRecommendationsProps> = ({
  recommendations,
  unavailableReason,
  totalMonthlySavings,
  indicativeRecommendationCount,
  savingsUnavailableReason,
}) => {
  if (recommendations.length === 0) {
    return <EmptyState unavailableReason={unavailableReason} />;
  }

  const pricedTotal =
    typeof totalMonthlySavings === 'number'
      ? totalMonthlySavings
      : recommendations
          .filter((r) => !r.indicativeOnly)
          .reduce((sum, r) => sum + (r.estimatedMonthlySavings || 0), 0);

  const headlineCurrency = recommendations.find((r) => r.currency)?.currency ?? null;
  const unpricedCount =
    typeof indicativeRecommendationCount === 'number'
      ? indicativeRecommendationCount
      : recommendations.filter((r) => r.indicativeOnly).length;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
        <div className="flex items-center gap-2">
          <i className="fas fa-lightbulb text-amber-500"></i>
          <span className="text-sm font-semibold text-gray-600">
            {recommendations.length} reservation{recommendations.length !== 1 ? 's' : ''} Azure
            recommends buying
          </span>
        </div>
        {/* Suppressed when the total cannot be summed honestly, rather than showing a figure that is
            either added across currencies or denominated in one the group total does not use. */}
        {!savingsUnavailableReason && pricedTotal > 0 && (
          <span className="text-sm font-semibold text-gray-600">
            Up to{' '}
            <span className="text-green-600 font-bold">
              {formatAmount(pricedTotal, headlineCurrency)}
            </span>{' '}
            per month
          </span>
        )}
      </div>

      {savingsUnavailableReason && (
        <div className="rounded-lg border border-amber-100 bg-amber-50 px-4 py-3 text-xs text-amber-800">
          {savingsUnavailableReason}
        </div>
      )}

      {/* The backend splits unpriced recommendations out of the headline total on purpose. Saying so
          is the other half of that: otherwise the count silently goes missing from the figure. */}
      {unpricedCount > 0 && (
        <p className="text-xs text-gray-500">
          {unpricedCount} of these {unpricedCount === 1 ? 'is' : 'are'} shown without a savings amount,
          because Azure returned the recommendation but not a figure. {unpricedCount === 1 ? 'It is' : 'They are'}{' '}
          excluded from the total above.
        </p>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        {recommendations.map((rec, idx) => (
          <div
            key={rec.recommendationId || idx}
            className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow border border-blue-50"
          >
            {/* Header */}
            <div className="flex items-start justify-between mb-4">
              <div className="flex items-center gap-3 min-w-0">
                <div className="w-9 h-9 bg-blue-100 rounded-lg flex items-center justify-center flex-shrink-0">
                  <i className="fas fa-cloud text-blue-600 text-sm"></i>
                </div>
                <div className="min-w-0">
                  <h4 className="font-bold text-gray-900 text-sm leading-tight truncate">
                    {rec.sku || rec.serviceName}
                  </h4>
                  <p className="text-xs text-gray-500 mt-0.5 truncate">
                    {rec.serviceName}
                    {rec.region ? ` \u00b7 ${rec.region}` : ''}
                  </p>
                </div>
              </div>
              <span className="ml-2 flex-shrink-0 bg-blue-50 text-blue-700 text-xs font-bold px-2.5 py-1 rounded-full border border-blue-100">
                {termLabel(rec.recommendedTerm)}
              </span>
            </div>

            {/* Quantity and scope */}
            <div className="flex flex-wrap gap-1.5 mb-3">
              {typeof rec.recommendedQuantity === 'number' && rec.recommendedQuantity > 0 && (
                <span className="text-[11px] font-semibold text-gray-600 bg-gray-100 rounded px-2 py-0.5">
                  Buy {rec.recommendedQuantity}
                </span>
              )}
              {rec.scope && (
                <span className="text-[11px] font-semibold text-gray-600 bg-gray-100 rounded px-2 py-0.5">
                  {rec.scope} scope
                </span>
              )}
              {rec.indicativeOnly && (
                <span className="text-[11px] font-semibold text-amber-700 bg-amber-50 border border-amber-200 rounded px-2 py-0.5">
                  Savings not quoted
                </span>
              )}
            </div>

            {/* Cost details */}
            <div className="space-y-2 mb-4">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs text-gray-500">Pay-as-you-go / month</span>
                <span className="text-sm font-bold text-gray-800">
                  {formatAmount(rec.currentMonthlyCost, rec.currency)}
                </span>
              </div>
              {typeof rec.reservedMonthlyCost === 'number' && (
                <div className="flex items-center justify-between gap-2">
                  <span className="text-xs text-gray-500">Reserved / month</span>
                  <span className="text-sm font-bold text-gray-800">
                    {formatAmount(rec.reservedMonthlyCost, rec.currency)}
                  </span>
                </div>
              )}
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs text-gray-500">Estimated savings</span>
                <span
                  className={`text-sm font-bold ${
                    rec.indicativeOnly ? 'text-gray-400' : 'text-green-600'
                  }`}
                >
                  {rec.indicativeOnly
                    ? 'Not quoted'
                    : `${formatAmount(rec.estimatedMonthlySavings, rec.currency)}/mo`}
                </span>
              </div>
            </div>

            {/* Savings bar */}
            {!rec.indicativeOnly && rec.estimatedSavingsPercentage > 0 && (
              <div className="mb-3">
                <div className="flex justify-between text-xs text-gray-500 mb-1">
                  <span>Savings rate</span>
                  <span className="font-semibold text-green-600">
                    {rec.estimatedSavingsPercentage.toFixed(1)}%
                  </span>
                </div>
                <div className="w-full bg-gray-100 rounded-full h-2">
                  <div
                    className="bg-gradient-to-r from-green-400 to-green-600 h-2 rounded-full"
                    style={{
                      width: `${Math.min(Math.max(rec.estimatedSavingsPercentage, 0), 100)}%`,
                    }}
                  ></div>
                </div>
              </div>
            )}

            {/* Alternative term */}
            {rec.alternativeTerm && (
              <p className="text-[11px] text-gray-500 mb-2">
                {termLabel(rec.alternativeTerm)} term:{' '}
                {typeof rec.alternativeTermMonthlySavings === 'number'
                  ? `${formatAmount(rec.alternativeTermMonthlySavings, rec.currency)}/mo`
                  : 'costed but lower'}
              </p>
            )}

            {/* Reason */}
            <p className="text-xs text-gray-500 italic">{rec.reason}</p>

            {!rec.currency && (
              <p className="text-[11px] text-gray-400 mt-2">
                Azure did not state a currency for these figures. They are in this subscription&rsquo;s
                billing currency.
              </p>
            )}
          </div>
        ))}
      </div>
    </div>
  );
};

export default AzureReservationRecommendations;
