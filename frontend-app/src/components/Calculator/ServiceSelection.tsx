/**
 * Service Selection Component
 * Allows users to select cloud provider, region, and service
 */

import React from 'react';
import type { Region, Service } from '../../types/calculator';

interface ServiceSelectionProps {
  regions: Region[];
  services: Service[];
  selectedRegion: string;
  selectedService: string;
  onRegionChange: (region: string) => void;
  onServiceChange: (service: string) => void;
  loading: boolean;
}

export const ServiceSelection: React.FC<ServiceSelectionProps> = ({
  regions,
  services,
  selectedRegion,
  selectedService,
  onRegionChange,
  onServiceChange,
  loading,
}) => {
  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
      <h2 className="text-lg font-semibold text-gray-800 mb-6 flex items-center">
        <span className="w-8 h-8 bg-indigo-50 text-indigo-600 rounded-lg flex items-center justify-center mr-3">
          <i className="fas fa-layer-group text-sm"></i>
        </span>
        Service Selection
      </h2>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div>
          <label className="block text-xs font-bold text-gray-400 uppercase tracking-widest mb-2">
            Cloud Provider
          </label>
          <select
            className="w-full bg-gray-50 border-0 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all"
            value="AWS"
            disabled
          >
            <option value="AWS">Amazon Web Services</option>
            <option value="GCP" disabled>
              Google Cloud (Coming Soon)
            </option>
            <option value="Azure" disabled>
              Microsoft Azure (Coming Soon)
            </option>
          </select>
        </div>
        <div>
          <label className="block text-xs font-bold text-gray-400 uppercase tracking-widest mb-2">
            Target Region
          </label>
          <select
            className="w-full bg-gray-50 border-0 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all"
            value={selectedRegion}
            onChange={(e) => onRegionChange(e.target.value)}
            disabled={loading}
          >
            {regions.map((region) => (
              <option key={region.code} value={region.code}>
                {region.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="block text-xs font-bold text-gray-400 uppercase tracking-widest mb-2">
            Service
          </label>
          <select
            className="w-full bg-gray-50 border-0 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all"
            value={selectedService}
            onChange={(e) => onServiceChange(e.target.value)}
            disabled={loading}
          >
            <option value="">-- Select Service --</option>
            {services.map((service) => (
              <option key={service.code} value={service.code}>
                {service.name} ({service.category})
              </option>
            ))}
          </select>
        </div>
      </div>
    </div>
  );
};
