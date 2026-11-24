import { useState, useCallback } from 'react';
import apiClient from '../utils/apiClient';
import { getCommitmentCoverage, legacyRispCoveragePath, type RispCoverageBody } from '../api/autospottingApi';

export interface RiCoverage {
  instanceType: string;
  region: string;
  availabilityZone: string;
  coveragePercent: number;
}

export interface SpCoverage {
  planType: string;
  coveragePercent: number;
}

export interface RispCoverageData {
  collectedAt: string | null;
  totalRiUtilizationPercent: number;
  totalSpUtilizationPercent: number;
  riCoverages: RiCoverage[];
  spCoverages: SpCoverage[];
}

/**
 * `accountId` is the AWS account id the legacy page uses. With `v2AccountId` (the XamOps account id) the
 * shell's Commitments view reads the v2 endpoint instead; the legacy page keeps its old path until Phase 36.
 */
export const useRispCoverage = (accountId: string | null, v2AccountId?: number | null) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [data, setData] = useState<RispCoverageData>({
    collectedAt: null,
    totalRiUtilizationPercent: 0,
    totalSpUtilizationPercent: 0,
    riCoverages: [],
    spCoverages: []
  });

  const fetchRispCoverage = useCallback(async () => {
    if (!accountId && v2AccountId == null) {
      setError('No account ID provided');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const response: RispCoverageBody & Record<string, any> =
        v2AccountId != null
          ? await getCommitmentCoverage(v2AccountId)
          : await apiClient.get(legacyRispCoveragePath(accountId as string));

      // Transform backend snake_case to camelCase
      const transformedData: RispCoverageData = {
        collectedAt: response.collected_at || null,
        totalRiUtilizationPercent: response.total_ri_utilization_percent || 0,
        totalSpUtilizationPercent: response.total_sp_utilization_percent || 0,
        riCoverages: (response.ri_coverages || []).map((item: any) => ({
          instanceType: item.instance_type || '',
          region: item.region || '',
          availabilityZone: item.availability_zone || '',
          coveragePercent: item.coverage_percent || 0
        })).filter((item: RiCoverage) => item.coveragePercent > 0), // Only show items with coverage
        spCoverages: (response.sp_coverages || []).map((item: any) => ({
          planType: item.plan_type || '',
          coveragePercent: item.coverage_percent || 0
        })).filter((item: SpCoverage) => item.coveragePercent > 0) // Only show items with coverage
      };

      setData(transformedData);
    } catch (err: any) {
      console.error('Error loading RI/SP coverage data:', err);
      setError(err.message || 'Failed to load RI/SP coverage data');
    } finally {
      setLoading(false);
    }
  }, [accountId, v2AccountId]);

  return {
    loading,
    error,
    data,
    fetchRispCoverage
  };
};
