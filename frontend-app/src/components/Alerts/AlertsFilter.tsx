/**
 * AlertsFilter Component
 *
 * Filter bar for filtering alerts by service
 */

import React from 'react';
import { Icon } from '@iconify/react';

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
    <div className="px-5 py-3 border-b border-[rgba(0,0,0,0.06)]">
      <div className="flex items-center gap-3 flex-wrap">
        <div className="flex items-center gap-2">
          <Icon icon="mdi:filter-outline" width={14} className="text-[rgba(0,0,0,0.4)]" />
          <label htmlFor="service-filter" className="text-[13px] font-medium text-[rgba(0,0,0,0.6)]">
            Filter by Service:
          </label>
          <select
            id="service-filter"
            value={selectedService}
            onChange={(e) => onServiceChange(e.target.value)}
            className="px-3 py-1.5 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[13px] font-medium text-[rgba(0,0,0,0.7)] focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
          >
            <option value="All">All Services</option>
            {services.map((service) => (
              <option key={service} value={service}>
                {service}
              </option>
            ))}
          </select>
        </div>
        <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.4)] bg-[rgba(0,0,0,0.04)] px-1.5 py-0.5 rounded-md">
          {filteredCount} alert{filteredCount !== 1 ? 's' : ''}
        </span>
      </div>
    </div>
  );
};

export default AlertsFilter;
