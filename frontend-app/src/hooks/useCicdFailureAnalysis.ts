import { useState, useCallback } from 'react';
import apiClient from '../utils/apiClient';
import { FailureAnalysis, DiffSummary } from '../types/cicdPipelines';

interface UseCicdFailureAnalysisReturn {
  failureAnalysis: FailureAnalysis | null;
  diffSummary: DiffSummary | null;
  isLoadingAnalysis: boolean;
  isLoadingDiff: boolean;
  analysisError: string | null;
  diffError: string | null;
  fetchFailureAnalysis: (owner: string, repo: string, jobId: number | string) => Promise<void>;
  fetchDiffSummary: (owner: string, repo: string, runId: number | string) => Promise<void>;
  reset: () => void;
}

export const useCicdFailureAnalysis = (): UseCicdFailureAnalysisReturn => {
  const [failureAnalysis, setFailureAnalysis] = useState<FailureAnalysis | null>(null);
  const [diffSummary, setDiffSummary] = useState<DiffSummary | null>(null);
  const [isLoadingAnalysis, setIsLoadingAnalysis] = useState(false);
  const [isLoadingDiff, setIsLoadingDiff] = useState(false);
  const [analysisError, setAnalysisError] = useState<string | null>(null);
  const [diffError, setDiffError] = useState<string | null>(null);

  const fetchFailureAnalysis = useCallback(async (owner: string, repo: string, jobId: number | string) => {
    setIsLoadingAnalysis(true);
    setAnalysisError(null);
    try {
      const data = await apiClient.get(`/api/cicd/github/runs/${owner}/${repo}/${jobId}/failure-analysis`);
      setFailureAnalysis(data as FailureAnalysis);
    } catch {
      setAnalysisError('Could not load failure analysis');
    } finally {
      setIsLoadingAnalysis(false);
    }
  }, []);

  const fetchDiffSummary = useCallback(async (owner: string, repo: string, runId: number | string) => {
    setIsLoadingDiff(true);
    setDiffError(null);
    try {
      const data = await apiClient.get(`/api/cicd/github/runs/${owner}/${repo}/${runId}/diff-summary`);
      setDiffSummary(data as DiffSummary);
    } catch {
      setDiffError('Could not load diff summary');
    } finally {
      setIsLoadingDiff(false);
    }
  }, []);

  const reset = useCallback(() => {
    setFailureAnalysis(null);
    setDiffSummary(null);
    setAnalysisError(null);
    setDiffError(null);
  }, []);

  return {
    failureAnalysis, diffSummary,
    isLoadingAnalysis, isLoadingDiff,
    analysisError, diffError,
    fetchFailureAnalysis, fetchDiffSummary, reset,
  };
};
