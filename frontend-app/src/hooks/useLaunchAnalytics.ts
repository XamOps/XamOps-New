import { useState, useCallback } from 'react';
import apiClient from '../utils/apiClient';

export interface InstanceTypeStats {
  instanceType: string;
  attempts: number;
  successes: number;
  failures: number;
  successRate: number;
}

export interface RegionStats {
  region: string;
  attempts: number;
  successes: number;
  failures: number;
  successRate: number;
}

export interface FailureReason {
  reason: string;
  count: number;
  percentage: number;
}

export interface RecommendedType {
  instanceType: string;
  region: string;
  successRate: number;
  avgLaunchTime: string;
  attempts: string;
}

export interface LaunchAnalyticsData {
  totalAttempts: number;
  successRate: number;
  successful: number;
  failed: number;
  byInstanceType: InstanceTypeStats[];
  byRegion: RegionStats[];
  failureReasons: FailureReason[];
  recommendations: RecommendedType[];
}

export const useLaunchAnalytics = (accountId: string) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [data, setData] = useState<LaunchAnalyticsData>({
    totalAttempts: 0,
    successRate: 0,
    successful: 0,
    failed: 0,
    byInstanceType: [],
    byRegion: [],
    failureReasons: [],
    recommendations: []
  });

  const fetchAnalytics = useCallback(async (startDate: string, endDate: string) => {
    if (!accountId) {
      setError('No account ID provided');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const params = new URLSearchParams({
        start: startDate,
        end: endDate
      });

      const response = await apiClient.get(
        `/autospotting/analytics/launches/${accountId}?${params}`
      );

      // Transform backend snake_case to camelCase
      const transformedData: LaunchAnalyticsData = {
        totalAttempts: response.total_attempts || 0,
        successRate: response.success_rate || 0,
        successful: response.total_successes || 0,
        failed: response.total_failures || 0,
        byInstanceType: Object.entries(response.by_instance_type || {}).map(([type, stats]: [string, any]) => ({
          instanceType: type,
          attempts: stats.attempts || 0,
          successes: stats.successes || 0,
          failures: stats.failures || 0,
          successRate: stats.success_rate || 0
        })).sort((a, b) => b.successRate - a.successRate), // Sort by success rate
        byRegion: Object.entries(response.by_region || {}).map(([region, stats]: [string, any]) => ({
          region: region,
          attempts: stats.attempts || 0,
          successes: stats.successes || 0,
          failures: stats.failures || 0,
          successRate: stats.success_rate || 0
        })).sort((a, b) => b.successRate - a.successRate), // Sort by success rate
        failureReasons: (response.top_failure_reasons || []).map((reason: any) => ({
          reason: reason.reason || 'Unknown',
          count: reason.count || 0,
          percentage: reason.percentage || 0
        })),
        recommendations: (response.recommended_types || []).map((rec: any) => ({
          instanceType: rec.instance_type || '',
          region: rec.region || 'Global',
          successRate: rec.success_rate || 0,
          avgLaunchTime: rec.avg_launch_time || '< 2 min',
          attempts: `${rec.total_launches || 0} attempts`
        }))
      };

      setData(transformedData);
    } catch (err: any) {
      console.error('Error fetching launch analytics:', err);
      setError(err.message || 'Failed to load analytics data');
    } finally {
      setLoading(false);
    }
  }, [accountId]);

  return {
    loading,
    error,
    data,
    fetchAnalytics
  };
};
