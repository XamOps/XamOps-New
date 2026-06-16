/**
 * useSizeEstimate Hook
 *
 * Provides real-time output file size estimation based on video/audio bitrate and duration.
 * Uses client-side calculation for instant feedback as the user adjusts settings.
 *
 * Formula: estimated_bytes = (videoBitrate + audioBitrate) * durationSeconds / 8
 * Returns lower (80%), estimated (100%), and upper (120%) bounds.
 */

import { useMemo } from 'react';

export interface SizeEstimation {
  /** Lower bound in bytes (80% of estimated) */
  lowerBound: number;
  /** Best estimate in bytes */
  estimated: number;
  /** Upper bound in bytes (120% of estimated) */
  upperBound: number;
}

interface UseSizeEstimateParams {
  /** Video bitrate in bits per second */
  videoBitrate: number | null;
  /** Audio bitrate in bits per second */
  audioBitrate: number | null;
  /** Duration of the content in seconds */
  durationSeconds: number | null;
}

interface UseSizeEstimateReturn {
  estimation: SizeEstimation | null;
}

/**
 * Calculates the output size estimation client-side for real-time feedback.
 */
export function useSizeEstimate({
  videoBitrate,
  audioBitrate,
  durationSeconds,
}: UseSizeEstimateParams): UseSizeEstimateReturn {
  const estimation = useMemo<SizeEstimation | null>(() => {
    const vBitrate = videoBitrate ?? 0;
    const aBitrate = audioBitrate ?? 0;
    const duration = durationSeconds ?? 0;

    // Need at least some bitrate and duration to estimate
    if ((vBitrate + aBitrate) <= 0 || duration <= 0) {
      return null;
    }

    // Formula: estimated_bytes = (videoBitrate + audioBitrate) * durationSeconds / 8
    const estimatedBytes = (vBitrate + aBitrate) * duration / 8;

    return {
      lowerBound: Math.round(estimatedBytes * 0.8),
      estimated: Math.round(estimatedBytes),
      upperBound: Math.round(estimatedBytes * 1.2),
    };
  }, [videoBitrate, audioBitrate, durationSeconds]);

  return { estimation };
}

export default useSizeEstimate;
