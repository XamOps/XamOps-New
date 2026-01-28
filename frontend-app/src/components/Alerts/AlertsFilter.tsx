/**
 * AlertsFilter Component
 *
 * Filter bar for filtering alerts by service
 */

import React from 'react';

interface AlertsFilterProps {
  selectedService: string;
  services: string[];
  filteredCount: number;
  onServiceChange: (service: string) => void;
}

const AlertsFilter: React.FC<AlertsFilterProps> = ({
  selectedService,
  services,
  filteredCount,
  onServiceChange,
}) => {
  return (
    <div className="alerts-filter-bar">
      <div className="alerts-filter-content">
        <div className="alerts-filter-left">
          <i className="fas fa-filter alerts-filter-icon"></i>
          <label htmlFor="service-filter" className="alerts-filter-label">
            Filter by Service:
          </label>
          <select
            id="service-filter"
            value={selectedService}
            onChange={(e) => onServiceChange(e.target.value)}
            className="alerts-filter-select"
          >
            <option value="All">All Services</option>
            {services.map((service) => (
              <option key={service} value={service}>
                {service}
              </option>
            ))}
          </select>
        </div>
        <div className="alerts-filter-count">
          <span>{filteredCount}</span> alert{filteredCount !== 1 ? 's' : ''} shown
        </div>
      </div>
    </div>
  );
};

export default AlertsFilter;
