/**
 * GCP Reservations Header Component
 *
 * Header with title, description, and refresh button
 */

import React from 'react';

interface GcpReservationsHeaderProps {
  isRefreshing: boolean;
  onRefresh: () => void;
}

const GcpReservationsHeader: React.FC<GcpReservationsHeaderProps> = ({
  isRefreshing,
  onRefresh,
}) => {
  return (
    <header className="gcp-reservations-header">
      <div className="header-content">
        <div className="header-icon-wrapper">
          <i className="fas fa-hand-holding-usd"></i>
        </div>
        <div className="header-text">
          <h1 className="header-title">GCP Committed Use Discounts</h1>
          <p className="header-subtitle">Analyze your GCP commitments & recommendations</p>
        </div>
      </div>
      <button
        onClick={onRefresh}
        className="refresh-button"
        disabled={isRefreshing}
      >
        <i className={`fas fa-sync-alt ${isRefreshing ? 'fa-spin' : ''}`}></i>
        <span>{isRefreshing ? 'Refreshing...' : 'Refresh Data'}</span>
      </button>
    </header>
  );
};

export default GcpReservationsHeader;
