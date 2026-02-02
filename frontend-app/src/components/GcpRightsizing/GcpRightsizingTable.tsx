/**
 * GcpRightsizingTable Component
 *
 * Main table displaying GCP rightsizing recommendations with filtering and search
 */

import React, { useState, useMemo } from 'react';
import type { GcpRightsizingRecommendation, ServiceFilter } from '../../types/gcpRightsizing';

interface GcpRightsizingTableProps {
  recommendations: GcpRightsizingRecommendation[];
  isLoading: boolean;
  onRefresh: () => void;
}

const SERVICES: ServiceFilter[] = ['All', 'Compute Engine', 'Cloud SQL', 'Commitment'];

export const GcpRightsizingTable: React.FC<GcpRightsizingTableProps> = ({
  recommendations,
  isLoading,
  onRefresh,
}) => {
  const [filter, setFilter] = useState<ServiceFilter>('All');
  const [searchTerm, setSearchTerm] = useState('');

  // Filter recommendations
  const filteredRecommendations = useMemo(() => {
    const term = searchTerm.toLowerCase();
    return recommendations.filter(rec => {
      const serviceMatch = filter === 'All' || (rec.service && rec.service === filter);
      const searchMatch = term === '' || (rec.resourceName && rec.resourceName.toLowerCase().includes(term));
      return serviceMatch && searchMatch;
    });
  }, [recommendations, filter, searchTerm]);

  // Helper functions
  const isUnderprovisioned = (rec: GcpRightsizingRecommendation): boolean => {
    return rec.recommendationType === 'PERFORMANCE_IMPROVEMENT';
  };

  const formatImpact = (rec: GcpRightsizingRecommendation): string => {
    const value = rec.monthlySavings || 0;
    return formatCurrency(Math.abs(value)) + ' / mo';
  };

  const getReasonText = (rec: GcpRightsizingRecommendation): string => {
    if (rec.reason && rec.reason !== 'No details available') {
      return rec.reason;
    }

    if (rec.recommendationType === 'PERFORMANCE_IMPROVEMENT') {
      return 'Resource is underprovisioned. Upgrade recommended for better performance.';
    } else if (rec.recommendationType === 'COST_SAVINGS') {
      return 'Resource is overprovisioned. Downgrade recommended to reduce costs.';
    }

    return rec.description || 'Optimization recommended';
  };

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
    return <div className="gcp-rightsizing-skeleton-table"></div>;
  }

  return (
    <div className="gcp-rightsizing-table-container">
      {/* Header Section */}
      <div className="gcp-rightsizing-table-header">
        <div className="gcp-rightsizing-table-title">
          <div className="gcp-rightsizing-table-title-icon">
            <i className="fas fa-list"></i>
          </div>
          <div>
            <h2>Optimization Recommendations</h2>
            <p>Right-size your resources for optimal performance and cost</p>
          </div>
        </div>
      </div>

      {/* Filters Section */}
      <div className="gcp-rightsizing-filters">
        <div className="gcp-rightsizing-filter-group">
          <div className="gcp-rightsizing-filter-label">
            <i className="fas fa-filter"></i>
            <span>Filter by service:</span>
          </div>
          <div className="gcp-rightsizing-filter-buttons">
            {SERVICES.map(service => (
              <button
                key={service}
                onClick={() => setFilter(service)}
                className={`gcp-rightsizing-filter-btn ${filter === service ? 'active' : ''}`}
              >
                {service}
              </button>
            ))}
          </div>
        </div>

        <div className="gcp-rightsizing-search">
          <i className="fas fa-search"></i>
          <input
            type="text"
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder="Search by resource name..."
            className="gcp-rightsizing-search-input"
          />
        </div>
      </div>

      {/* Empty State */}
      {filteredRecommendations.length === 0 && (
        <div className="gcp-rightsizing-empty-state">
          <div className="gcp-rightsizing-empty-icon">
            <i className="fas fa-check-circle"></i>
          </div>
          <h3>All Optimized!</h3>
          <p>No rightsizing recommendations found. Your resources are well-optimized.</p>
          <button onClick={onRefresh} className="gcp-rightsizing-refresh-empty-btn">
            <i className="fas fa-sync-alt"></i>
            Refresh Analysis
          </button>
        </div>
      )}

      {/* Table */}
      {filteredRecommendations.length > 0 && (
        <div className="gcp-rightsizing-table-wrapper">
          <table className="gcp-rightsizing-table">
            <thead>
              <tr>
                <th>Resource Name</th>
                <th>Service</th>
                <th>Location</th>
                <th>Machine Type Comparison</th>
                <th>Recommendation</th>
                <th>Impact</th>
              </tr>
            </thead>
            <tbody>
              {filteredRecommendations.map((rec, index) => (
                <tr key={rec.recommendationId || `${rec.resourceName}-${index}`}>
                  {/* Resource Name */}
                  <td className="gcp-rightsizing-cell-resource">
                    <div className="gcp-rightsizing-resource-name">
                      <div className="gcp-rightsizing-resource-icon">
                        <i className={rec.service === 'Cloud SQL' ? 'fas fa-database' : 'fas fa-server'}></i>
                      </div>
                      <span>{rec.resourceName}</span>
                    </div>
                  </td>

                  {/* Service */}
                  <td>
                    <div className="gcp-rightsizing-service">
                      <span>{rec.service || 'N/A'}</span>
                      {rec.recommendationType === 'PERFORMANCE_IMPROVEMENT' && (
                        <span className="gcp-rightsizing-performance-badge">
                          <i className="fas fa-bolt"></i>
                          Performance
                        </span>
                      )}
                    </div>
                  </td>

                  {/* Location */}
                  <td>
                    <span className="gcp-rightsizing-location">{rec.location || 'global'}</span>
                  </td>

                  {/* Machine Type Comparison */}
                  <td>
                    <div className="gcp-rightsizing-machine-comparison">
                      <div className="gcp-rightsizing-machine-current">
                        <p className="gcp-rightsizing-machine-type">{rec.currentMachineType || 'N/A'}</p>
                        <p className="gcp-rightsizing-machine-label">Current</p>
                      </div>
                      <div className="gcp-rightsizing-machine-arrow">
                        <i className="fas fa-arrow-right"></i>
                      </div>
                      <div className="gcp-rightsizing-machine-recommended">
                        <p className="gcp-rightsizing-machine-type">{rec.recommendedMachineType || 'N/A'}</p>
                        <p className="gcp-rightsizing-machine-label">Recommended</p>
                      </div>
                    </div>
                  </td>

                  {/* Recommendation */}
                  <td>
                    <div className="gcp-rightsizing-reason">
                      <i className="fas fa-info-circle"></i>
                      <span>{getReasonText(rec)}</span>
                    </div>
                  </td>

                  {/* Impact */}
                  <td>
                    <div className="gcp-rightsizing-impact">
                      <span className={`gcp-rightsizing-impact-badge ${isUnderprovisioned(rec) ? 'warning' : 'savings'}`}>
                        {formatImpact(rec)}
                      </span>
                      <span className={`gcp-rightsizing-impact-label ${isUnderprovisioned(rec) ? 'warning' : 'savings'}`}>
                        <i className="fas fa-info-circle"></i>
                        {isUnderprovisioned(rec) ? '30-day cost' : 'Potential savings'}
                      </span>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default GcpRightsizingTable;
