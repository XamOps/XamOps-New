/**
 * usePresets Hook
 *
 * Manages CRUD operations for transcode presets via the MediaOps API.
 * Provides state management for presets list, loading, and error states.
 */

import { useState, useCallback, useEffect } from 'react';
import { apiFetch, apiFetchJson } from '../utils/apiClient';

export interface TranscodePreset {
  id: number;
  clientId: number;
  name: string;
  outputFormat: string | null;
  resolution: string | null;
  bitrate: string | null;
  fps: number | null;
  codec: string | null;
  audioBitrate: string | null;
  customFfmpegParams: string | null;
  createdAt: string;
  updatedAt: string | null;
}

export interface PresetRequest {
  name: string;
  outputFormat?: string;
  resolution?: string;
  bitrate?: string;
  fps?: number;
  codec?: string;
  audioBitrate?: string;
  customFfmpegParams?: string;
}

interface UsePresetsReturn {
  presets: TranscodePreset[];
  loading: boolean;
  error: string | null;
  fetchPresets: () => Promise<void>;
  createPreset: (req: PresetRequest) => Promise<TranscodePreset>;
  updatePreset: (id: number, req: PresetRequest) => Promise<TranscodePreset>;
  deletePreset: (id: number) => Promise<void>;
}

const API_BASE = '/api/media-ops/presets';

export function usePresets(): UsePresetsReturn {
  const [presets, setPresets] = useState<TranscodePreset[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  const fetchPresets = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiFetchJson<TranscodePreset[]>(API_BASE);
      setPresets(data);
    } catch (e: any) {
      setError(e.message || 'Failed to fetch presets');
    } finally {
      setLoading(false);
    }
  }, []);

  const createPreset = useCallback(async (req: PresetRequest): Promise<TranscodePreset> => {
    setError(null);
    try {
      const created = await apiFetchJson<TranscodePreset>(API_BASE, {
        method: 'POST',
        body: JSON.stringify(req),
      });
      setPresets(prev => [created, ...prev]);
      return created;
    } catch (e: any) {
      const msg = e.message || 'Failed to create preset';
      setError(msg);
      throw new Error(msg);
    }
  }, []);

  const updatePreset = useCallback(async (id: number, req: PresetRequest): Promise<TranscodePreset> => {
    setError(null);
    try {
      const updated = await apiFetchJson<TranscodePreset>(`${API_BASE}/${id}`, {
        method: 'PUT',
        body: JSON.stringify(req),
      });
      setPresets(prev => prev.map(p => p.id === id ? updated : p));
      return updated;
    } catch (e: any) {
      const msg = e.message || 'Failed to update preset';
      setError(msg);
      throw new Error(msg);
    }
  }, []);

  const deletePreset = useCallback(async (id: number): Promise<void> => {
    setError(null);
    try {
      await apiFetch(`${API_BASE}/${id}`, { method: 'DELETE' });
      setPresets(prev => prev.filter(p => p.id !== id));
    } catch (e: any) {
      const msg = e.message || 'Failed to delete preset';
      setError(msg);
      throw new Error(msg);
    }
  }, []);

  // Fetch presets on mount
  useEffect(() => {
    fetchPresets();
  }, [fetchPresets]);

  return {
    presets,
    loading,
    error,
    fetchPresets,
    createPreset,
    updatePreset,
    deletePreset,
  };
}

export default usePresets;
