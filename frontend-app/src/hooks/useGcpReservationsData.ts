/**
 * Custom hook for GCP Reservations Data
 *
 * Handles fetching and managing GCP Committed Use Discounts (CUDs) data
 * from the backend API
 */

import { useState, useEffect, useCallback } from 'react';
import apiClient from '../utils/apiClient';
import type { GcpActiveCud, GcpCudRecommendation } from '../types/gcpReservations';

interface UseGcpReservationsDataProps {
  selectedAccountId: string | null;
}

interface UseGcpReservationsDataReturn {
  activeCuds: GcpActiveCud[];
  cudRecommendations: GcpCudRecommendation[];
  isLoadingActive: boolean;
  isLoadingRecommendations: boolean;
  isRefreshing: boolean;
  error: string | null;
  refreshAll: () => Promise<void>;
}

export const useGcpReservationsData = ({
  selectedAccountId,
}: UseGcpReservationsDataProps): UseGcpReservationsDataReturn => {
  const [activeCuds, setActiveCuds] = useState<GcpActiveCud[]>([]);
  const [cudRecommendations, setCudRecommendations] = useState<GcpCudRecommendation[]>([]);
  const [isLoadingActive, setIsLoadingActive] = useState(true);
  const [isLoadingRecommendations, setIsLoadingRecommendations] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * Load active CUDs
   * Note: Backend returns empty array - feature not yet implemented
   */
  const loadActiveCuds = useCallback(async () => {
    if (!selectedAccountId) {
      setIsLoadingActive(false);
      return;
    }

    try {
      console.log(`[GCP Reservations] Loading active CUDs for account: ${selectedAccountId}`);

      // For now, active CUDs feature is not implemented
      // Keep this here for future implementation
      setActiveCuds([]);
      setIsLoadingActive(false);

      console.log('ℹ️ Active CUDs: Feature not yet implemented. Showing recommendations only.');
    } catch (err) {
      console.error('[GCP Reservations] Failed to load active CUDs:', err);
      setError(err instanceof Error ? err.message : 'Failed to load active CUDs');
      setActiveCuds([]);
      setIsLoadingActive(false);
    }
  }, [selectedAccountId]);

  /**
   * Load CUD recommendations from backend
   */
  const loadCudRecommendations = useCallback(async () => {
    if (!selectedAccountId) {
      setIsLoadingRecommendations(false);
      return;
    }

    try {
      console.log(`[GCP Reservations] Loading CUD recommendations for account: ${selectedAccountId}`);
      setIsLoadingRecommendations(true);

      const response = await apiClient.get<GcpCudRecommendation[]>(
        `/api/xamops/gcp/reservations/cuds?accountId=${selectedAccountId}`
      );

      console.log('[GCP Reservations] CUD Recommendations received:', response);
      setCudRecommendations(Array.isArray(response) ? response : []);
      setError(null);
    } catch (err) {
      console.error('[GCP Reservations] Failed to load CUD recommendations:', err);
      setError(err instanceof Error ? err.message : 'Failed to load CUD recommendations');
      setCudRecommendations([]);
    } finally {
      setIsLoadingRecommendations(false);
    }
  }, [selectedAccountId]);

  /**
   * Refresh all data
   */
  const refreshAll = useCallback(async () => {
    setIsRefreshing(true);
    setIsLoadingActive(true);
    setIsLoadingRecommendations(true);

    try {
      await Promise.all([
        loadActiveCuds(),
        loadCudRecommendations()
      ]);
    } finally {
      setIsRefreshing(false);
    }
  }, [loadActiveCuds, loadCudRecommendations]);

  // Initial load when account changes
  useEffect(() => {
    if (selectedAccountId) {
      loadActiveCuds();
      loadCudRecommendations();
    }
  }, [selectedAccountId, loadActiveCuds, loadCudRecommendations]);

  return {
    activeCuds,
    cudRecommendations,
    isLoadingActive,
    isLoadingRecommendations,
    isRefreshing,
    error,
    refreshAll,
  };
};
