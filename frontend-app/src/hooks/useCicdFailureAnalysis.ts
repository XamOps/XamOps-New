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

/**
 * Both endpoints used to have their body cast straight through with `as FailureAnalysis` /
 * `as DiffSummary`. Every field on those interfaces is required, so the components render
 * `analysis.snippets.map(...)` and `diff.commits.length` with no guard — correctly, given what
 * the types promise. The cast was the only thing standing behind that promise, and it checked
 * nothing: any other JSON shape became a FailureAnalysis as far as the compiler was concerned,
 * and the first property access threw, taking the whole page down via the error boundary.
 *
 * Validated at the boundary instead, so a shape mismatch shows the tab's existing error state.
 * Mirrors the check useCicdMetrics already does on /api/cicd/{provider}/metrics.
 */
const isObject = (d: unknown): d is Record<string, unknown> =>
  !!d && typeof d === 'object' && !Array.isArray(d);

const isFailureAnalysis = (d: unknown): d is FailureAnalysis =>
  isObject(d) && Array.isArray(d.snippets);

const isDiffSummary = (d: unknown): d is DiffSummary =>
  isObject(d) && (Array.isArray(d.commits) || d.noPreviousSuccess === true);

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
      if (!isFailureAnalysis(data)) throw new Error('Unexpected failure-analysis response shape');
      // `snippets` is guaranteed an array by here, but an individual snippet's `lines` is not,
      // and CicdFailureDetail maps over it one level down.
      setFailureAnalysis({
        ...data,
        snippets: data.snippets.map(s => ({ ...s, lines: Array.isArray(s?.lines) ? s.lines : [] })),
      });
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
      if (!isDiffSummary(data)) throw new Error('Unexpected diff-summary response shape');
      // CicdDiffSummary reads .length on both arrays before mapping, so neither may be absent.
      setDiffSummary({
        ...data,
        commits: Array.isArray(data.commits) ? data.commits : [],
        changedFiles: Array.isArray(data.changedFiles) ? data.changedFiles : [],
        filesChanged: typeof data.filesChanged === 'number' ? data.filesChanged : 0,
      });
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
