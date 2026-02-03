/**
 * Cloud Pricing Calculator Page
 *
 * Converted from: calculator.html
 *
 * This page provides a comprehensive pricing calculator for cloud services.
 * Features:
 * - Multi-cloud provider support (AWS, GCP, Azure - AWS available now)
 * - Dynamic service selection and configuration
 * - Real-time pricing calculation from backend APIs
 * - Estimate cart for comparing multiple services
 * - Support for multiple pricing models
 *
 * Changes from original:
 * - Converted from vanilla JS to React with TypeScript
 * - Modern React patterns (hooks, composition)
 * - Improved UI with better visual hierarchy
 * - All backend integration preserved (same endpoints, same logic)
 * - Enhanced UX with loading states and error handling
 */

import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useCalculator } from '../hooks/useCalculator';
import { useEstimateCart } from '../hooks/useEstimateCart';
import { ServiceSelection } from '../components/Calculator/ServiceSelection';
import { ConfigurationForm } from '../components/Calculator/ConfigurationForm';
import { PricingResult } from '../components/Calculator/PricingResult';
import { EstimateCart } from '../components/Calculator/EstimateCart';
import type { FormValues } from '../types/calculator';

const CalculatorPage: React.FC = () => {
  const navigate = useNavigate();
  const {
    regions,
    services,
    serviceConfig,
    pricingResult,
    loading,
    error,
    loadRegions,
    loadServices,
    loadServiceConfig,
    loadDynamicOptions,
    calculatePricing,
    resetPricingResult,
  } = useCalculator();

  const {
    cart,
    totalCost,
    addToCart,
    removeFromCart,
  } = useEstimateCart();

  const [selectedRegion, setSelectedRegion] = useState('');
  const [selectedService, setSelectedService] = useState('');

  // Load initial data on mount
  useEffect(() => {
    loadRegions();
    loadServices();
  }, [loadRegions, loadServices]);

  // Set default region when regions are loaded
  useEffect(() => {
    if (regions.length > 0 && !selectedRegion) {
      // Default to ap-south-1 (Mumbai) if available, otherwise first region
      const defaultRegion = regions.find(r => r.code === 'ap-south-1') || regions[0];
      setSelectedRegion(defaultRegion.code);
    }
  }, [regions, selectedRegion]);

  // Handle region change
  const handleRegionChange = (region: string) => {
    setSelectedRegion(region);
    resetPricingResult();
  };

  // Handle service change
  const handleServiceChange = (serviceCode: string) => {
    setSelectedService(serviceCode);
    resetPricingResult();
    if (serviceCode && selectedRegion) {
      loadServiceConfig(serviceCode);
    }
  };

  // Handle form submission
  const handleFormSubmit = async (formValues: FormValues) => {
    if (!selectedRegion) return;
    await calculatePricing(selectedRegion, formValues);
  };

  // Handle add to cart
  const handleAddToCart = () => {
    if (!pricingResult || !serviceConfig) return;

    // Build config summary from form values (we need to get this from the form)
    const configSummary = `Service configured`;

    addToCart({
      serviceName: serviceConfig.serviceName,
      region: selectedRegion,
      monthlyCost: pricingResult.totalMonthly,
      configSummary,
    });

    // Show success notification (optional)
    console.log('Added to cart:', serviceConfig.serviceName);
  };

  return (
    <main className="flex-1 bg-gray-50">
      <div className="p-8">
        {/* Error banner */}
        {error && (
          <div className="mb-6 bg-red-50 border border-red-100 rounded-xl p-4 flex items-center gap-3">
            <div className="w-10 h-10 bg-red-100 rounded-lg flex items-center justify-center flex-shrink-0">
              <i className="fas fa-exclamation-triangle text-red-600"></i>
            </div>
            <div className="flex-1">
              <p className="text-sm font-semibold text-red-800">Error</p>
              <p className="text-xs text-red-600 mt-0.5">{error}</p>
            </div>
            <button
              onClick={() => window.location.reload()}
              className="text-xs font-semibold text-red-600 hover:text-red-700 underline"
            >
              Retry
            </button>
          </div>
        )}

        <div className="grid grid-cols-1 xl:grid-cols-3 gap-8">
          {/* Left column - Service selection and configuration */}
          <div className="xl:col-span-2 space-y-6">
            {/* Service Selection */}
            <ServiceSelection
              regions={regions}
              services={services}
              selectedRegion={selectedRegion}
              selectedService={selectedService}
              onRegionChange={handleRegionChange}
              onServiceChange={handleServiceChange}
              loading={loading.regions || loading.services}
            />

            {/* Configuration Form */}
            <ConfigurationForm
              serviceConfig={serviceConfig}
              region={selectedRegion}
              loading={loading.config}
              onSubmit={handleFormSubmit}
              onLoadDynamicOptions={loadDynamicOptions}
            />

            {/* Pricing Result */}
            {pricingResult && serviceConfig && (
              <PricingResult
                result={pricingResult}
                serviceConfig={serviceConfig}
                region={selectedRegion}
                onAddToCart={handleAddToCart}
              />
            )}
          </div>

          {/* Right column - Estimate Cart */}
          <div className="xl:col-span-1">
            <EstimateCart
              cart={cart}
              totalCost={totalCost}
              onRemoveItem={removeFromCart}
            />
          </div>
        </div>
      </div>
    </main>
  );
};

export default CalculatorPage;
