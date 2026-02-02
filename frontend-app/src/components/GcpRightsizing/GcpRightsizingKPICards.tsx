/**
 * GcpRightsizingKPICards Component
 *
 * KPI cards displaying key metrics for GCP rightsizing recommendations
 */

import React, { useMemo } from 'react';
import type { GcpRightsizingRecommendation } from '../../types/gcpRightsizing';

interface GcpRightsizingKPICardsProps {
  recommendations: GcpRightsizingRecommendation[];
  isLoading: boolean;
}

export const GcpRightsizingKPICards: React.FC<GcpRightsizingKPICardsProps> = ({
  recommendations,
  isLoading,
}) => {
  // Calculate metrics
  const potentialSavings = useMemo(() => {
    return recommendations
      .filter(rec => rec.recommendationType === 'COST_SAVINGS')
      .reduce((total, rec) => total + (rec.monthlySavings || 0), 0);
  }, [recommendations]);

  const totalCurrentCosts = useMemo(() => {
    return recommendations
      .filter(rec => rec.recommendationType === 'PERFORMANCE_IMPROVEMENT')
      .reduce((total, rec) => total + (rec.monthlySavings || 0), 0);
  }, [recommendations]);

  const recommendationCount = recommendations.length;

  const topService = useMemo(() => {
    if (recommendationCount === 0) return 'N/A';
    const counts = recommendations.reduce((acc, r) => {
      const service = r.service || 'Other';
      acc[service] = (acc[service] || 0) + 1;
      return acc;
    }, {} as Record<string, number>);
    return Object.keys(counts).reduce((a, b) => counts[a] > counts[b] ? a : b);
  }, [recommendations, recommendationCount]);

  // Format currency
  const formatCurrency = (value: number): string => {
    if (value === null || value === undefined || isNaN(value)) return '₹0.00';
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: 'INR',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  };

  if (isLoading) {
    return (
      <div className="gcp-rightsizing-kpi-cards">
        {[...Array(4)].map((_, i) => (
          <div key={i} className="gcp-rightsizing-skeleton-card"></div>
        ))}
      </div>
    );
  }

  return (
    <div className="gcp-rightsizing-kpi-cards">
      {/* Potential Savings Card */}
      <div className="gcp-rightsizing-kpi-card">
        <div className="gcp-rightsizing-kpi-content">
          <h3>Potential Savings</h3>
          <p className="gcp-rightsizing-kpi-value savings">
            {formatCurrency(potentialSavings)}
          </p>
          <p className="gcp-rightsizing-kpi-label">per month</p>
        </div>
        <div className="gcp-rightsizing-kpi-icon savings">
          <i className="fas fa-piggy-bank"></i>
        </div>
      </div>

      {/* Current Costs Card */}
      <div className="gcp-rightsizing-kpi-card">
        <div className="gcp-rightsizing-kpi-content">
          <h3>Current Costs</h3>
          <p className="gcp-rightsizing-kpi-value warning">
            {formatCurrency(totalCurrentCosts)}
          </p>
          <p className="gcp-rightsizing-kpi-label">underprovisioned resources</p>
        </div>
        <div className="gcp-rightsizing-kpi-icon warning">
          <i className="fas fa-chart-line"></i>
        </div>
      </div>

      {/* Recommendations Count Card */}
      <div className="gcp-rightsizing-kpi-card">
        <div className="gcp-rightsizing-kpi-content">
          <h3>Recommendations</h3>
          <p className="gcp-rightsizing-kpi-value info">
            {recommendationCount}
          </p>
          <p className="gcp-rightsizing-kpi-label">optimization opportunities</p>
        </div>
        <div className="gcp-rightsizing-kpi-icon info">
          <i className="fas fa-lightbulb"></i>
        </div>
      </div>

      {/* Top Service Card */}
      <div className="gcp-rightsizing-kpi-card">
        <div className="gcp-rightsizing-kpi-content">
          <h3>Top Service</h3>
          <p className="gcp-rightsizing-kpi-value service">
            {topService}
          </p>
          <p className="gcp-rightsizing-kpi-label">needs optimization</p>
        </div>
        <div className="gcp-rightsizing-kpi-icon service">
          <i className="fas fa-crown"></i>
        </div>
      </div>
    </div>
  );
};

export default GcpRightsizingKPICards;
