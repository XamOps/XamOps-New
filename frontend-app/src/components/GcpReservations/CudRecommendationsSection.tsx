/**
 * CUD Recommendations Section Component
 *
 * Displays CUD purchase recommendations with detailed insights
 */

import React from 'react';
import type { GcpCudRecommendation } from '../../types/gcpReservations';

interface CudRecommendationsSectionProps {
  recommendations: GcpCudRecommendation[];
  isLoading: boolean;
}

const CudRecommendationsSection: React.FC<CudRecommendationsSectionProps> = ({
  recommendations,
  isLoading,
}) => {
  const formatCurrency = (value: number | undefined): string => {
    if (value === null || value === undefined || isNaN(value)) return '₹0.00';
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: 'INR',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  };

  const extractTermFromDescription = (machineType: string | undefined): string => {
    if (!machineType) return '3 years';
    if (machineType.includes('3-year') || machineType.includes('3 year')) return '3 years';
    if (machineType.includes('1-year') || machineType.includes('1 year')) return '1 year';
    return '3 years';
  };

  const calculateCudCost = (rec: GcpCudRecommendation): number => {
    const savings = Math.abs(rec.monthlySavings || 0);
    const estimatedOnDemand = savings / 0.55;
    const cudCost = estimatedOnDemand * 0.45;
    return cudCost;
  };

  const getProjectFromRecommendation = (rec: GcpCudRecommendation): string => {
    if (rec.recommendationId) {
      const match = rec.recommendationId.match(/projects\/([^\/]+)\//);
      if (match) return match[1];
    }
    return 'XamOps';
  };

  /**
   * Break-even blurb for a recommendation. Rendered as JSX rather than an HTML
   * string so there is no innerHTML sink on this path at all.
   */
  const renderUsageInsight = (rec: GcpCudRecommendation): React.ReactNode => {
    const term = extractTermFromDescription(rec.currentMachineType);
    const termYears = term.includes('3') ? 3 : 1;
    const breakEvenDays = Math.round(termYears * 365 * 0.45);
    const breakEvenPercent = 45;

    return (
      <>
        With this CUD purchase, if you use <strong>1 GB</strong> for at least{' '}
        <strong>{breakEvenDays} days</strong> in <strong>{termYears} years</strong>{' '}
        (<strong>{breakEvenPercent}%</strong> of CUD term), you will still break even.{' '}
        <a
          href="https://cloud.google.com/docs/cuds-recommender#understanding-summary-cards"
          target="_blank"
          rel="noopener noreferrer"
          className="usage-insight-link"
        >
          Learn more
        </a>
      </>
    );
  };

  return (
    <div className="glass-card">
      <div className="section-header">
        <h2 className="section-title">CUD Recommendations</h2>
        <span className="badge badge-warning">
          {recommendations?.length || 0} Recommendations
        </span>
      </div>

      {isLoading && (
        <div className="loading-skeleton">
          <div className="skeleton skeleton-large"></div>
          <div className="skeleton skeleton-large"></div>
        </div>
      )}

      {!isLoading && (!recommendations || recommendations.length === 0) && (
        <div className="empty-state">
          <i className="fas fa-lightbulb empty-state-icon text-yellow-400"></i>
          <p className="empty-state-title">No CUD Recommendations Available</p>
          <p className="empty-state-subtitle">Google Cloud hasn't generated any commitment recommendations yet.</p>
          <p className="empty-state-text">This typically requires consistent usage patterns over time.</p>
        </div>
      )}

      {!isLoading && recommendations && recommendations.length > 0 && (
        <div className="recommendations-list">
          {recommendations.map((rec, index) => (
            <div key={index} className="recommendation-card">
              <div className="recommendation-header">
                <div className="recommendation-badges">
                  <span className="badge-recommended">
                    <i className="fas fa-star"></i>
                    RECOMMENDED CUD
                  </span>
                  <i className="fas fa-link recommendation-link-icon"></i>
                </div>
                <span className="recommendation-selected">
                  <i className="fas fa-check-circle"></i>
                  Selected
                </span>
              </div>

              <h3 className="recommendation-title">
                Save {formatCurrency(Math.abs(rec.monthlySavings || 0))}/month by purchasing a{' '}
                {extractTermFromDescription(rec.currentMachineType)}{' '}
                {rec.currentMachineType || 'resource-based CUD'}
              </h3>

              <div className="recommendation-details">
                <div className="detail-row">
                  <span className="detail-label">Resources covered</span>
                  <span className="detail-value">
                    Eligible {rec.currentMachineType || 'usage'} in{' '}
                    {rec.location || 'us-east1'}, for project{' '}
                    {getProjectFromRecommendation(rec)}
                  </span>
                </div>
                <div className="detail-row">
                  <span className="detail-label">Basis for recommendation</span>
                  <span className="detail-value">Last 30 days of historical usage</span>
                </div>
                <div className="detail-row">
                  <span className="detail-label">Eligible usage to be covered</span>
                  <span className="detail-value">
                    100% based on <span className="configuration-link">configuration</span>
                  </span>
                </div>
                <div className="detail-row">
                  <span className="detail-label">CUD term</span>
                  <span className="detail-value">
                    {extractTermFromDescription(rec.currentMachineType)} (billed monthly)
                  </span>
                </div>
                <div className="detail-row">
                  <span className="detail-label">Estimated CUD cost</span>
                  <span className="detail-value">
                    {formatCurrency(calculateCudCost(rec))}/month
                    <span className="detail-value-sub">
                      ({formatCurrency(calculateCudCost(rec) / 730)}/hr)
                    </span>
                    <br />
                    <span className="savings-text">
                      55% less than on-demand price
                    </span>
                  </span>
                </div>
                <div className="detail-row">
                  <span className="detail-label">Estimated savings</span>
                  <span className="detail-value savings-value">
                    {formatCurrency(Math.abs(rec.monthlySavings || 0))}/month*
                  </span>
                </div>
                <div className="detail-row detail-row-last">
                  <span className="detail-label">Usage insights</span>
                  <span className="detail-value detail-value-insight">
                    {renderUsageInsight(rec)}
                  </span>
                </div>
              </div>

              <div className="recommendation-info-box">
                <i className="fas fa-info-circle recommendation-info-icon"></i>
                <strong>Details:</strong>{' '}
                {rec.reason || rec.description || 'No additional details available'}
              </div>

              <div className="recommendation-disclaimer">
                <i className="fas fa-info-circle"></i>
                * If applicable, the recommendation takes into account existing CUDs, sustained discounts,
                custom machine surcharges and negotiated pricing. The lowest on-demand rate for the SKUs is considered.
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default CudRecommendationsSection;
