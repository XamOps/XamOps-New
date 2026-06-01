package collector

import (
	"bufio"
	"context"
	"fmt"
	"log"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

func collectRedisAll(ctx context.Context, dsns []string) []DataBatch {
	var metrics   []RedisInstanceMetrics
	var slowLogs  []RedisSlowLog
	var keyspaces []RedisKeyspace

	for i, dsn := range dsns {
		_, host, err := parseDSN(dsn)
		if err != nil {
			log.Printf("WARN: redis: DSN %d parse error: %v", i, err)
			continue
		}
		alias := fmt.Sprintf("db-%d", i)
		client, err := connectRedis(ctx, dsn)
		if err != nil {
			log.Printf("WARN: redis: %s connect: %v", host, err)
			continue
		}
		qCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
		if m, sl, ks, err := collectRedisInstance(qCtx, client, alias, host); err == nil {
			metrics   = append(metrics, m)
			slowLogs  = append(slowLogs, sl...)
			keyspaces = append(keyspaces, ks...)
		} else {
			log.Printf("WARN: redis: %s: %v", host, err)
		}
		cancel()
		client.Close()
	}

	now := time.Now().UTC()
	var batches []DataBatch
	if len(metrics) > 0 {
		batches = append(batches, DataBatch{Collector: "redis-instance-metrics", Items: metrics, Count: len(metrics), CollectedAt: now})
	}
	if len(slowLogs) > 0 {
		batches = append(batches, DataBatch{Collector: "redis-slow-log", Items: slowLogs, Count: len(slowLogs), CollectedAt: now})
	}
	if len(keyspaces) > 0 {
		batches = append(batches, DataBatch{Collector: "redis-keyspace", Items: keyspaces, Count: len(keyspaces), CollectedAt: now})
	}
	return batches
}

func connectRedis(ctx context.Context, dsn string) (*redis.Client, error) {
	opt, err := redis.ParseURL(dsn)
	if err != nil {
		return nil, fmt.Errorf("parse url: %w", err)
	}
	opt.DialTimeout  = 5 * time.Second
	opt.ReadTimeout  = 5 * time.Second
	opt.WriteTimeout = 5 * time.Second
	client := redis.NewClient(opt)
	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := client.Ping(pingCtx).Err(); err != nil {
		client.Close()
		return nil, fmt.Errorf("ping: %w", err)
	}
	return client, nil
}

func collectRedisInstance(ctx context.Context, client *redis.Client, alias, host string) (
		RedisInstanceMetrics, []RedisSlowLog, []RedisKeyspace, error) {

	infoStr, err := client.Info(ctx, "all").Result()
	if err != nil {
		return RedisInstanceMetrics{}, nil, nil, fmt.Errorf("INFO all: %w", err)
	}
	now := time.Now().UTC().Format(time.RFC3339)
	info := parseRedisInfo(infoStr)

	m := RedisInstanceMetrics{DSNAlias: alias, DBHost: host, CollectedAt: now}
	m.UsedMemoryBytes        = infoInt64(info, "used_memory")
	m.MaxMemoryBytes         = infoInt64(info, "maxmemory")
	m.KeyspaceHits           = infoInt64(info, "keyspace_hits")
	m.KeyspaceMisses         = infoInt64(info, "keyspace_misses")
	m.EvictedKeys            = infoInt64(info, "evicted_keys")
	m.ExpiredKeys            = infoInt64(info, "expired_keys")
	m.TotalCommandsProcessed = infoInt64(info, "total_commands_processed")
	m.ConnectedClients       = infoInt64(info, "connected_clients")
	m.UptimeSeconds          = infoInt64(info, "uptime_in_seconds")
	m.Role                   = info["role"]
	m.Version                = info["redis_version"]
	if m.MaxMemoryBytes > 0 {
		m.MemUsagePct = float64(m.UsedMemoryBytes) / float64(m.MaxMemoryBytes) * 100
	}
	total := m.KeyspaceHits + m.KeyspaceMisses
	if total > 0 {
		m.HitRatioPct = float64(m.KeyspaceHits) / float64(total) * 100
	}
	if m.Role == "slave" {
		masterOffset := infoInt64(info, "master_repl_offset")
		slaveOffset  := infoInt64(info, "slave_repl_offset")
		if masterOffset > 0 {
			lag := float64(masterOffset-slaveOffset) / 1000.0
			if lag < 0 { lag = 0 }
			m.ReplLagSeconds = lag
		}
	}

	rawSlow, _ := client.SlowLogGet(ctx, 10).Result()
	var slowLogs []RedisSlowLog
	for _, entry := range rawSlow {
		cmd := ""
		if len(entry.Args) > 0 {
			parts := entry.Args
			if len(parts) > 3 { parts = parts[:3] }
			cmd = strings.Join(parts, " ")
		}
		slowLogs = append(slowLogs, RedisSlowLog{
			DSNAlias:    alias,
			DBHost:      host,
			ID:          entry.ID,
			Timestamp:   time.Unix(entry.Time.Unix(), 0).UTC().Format(time.RFC3339),
			DurationUs:  entry.Duration.Microseconds(),
			Command:     cmd,
			CollectedAt: now,
		})
	}

	keyspaceStr, _ := client.Info(ctx, "keyspace").Result()
	ksInfo := parseRedisInfo(keyspaceStr)
	var keyspaces []RedisKeyspace
	for k, v := range ksInfo {
		if !strings.HasPrefix(k, "db") { continue }
		parts := strings.Split(v, ",")
		ks := RedisKeyspace{DSNAlias: alias, DBHost: host, Database: k, CollectedAt: now}
		for _, p := range parts {
			kv := strings.SplitN(p, "=", 2)
			if len(kv) != 2 { continue }
			switch kv[0] {
			case "keys":    ks.Keys    = parseInt64(kv[1])
			case "expires": ks.Expires = parseInt64(kv[1])
			case "avg_ttl": ks.AvgTTLMs = parseInt64(kv[1])
			}
		}
		keyspaces = append(keyspaces, ks)
	}
	return m, slowLogs, keyspaces, nil
}

func parseRedisInfo(s string) map[string]string {
	out := make(map[string]string)
	scanner := bufio.NewScanner(strings.NewReader(s))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") { continue }
		kv := strings.SplitN(line, ":", 2)
		if len(kv) == 2 {
			out[strings.TrimSpace(kv[0])] = strings.TrimSpace(kv[1])
		}
	}
	return out
}

func infoInt64(info map[string]string, key string) int64 {
	return parseInt64(info[key])
}

func parseInt64(s string) int64 {
	n, _ := strconv.ParseInt(strings.TrimSpace(s), 10, 64)
	return n
}
