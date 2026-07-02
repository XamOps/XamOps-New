/**
 * Canned (saved) reply templates — shared admin library.
 * CRUD against /api/billops/canned-responses.
 */
import { useState, useEffect, useCallback } from 'react';

export interface CannedResponse {
  id: number;
  title: string;
  body: string;
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
}

interface UseCannedResponsesReturn {
  responses: CannedResponse[];
  loading: boolean;
  error: string | null;
  refetch: () => Promise<void>;
  create: (title: string, body: string) => Promise<boolean>;
  update: (id: number, title: string, body: string) => Promise<boolean>;
  remove: (id: number) => Promise<boolean>;
}

const BASE = '/api/billops/canned-responses';

export const useCannedResponses = (enabled: boolean): UseCannedResponsesReturn => {
  const [responses, setResponses] = useState<CannedResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refetch = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(BASE, { credentials: 'include' });
      if (!res.ok) throw new Error(`status ${res.status}`);
      setResponses(await res.json());
    } catch (e) {
      setError('Could not load templates.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (enabled) refetch();
  }, [enabled, refetch]);

  const create = useCallback(async (title: string, body: string) => {
    try {
      const res = await fetch(BASE, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title, body }),
      });
      if (!res.ok) return false;
      const created: CannedResponse = await res.json();
      setResponses((prev) => [...prev, created].sort((a, b) => a.title.localeCompare(b.title)));
      return true;
    } catch {
      return false;
    }
  }, []);

  const update = useCallback(async (id: number, title: string, body: string) => {
    try {
      const res = await fetch(`${BASE}/${id}`, {
        method: 'PUT',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title, body }),
      });
      if (!res.ok) return false;
      const updated: CannedResponse = await res.json();
      setResponses((prev) =>
        prev.map((r) => (r.id === id ? updated : r)).sort((a, b) => a.title.localeCompare(b.title))
      );
      return true;
    } catch {
      return false;
    }
  }, []);

  const remove = useCallback(async (id: number) => {
    try {
      const res = await fetch(`${BASE}/${id}`, { method: 'DELETE', credentials: 'include' });
      if (!res.ok && res.status !== 204) return false;
      setResponses((prev) => prev.filter((r) => r.id !== id));
      return true;
    } catch {
      return false;
    }
  }, []);

  return { responses, loading, error, refetch, create, update, remove };
};
