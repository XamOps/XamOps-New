/**
 * Custom hook for calculator functionality
 * Handles API calls and state management for the calculator
 */

import { useState, useCallback } from 'react';
import type {
  Region,
  Service,
  ServiceConfig,
  PricingResult,
  FormValues,
  DynamicSelectOption,
} from '../types/calculator';

const AWS_API_ROOT = 'http://localhost:8080';
const AWS_API_BASE = `${AWS_API_ROOT}/api/aws/calculator`;

// Generic API GET with timeout
async function apiGet<T>(url: string, params: Record<string, any> = {}, timeoutMs = 10000): Promise<T> {
  const query = new URLSearchParams(
    Object.entries(params).filter(([_, v]) => v != null)
  ).toString();
  const fullUrl = query ? `${url}?${query}` : url;

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(fullUrl, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' },
      signal: controller.signal,
    });

    if (!response.ok) {
      const text = await response.text();
      throw new Error(`API ${response.status}: ${text}`);
    }

    return response.json();
  } finally {
    clearTimeout(timeout);
  }
}

export const useCalculator = () => {
  const [regions, setRegions] = useState<Region[]>([]);
  const [services, setServices] = useState<Service[]>([]);
  const [serviceConfig, setServiceConfig] = useState<ServiceConfig | null>(null);
  const [pricingResult, setPricingResult] = useState<PricingResult | null>(null);
  const [loading, setLoading] = useState({
    regions: false,
    services: false,
    config: false,
    pricing: false,
  });
  const [error, setError] = useState<string | null>(null);

  // Load AWS regions
  const loadRegions = useCallback(async () => {
    setLoading(prev => ({ ...prev, regions: true }));
    setError(null);

    try {
      const data = await apiGet<{ regions: Region[] }>(
        `${AWS_API_BASE}/regions`,
        { accountId: 'default' }
      );

      if (data.regions && data.regions.length > 0) {
        setRegions(data.regions);
      } else {
        // Fallback regions
        setRegions([
          { code: 'us-east-1', name: 'US East (N. Virginia)' },
          { code: 'ap-south-1', name: 'Asia Pacific (Mumbai)' },
          { code: 'eu-west-1', name: 'EU (Ireland)' },
        ]);
      }
    } catch (err) {
      console.error('Error loading regions:', err);
      // Use fallback regions on error
      setRegions([
        { code: 'us-east-1', name: 'US East (N. Virginia)' },
        { code: 'ap-south-1', name: 'Asia Pacific (Mumbai)' },
        { code: 'eu-west-1', name: 'EU (Ireland)' },
      ]);
    } finally {
      setLoading(prev => ({ ...prev, regions: false }));
    }
  }, []);

  // Load AWS services
  const loadServices = useCallback(async () => {
    setLoading(prev => ({ ...prev, services: true }));
    setError(null);

    try {
      const data = await apiGet<{ services: Service[] }>(`${AWS_API_BASE}/services`);
      const validServices = (data.services || []).filter(
        svc => svc.name && svc.name !== 'undefined'
      );
      setServices(validServices);
    } catch (err: any) {
      console.error('Error loading services:', err);
      setError(err.message || 'Failed to load services');
    } finally {
      setLoading(prev => ({ ...prev, services: false }));
    }
  }, []);

  // Load service configuration
  const loadServiceConfig = useCallback(async (serviceCode: string) => {
    setLoading(prev => ({ ...prev, config: true }));
    setError(null);
    setPricingResult(null);

    try {
      const config = await apiGet<ServiceConfig>(
        `${AWS_API_BASE}/service-config/${serviceCode}`
      );
      setServiceConfig(config);
    } catch (err: any) {
      console.error('Error loading service config:', err);
      setError(err.message || 'Failed to load service configuration');
      setServiceConfig(null);
    } finally {
      setLoading(prev => ({ ...prev, config: false }));
    }
  }, []);

  // Load dynamic select options
  const loadDynamicOptions = useCallback(
    async (
      apiEndpoint: string,
      region: string,
      dependencies: Record<string, any> = {}
    ): Promise<DynamicSelectOption[]> => {
      const params = {
        accountId: 'default',
        region,
        ...dependencies,
      };

      try {
        const data = await apiGet<any>(`${AWS_API_ROOT}${apiEndpoint}`, params, 20000);
        return data.instances || data.data || [];
      } catch (err) {
        console.error('Error loading dynamic options:', err);
        return [];
      }
    },
    []
  );

  // Calculate pricing
  const calculatePricing = useCallback(
    async (region: string, formValues: FormValues) => {
      if (!serviceConfig) return;

      setLoading(prev => ({ ...prev, pricing: true }));
      setError(null);

      const params = {
        accountId: 'default',
        region,
        ...formValues,
      };

      try {
        const result = await apiGet<PricingResult>(
          `${AWS_API_ROOT}${serviceConfig.pricingEndpoint}`,
          params,
          30000
        );
        setPricingResult(result);
      } catch (err: any) {
        console.error('Error calculating pricing:', err);
        setError(err.message || 'Failed to calculate pricing');
        setPricingResult(null);
      } finally {
        setLoading(prev => ({ ...prev, pricing: false }));
      }
    },
    [serviceConfig]
  );

  // Reset pricing result
  const resetPricingResult = useCallback(() => {
    setPricingResult(null);
  }, []);

  return {
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
  };
};
