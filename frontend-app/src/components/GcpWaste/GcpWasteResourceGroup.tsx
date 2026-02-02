/**
 * GcpWasteResourceGroup Component
 * Collapsible group of wasted resources by type
 */

import React from 'react';
import type { GcpWasteResource } from '../../types/gcpWaste';

interface GcpWasteResourceGroupProps {
  type: string;
  resources: GcpWasteResource[];
  isOpen: boolean;
  onToggle: () => void;
}

const GcpWasteResourceGroup: React.FC<GcpWasteResourceGroupProps> = ({
  type,
  resources,
  isOpen,
  onToggle,
}) => {
  // Calculate total savings for this group
  const totalSavings = resources.reduce((sum, resource) => sum + (resource.monthlySavings || 0), 0);

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

  return (
    <div className="gcp-waste-service-group">
      {/* Group Header */}
      <div className="gcp-waste-group-header" onClick={onToggle}>
        <div className="gcp-waste-group-header-left">
          <i
            className={`fas fa-chevron-right gcp-waste-chevron ${isOpen ? 'gcp-waste-chevron-open' : ''}`}
          ></i>
          <div className="gcp-waste-group-icon">
            <i className="fas fa-cloud"></i>
          </div>
          <h3>{type}</h3>
          <div className="gcp-waste-group-badge">{resources.length} items</div>
        </div>
        <div className="gcp-waste-group-savings">{formatCurrency(totalSavings)}</div>
      </div>

      {/* Group Resources */}
      {isOpen && (
        <div className="gcp-waste-group-resources">
          {resources.map((resource, index) => (
            <div key={index} className="gcp-waste-resource-row">
              <div className="gcp-waste-resource-name">{resource.resourceName}</div>
              <div className="gcp-waste-resource-location">{resource.location || 'N/A'}</div>
              <div className="gcp-waste-resource-savings">
                {formatCurrency(resource.monthlySavings)}
              </div>
              <div className="gcp-waste-resource-actions">
                <button
                  className="gcp-waste-delete-btn"
                  title="Delete"
                  onClick={() => console.log('Delete:', resource.resourceName)}
                >
                  <i className="fas fa-trash-alt"></i>
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default GcpWasteResourceGroup;
