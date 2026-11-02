import { useState, useEffect } from 'react';
import apiClient from '../utils/apiClient';

export interface RedisInstanceMetrics {
  dsnAlias: string;
  dbHost: string;
  usedMemoryBytes: number;
  maxMemoryBytes: number;
  memUsagePct: number;
  keyspaceHits: number;
  keyspaceMisses: number;
  hitRatioPct: number;
  evictedKeys: number;
  expiredKeys: number;
  totalCommandsProcessed: number;
  connectedClients: number;
  role: string;
  replLagSeconds: number;
  uptimeSeconds: number;
  version: string;
  collectedAt: string;
}

export interface RedisSlowLog {
  dsnAlias: string;
  dbHost: string;
  id: number;
  timestamp: string;
  durationUs: number;
  command: string;
  collectedAt: string;
}

export interface RedisKeyspace {
  dsnAlias: string;
  dbHost: string;
  database: string;
  keys: number;
  expires: number;
  avgTtlMs: number;
  collectedAt: string;
}

export function useRedisData(accountId: string, dbHost?: string) {
  const [metrics, setMetrics] = useState<RedisInstanceMetrics[]>([]);
  const [slowLog, setSlowLog] = useState<RedisSlowLog[]>([]);
  const [keyspace, setKeyspace] = useState<RedisKeyspace[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    // PERF-19: only fetch when a Redis host is supplied. The drawer passes a host only for a Redis
    // instance whose mirror flag is on, so a Postgres/MySQL drawer no longer fires three unused
    // Redis requests on every open.
    if (!accountId || !dbHost) {
      setMetrics([]);
      setSlowLog([]);
      setKeyspace([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    const base = `/api/dbops/aws/${accountId}`;
    Promise.all([
      apiClient.get<RedisInstanceMetrics[]>(`${base}/agent-redis-metrics`),
      apiClient.get<RedisSlowLog[]>(`${base}/agent-redis-slow-log`),
      apiClient.get<RedisKeyspace[]>(`${base}/agent-redis-keyspace`),
    ])
      .then(([m, s, k]) => {
        const filter = <T extends { dbHost?: string }>(arr: T[]) =>
          dbHost
            ? arr.filter((x) => x.dbHost === dbHost || x.dbHost?.includes(dbHost))
            : arr;
        setMetrics(filter(m ?? []));
        setSlowLog(filter(s ?? []));
        setKeyspace(filter(k ?? []));
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [accountId, dbHost]);

  return { metrics, slowLog, keyspace, loading };
}
