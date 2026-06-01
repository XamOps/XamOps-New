package collector

import (
	"context"
	"fmt"
	"log"
	"strings"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

func collectMongoAll(ctx context.Context, dsns []string) []DataBatch {
	var batches []DataBatch
	var metrics    []MongoInstanceMetrics
	var slowOps    []MongoSlowOp
	var indexStats []MongoIndexStat
	var replStatus []MongoReplStatus

	for i, dsn := range dsns {
		_, host, err := parseDSN(dsn)
		if err != nil {
			log.Printf("WARN: mongo: DSN %d parse error: %v", i, err)
			continue
		}
		alias := fmt.Sprintf("db-%d", i)
		client, err := connectMongo(ctx, dsn)
		if err != nil {
			log.Printf("WARN: mongo: %s connect: %v", host, err)
			continue
		}
		qCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
		if m, err := mongoInstanceMetrics(qCtx, client, alias, host); err == nil {
			metrics = append(metrics, m)
		} else {
			log.Printf("WARN: mongo-instance-metrics: %s: %v", host, err)
		}
		if ops, err := mongoSlowOps(qCtx, client, alias, host); err == nil {
			slowOps = append(slowOps, ops...)
		} else {
			log.Printf("INFO: mongo-slow-ops: %s: %v", host, err)
		}
		if stats, err := mongoIndexStats(qCtx, client, alias, host); err == nil {
			indexStats = append(indexStats, stats...)
		} else {
			log.Printf("WARN: mongo-index-stats: %s: %v", host, err)
		}
		if rs, err := mongoReplStatus(qCtx, client, alias, host); err == nil {
			replStatus = append(replStatus, rs)
		} else {
			log.Printf("INFO: mongo-repl-status: %s: not a replica set or insufficient privileges", host)
		}
		cancel()
		_ = client.Disconnect(context.Background())
	}

	now := time.Now().UTC()
	if len(metrics) > 0 {
		batches = append(batches, DataBatch{Collector: "mongo-instance-metrics", Items: metrics, Count: len(metrics), CollectedAt: now})
	}
	if len(slowOps) > 0 {
		batches = append(batches, DataBatch{Collector: "mongo-slow-ops", Items: slowOps, Count: len(slowOps), CollectedAt: now})
	}
	if len(indexStats) > 0 {
		batches = append(batches, DataBatch{Collector: "mongo-index-stats", Items: indexStats, Count: len(indexStats), CollectedAt: now})
	}
	if len(replStatus) > 0 {
		batches = append(batches, DataBatch{Collector: "mongo-repl-status", Items: replStatus, Count: len(replStatus), CollectedAt: now})
	}
	return batches
}

func connectMongo(ctx context.Context, dsn string) (*mongo.Client, error) {
	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	opts := options.Client().ApplyURI(dsn).
		SetServerSelectionTimeout(5 * time.Second).
		SetConnectTimeout(5 * time.Second)
	client, err := mongo.Connect(pingCtx, opts)
	if err != nil {
		return nil, fmt.Errorf("connect: %w", err)
	}
	if err := client.Ping(pingCtx, nil); err != nil {
		_ = client.Disconnect(context.Background())
		return nil, fmt.Errorf("ping: %w", err)
	}
	return client, nil
}

func mongoInstanceMetrics(ctx context.Context, client *mongo.Client, alias, host string) (MongoInstanceMetrics, error) {
	var result bson.M
	if err := client.Database("admin").RunCommand(ctx, bson.D{{Key: "serverStatus", Value: 1}}).Decode(&result); err != nil {
		return MongoInstanceMetrics{}, fmt.Errorf("serverStatus: %w", err)
	}
	m := MongoInstanceMetrics{DSNAlias: alias, DBHost: host, CollectedAt: time.Now().UTC().Format(time.RFC3339)}
	if conns, ok := result["connections"].(bson.M); ok {
		m.CurrentConns = safeMongoInt64(conns["current"])
		m.AvailableConns = safeMongoInt64(conns["available"])
		m.TotalCreatedConns = safeMongoInt64(conns["totalCreated"])
	}
	if mem, ok := result["mem"].(bson.M); ok {
		m.ResidentMemMb = safeMongoInt64(mem["resident"])
		m.VirtualMemMb = safeMongoInt64(mem["virtual"])
	}
	if ops, ok := result["opcounters"].(bson.M); ok {
		m.OpsInsert = safeMongoInt64(ops["insert"])
		m.OpsQuery = safeMongoInt64(ops["query"])
		m.OpsUpdate = safeMongoInt64(ops["update"])
		m.OpsDelete = safeMongoInt64(ops["delete"])
		m.OpsGetmore = safeMongoInt64(ops["getmore"])
		m.OpsCommand = safeMongoInt64(ops["command"])
	}
	if net, ok := result["network"].(bson.M); ok {
		m.NetworkBytesIn = safeMongoInt64(net["bytesIn"])
		m.NetworkBytesOut = safeMongoInt64(net["bytesOut"])
	}
	m.UptimeSeconds = safeMongoInt64(result["uptime"])
	if v, ok := result["version"].(string); ok {
		m.Version = v
	}
	return m, nil
}

func mongoSlowOps(ctx context.Context, client *mongo.Client, alias, host string) ([]MongoSlowOp, error) {
	var result bson.M
	if err := client.Database("admin").RunCommand(ctx, bson.D{
		{Key: "currentOp", Value: 1},
		{Key: "active", Value: true},
		{Key: "secs_running", Value: bson.D{{Key: "$gte", Value: 1}}},
	}).Decode(&result); err != nil {
		return nil, fmt.Errorf("currentOp: %w", err)
	}
	inprog, ok := result["inprog"].(bson.A)
	if !ok {
		return nil, nil
	}
	now := time.Now().UTC().Format(time.RFC3339)
	var ops []MongoSlowOp
	for _, raw := range inprog {
		op, ok := raw.(bson.M)
		if !ok {
			continue
		}
		var ns, opType, digest string
		if v, ok := op["ns"].(string); ok { ns = v }
		if v, ok := op["op"].(string); ok { opType = v }
		if q, ok := op["command"].(bson.M); ok {
			digest = mongoDigest(q)
		} else if q, ok := op["query"].(bson.M); ok {
			digest = mongoDigest(q)
		}
		secsRunning := safeMongoFloat64(op["secs_running"])
		ops = append(ops, MongoSlowOp{
			DSNAlias:    alias,
			DBHost:      host,
			Namespace:   ns,
			Op:          opType,
			QueryDigest: digest,
			Millis:      int64(secsRunning * 1000),
			Timestamp:   now,
			CollectedAt: now,
		})
	}
	return ops, nil
}

func mongoIndexStats(ctx context.Context, client *mongo.Client, alias, host string) ([]MongoIndexStat, error) {
	dbNames, err := client.ListDatabaseNames(ctx, bson.D{})
	if err != nil {
		return nil, fmt.Errorf("listDatabases: %w", err)
	}
	now := time.Now().UTC().Format(time.RFC3339)
	var stats []MongoIndexStat
	skip := map[string]bool{"admin": true, "config": true, "local": true}
	for _, dbName := range dbNames {
		if skip[dbName] {
			continue
		}
		collNames, err := client.Database(dbName).ListCollectionNames(ctx, bson.D{})
		if err != nil {
			continue
		}
		for _, collName := range collNames {
			cursor, err := client.Database(dbName).Collection(collName).Aggregate(ctx,
				mongo.Pipeline{bson.D{{Key: "$indexStats", Value: bson.D{}}}})
			if err != nil {
				continue
			}
			var results []bson.M
			_ = cursor.All(ctx, &results)
			cursor.Close(ctx)
			for _, r := range results {
				var idxName string
				if v, ok := r["name"].(string); ok { idxName = v }
				var accessOps int64
				var accessSince string
				if acc, ok := r["accesses"].(bson.M); ok {
					accessOps = safeMongoInt64(acc["ops"])
					if t, ok := acc["since"].(time.Time); ok {
						accessSince = t.Format(time.RFC3339)
					}
				}
				stats = append(stats, MongoIndexStat{
					DSNAlias:    alias,
					DBHost:      host,
					Database:    dbName,
					Collection:  collName,
					IndexName:   idxName,
					AccessOps:   accessOps,
					AccessSince: accessSince,
					CollectedAt: now,
				})
			}
		}
	}
	return stats, nil
}

func mongoReplStatus(ctx context.Context, client *mongo.Client, alias, host string) (MongoReplStatus, error) {
	var result bson.M
	if err := client.Database("admin").RunCommand(ctx, bson.D{{Key: "replSetGetStatus", Value: 1}}).Decode(&result); err != nil {
		return MongoReplStatus{}, fmt.Errorf("replSetGetStatus: %w", err)
	}
	rs := MongoReplStatus{DSNAlias: alias, DBHost: host, CollectedAt: time.Now().UTC().Format(time.RFC3339)}
	if v, ok := result["set"].(string); ok { rs.SetName = v }
	rs.MyState = int(safeMongoInt64(result["myState"]))
	members, ok := result["members"].(bson.A)
	if !ok {
		return rs, nil
	}
	var primaryOptime, myOptime time.Time
	for _, rawMember := range members {
		m, ok := rawMember.(bson.M)
		if !ok { continue }
		state := safeMongoInt64(m["state"])
		ot, _ := m["optimeDate"].(time.Time)
		self, _ := m["self"].(bool)
		if state == 1 { primaryOptime = ot }
		if self       { myOptime = ot }
	}
	if !primaryOptime.IsZero() && !myOptime.IsZero() {
		lag := primaryOptime.Sub(myOptime).Seconds()
		if lag < 0 { lag = 0 }
		rs.LagSeconds = lag
	}
	return rs, nil
}

func mongoDigest(m bson.M) string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	return "{" + strings.Join(keys, ", ") + "}"
}

func safeMongoInt64(v interface{}) int64 {
	switch n := v.(type) {
	case int32:   return int64(n)
	case int64:   return n
	case int:     return int64(n)
	case float64: return int64(n)
	}
	return 0
}

func safeMongoFloat64(v interface{}) float64 {
	switch n := v.(type) {
	case float64: return n
	case int32:   return float64(n)
	case int64:   return float64(n)
	case int:     return float64(n)
	}
	return 0
}
