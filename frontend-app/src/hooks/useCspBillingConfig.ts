import { useState, useEffect } from 'react';
import { apiFetch } from '../utils/apiClient';

export interface CspBillingConfigOption {
  id: number;
  name: string;
  gcpProjectId: string;
  datasetId: string;
  tableId: string;
}

export function useCspBillingConfig() {
  const [configs, setConfigs] = useState<CspBillingConfigOption[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setIsLoading(true);
    apiFetch('/api/xamops/gcp/csp-billing/configs')
      .then(res => {
        if (!res.ok) throw new Error('Failed to load CSP configs');
        return res.json();
      })
      .then((data: CspBillingConfigOption[]) => setConfigs(data))
      .catch(err => setError(err.message))
      .finally(() => setIsLoading(false));
  }, []);

  return { configs, isLoading, error };
}
