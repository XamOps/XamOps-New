/**
 * Active CUDs Section Component
 *
 * Displays active Committed Use Discounts
 */

import React from 'react';
import type { GcpActiveCud } from '../../types/gcpReservations';

interface ActiveCudsSectionProps {
  activeCuds: GcpActiveCud[];
  isLoading: boolean;
}

const ActiveCudsSection: React.FC<ActiveCudsSectionProps> = ({
  activeCuds,
  isLoading,
}) => {
  const formatNumber = (value: number | undefined): string => {
    if (value === null || value === undefined || isNaN(value)) return '0';
    return new Intl.NumberFormat('en-US').format(value);
  };

  return (
    <div className="glass-card">
      <div className="section-header">
        <h2 className="section-title">Active Committed Use Discounts</h2>
        <span className="badge badge-success">
          {activeCuds?.length || 0} Active
        </span>
      </div>

      {isLoading && <div className="skeleton skeleton-large"></div>}

      {!isLoading && (!activeCuds || activeCuds.length === 0) && (
        <div className="empty-state">
          <i className="fas fa-info-circle empty-state-icon text-blue-400"></i>
          <p className="empty-state-title">No active Committed Use Discounts found for this account.</p>
          <p className="empty-state-subtitle">Purchase CUDs to save on consistent workloads.</p>
        </div>
      )}

      {!isLoading && activeCuds && activeCuds.length > 0 && (
        <div className="cuds-list">
          {activeCuds.map((cud, index) => (
            <div key={index} className="cud-card">
              <div className="cud-header">
                <div>
                  <h3 className="cud-name">{cud.name || 'Committed Use Discount'}</h3>
                  {cud.description && <p className="cud-description">{cud.description}</p>}
                </div>
                <span className={`badge badge-${cud.status === 'ACTIVE' ? 'success' : 'warning'}`}>
                  {cud.status || 'ACTIVE'}
                </span>
              </div>
              <div className="cud-details-grid">
                <div className="detail-item">
                  <p className="detail-label">Type</p>
                  <p className="detail-value">{cud.commitmentUnit || cud.type || 'N/A'}</p>
                </div>
                <div className="detail-item">
                  <p className="detail-label">Term</p>
                  <p className="detail-value">{cud.term || cud.plan || 'N/A'}</p>
                </div>
                <div className="detail-item">
                  <p className="detail-label">Region</p>
                  <p className="detail-value">{cud.region || 'Global'}</p>
                </div>
                <div className="detail-item">
                  <p className="detail-label">Commitment</p>
                  <p className="detail-value detail-value-highlight">
                    {formatNumber(cud.commitmentAmount)} {cud.commitmentUnit || ''}
                  </p>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default ActiveCudsSection;
