import { useState, useCallback } from 'react';
import apiClient from '../utils/apiClient';

export interface ExplainResult {
  requestId: string;
  status: 'PENDING' | 'IN_FLIGHT' | 'COMPLETED' | 'FAILED' | 'EXPIRED';
  planJson: string | null;
  error: string | null;
}

export function useExplainQuery(accountId: string) {
  const [isLoading, setIsLoading] = useState(false);
  const [result, setResult]       = useState<ExplainResult | null>(null);
  const [error, setError]         = useState<string | null>(null);

  const explain = useCallback(async (params: {
    agentName: string;
    dsnAlias: string;
    queryText: string;
    dbEngine: string;
  }) => {
    setIsLoading(true);
    setResult(null);
    setError(null);

    try {
      const { requestId } = await apiClient.post<{ requestId: string }>(
        `/api/dbops/aws/${accountId}/explain-query`,
        params
      );

      const MAX_ATTEMPTS = 20;
      for (let i = 0; i < MAX_ATTEMPTS; i++) {
        await new Promise(r => setTimeout(r, 3000));
        const res = await apiClient.get<ExplainResult>(
          `/api/dbops/aws/${accountId}/explain-result/${requestId}`
        );
        if (res.status === 'COMPLETED' || res.status === 'FAILED' || res.status === 'EXPIRED') {
          setResult(res);
          if (res.status !== 'COMPLETED') {
            setError(res.error ?? `Explain ${res.status.toLowerCase()}`);
          }
          return;
        }
      }
      setError('Explain timed out — agent did not respond within 60 s');
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Unknown error';
      // SEC-2/3: Explain is disabled server-side — enqueue and result polling both answer 409
      // explain_disabled. Surface a calm, non-crashing state instead of a raw error banner.
      if (msg.includes('explain_disabled') || msg.includes('HTTP 409')) {
        setError('Explain is temporarily unavailable.');
      } else {
        setError(msg);
      }
    } finally {
      setIsLoading(false);
    }
  }, [accountId]);

  const reset = useCallback(() => {
    setResult(null);
    setError(null);
    setIsLoading(false);
  }, []);

  return { explain, isLoading, result, error, reset };
}
