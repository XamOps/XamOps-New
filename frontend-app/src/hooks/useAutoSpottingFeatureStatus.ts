/**
 * Reads which AutoSpotting conversion switches are on (GET /api/autospotting/feature-status).
 *
 * Fails closed: until the response arrives, and on any error, both clouds are treated as
 * paused. The backend refuses paused conversions anyway, so the worst case is a disabled
 * Enable control, never a conversion the UI promised and the server rejected.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import apiClient from '../utils/apiClient';

export interface AutoSpottingFeatureStatus {
  awsConversionsEnabled: boolean;
  gcpConversionsEnabled: boolean;
  loading: boolean;
  refresh: () => Promise<void>;
}

export const useAutoSpottingFeatureStatus = (): AutoSpottingFeatureStatus => {
  const [awsConversionsEnabled, setAws] = useState(false);
  const [gcpConversionsEnabled, setGcp] = useState(false);
  const [loading, setLoading] = useState(true);
  const mounted = useRef(true);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiClient.get('/api/autospotting/feature-status');
      if (!mounted.current) return;
      setAws(data?.aws?.conversionsEnabled === true);
      setGcp(data?.gcp?.conversionsEnabled === true);
    } catch {
      if (!mounted.current) return;
      setAws(false);
      setGcp(false);
    } finally {
      if (mounted.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    refresh();
    return () => { mounted.current = false; };
  }, [refresh]);

  return { awsConversionsEnabled, gcpConversionsEnabled, loading, refresh };
};
