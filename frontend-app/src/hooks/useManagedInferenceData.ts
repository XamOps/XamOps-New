import { useState, useCallback } from 'react';
import { apiFetchJson } from '../utils/apiClient';
import type { InferenceOverview } from '../types/aiops';

export function useManagedInferenceData(period: string) {
  const [data, setData] = useState<InferenceOverview | null>(null);
  const [loading, setLoading] = useState(false);

  const loadInference = useCallback(async () => {
    setLoading(true);
    try {
      const result = await apiFetchJson<InferenceOverview>(
        `/api/xamops/aiops/managed-inference?period=${period}`
      );
      setData(result);
    } catch (err) {
      console.error('Failed to load managed inference', err);
    } finally {
      setLoading(false);
    }
  }, [period]);

  return { data, loading, loadInference };
}
