/**
 * Security Empty State Component
 *
 * Shows when there are no security findings
 */

import React from 'react';

interface SecurityEmptyStateProps {
  onRefresh: () => void;
}

const SecurityEmptyState: React.FC<SecurityEmptyStateProps> = ({ onRefresh }) => {
  return (
    <div className="security-empty-state">
      <div className="security-empty-icon">
        <i className="fas fa-shield-alt"></i>
      </div>
      <h3 className="security-empty-title">All Clear!</h3>
      <p className="security-empty-message">
        No security findings detected. Your environment appears to be secure.
      </p>
      <button onClick={onRefresh} className="security-btn security-btn-primary">
        <i className="fas fa-sync-alt"></i>
        <span>Run Security Scan</span>
      </button>
    </div>
  );
};

export default SecurityEmptyState;
