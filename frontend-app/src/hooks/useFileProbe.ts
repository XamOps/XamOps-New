/**
 * useFileProbe Hook
 *
 * Runs FFprobe on a source file to extract metadata (codec, resolution, bitrate, etc.)
 * Auto-clears results when the source URL changes.
 */

import { useState, useCallback, useRef } from 'react';
import { apiFetchJson } from '../utils/apiClient';

export interface FileProbeResult {
  videoCodec: string | null;
  audioCodec: string | null;
  resolution: string | null;
  videoBitrate: number | null;
  audioBitrate: number | null;
  frameRate: number | null;
  audioSampleRate: number | null;
  audioChannels: number | null;
  durationSeconds: number | null;
  fileSize: number | null;
  format: string | null;
}

interface UseFileProbeReturn {
  probeResult: FileProbeResult | null;
  probing: boolean;
  probeError: string | null;
  probeFile: (sourceUrl: string) => Promise<FileProbeResult | null>;
  clearProbe: () => void;
}

const PROBE_ENDPOINT = '/api/media-ops/probe';

export function useFileProbe(): UseFileProbeReturn {
  const [probeResult, setProbeResult] = useState<FileProbeResult | null>(null);
  const [probing, setProbing] = useState<boolean>(false);
  const [probeError, setProbeError] = useState<string | null>(null);
  const lastSourceRef = useRef<string | null>(null);

  const clearProbe = useCallback(() => {
    setProbeResult(null);
    setProbeError(null);
    setProbing(false);
    lastSourceRef.current = null;
  }, []);

  const probeFile = useCallback(async (sourceUrl: string): Promise<FileProbeResult | null> => {
    if (!sourceUrl || !sourceUrl.trim()) {
      clearProbe();
      return null;
    }

    // Auto-clear when sourceUrl changes
    if (lastSourceRef.current && lastSourceRef.current !== sourceUrl) {
      setProbeResult(null);
      setProbeError(null);
    }
    lastSourceRef.current = sourceUrl;

    setProbing(true);
    setProbeError(null);

    try {
      const result = await apiFetchJson<FileProbeResult>(PROBE_ENDPOINT, {
        method: 'POST',
        body: JSON.stringify({ sourceUrl }),
      });
      setProbeResult(result);
      return result;
    } catch (e: any) {
      const msg = e.message || 'Failed to probe file';
      setProbeError(msg);
      setProbeResult(null);
      return null;
    } finally {
      setProbing(false);
    }
  }, [clearProbe]);

  return {
    probeResult,
    probing,
    probeError,
    probeFile,
    clearProbe,
  };
}

export default useFileProbe;
