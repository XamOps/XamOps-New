package collector

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"sort"
	"time"

	"github.com/lib/pq"
)

const fkGraphTableCeiling = 5000 // emitted-table ceiling, ranked by catalog sizeBytes desc (§5)

// collectDBFkGraph reads foreign-key relationships and catalog table weights for every Postgres DSN
// (COL-C1) and emits ONE DbFkGraphDoc per DSN. Node weights (sizeBytes, rowEstimate) are read from
// the catalog in the same pass — relpages × block_size and GREATEST(reltuples,0) — NOT with the
// pg_total_relation_size filesystem stat (PERF-6). The emitted table set is capped at the 5,000
// largest FK-participating tables; edges are kept only when both endpoints survive the cap so the
// collected document is never dangling.
func collectDBFkGraph(ctx context.Context, dsns []string) (DataBatch, error) {
	var docs []DbFkGraphDoc
	for i, dsn := range dsns {
		alias := fmt.Sprintf("db-%d", i)
		engine, host, err := parseDSN(dsn)
		if err != nil {
			continue
		}
		if engine != "postgres" {
			continue
		}
		doc, err := queryPostgresFkGraph(ctx, dsn, alias, host)
		if err != nil {
			log.Printf("WARN: db-fk-graph: %s (%s): %v", alias, host, err)
			continue
		}
		docs = append(docs, *doc)
	}
	return DataBatch{Collector: "db-fk-graph", Items: docs, Count: len(docs), CollectedAt: time.Now().UTC()}, nil
}

func queryPostgresFkGraph(ctx context.Context, dsn, alias, host string) (*DbFkGraphDoc, error) {
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, fmt.Errorf("open: %w", err)
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	db.SetConnMaxLifetime(15 * time.Second)

	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := db.PingContext(pingCtx); err != nil {
		return nil, fmt.Errorf("ping: %w", err)
	}
	if _, err := db.ExecContext(ctx, "SET statement_timeout = '10s'"); err != nil {
		return nil, fmt.Errorf("set statement_timeout: %w", err)
	}

	doc := &DbFkGraphDoc{
		DSNAlias:    alias,
		DBEngine:    "postgres",
		DBHost:      host,
		CollectedAt: time.Now().UTC().Format(time.RFC3339),
		Tables:      []DbFkTable{},
		FkEdges:     []DbFkEdge{},
	}

	// FK edges + the participating table OIDs ────────────────────────────────
	edgeRows, err := db.QueryContext(ctx, postgresFkEdgesQuery)
	if err != nil {
		return nil, fmt.Errorf("query pg_constraint: %w", err)
	}
	type rawEdge struct {
		edge                   DbFkEdge
		childOid, parentOid    int64
	}
	var raw []rawEdge
	oidSet := map[int64]struct{}{}
	for edgeRows.Next() {
		var e DbFkEdge
		var childOid, parentOid int64
		var cols pq.StringArray
		var onDel, onUpd string
		if err := edgeRows.Scan(&e.ConstraintName,
			&e.ChildSchema, &e.ChildTable, &childOid,
			&e.ParentSchema, &e.ParentTable, &parentOid,
			&onDel, &onUpd, &cols); err != nil {
			continue
		}
		e.Columns = []string(cols)
		if e.Columns == nil {
			e.Columns = []string{}
		}
		e.OnDelete = fkActionLabel(onDel)
		e.OnUpdate = fkActionLabel(onUpd)
		raw = append(raw, rawEdge{edge: e, childOid: childOid, parentOid: parentOid})
		oidSet[childOid] = struct{}{}
		oidSet[parentOid] = struct{}{}
	}
	edgeRows.Close()
	if err := edgeRows.Err(); err != nil {
		return nil, err
	}

	if len(oidSet) == 0 {
		// Schema declares no FKs — an honest empty document, not an error.
		doc.TotalFkTables = 0
		doc.TotalFkEdges = 0
		return doc, nil
	}

	// Catalog weights for every participating table ──────────────────────────
	oids := make([]int64, 0, len(oidSet))
	for oid := range oidSet {
		oids = append(oids, oid)
	}
	weightRows, err := db.QueryContext(ctx, postgresFkTableWeightsQuery, pq.Array(oids))
	if err != nil {
		return nil, fmt.Errorf("query catalog weights: %w", err)
	}
	byOid := map[int64]DbFkTable{}
	for weightRows.Next() {
		var t DbFkTable
		if err := weightRows.Scan(&t.Oid, &t.Schema, &t.Name, &t.SizeBytes, &t.RowEstimate); err != nil {
			continue
		}
		if t.SizeBytes < 0 {
			t.SizeBytes = 0
		}
		byOid[t.Oid] = t
	}
	weightRows.Close()
	if err := weightRows.Err(); err != nil {
		return nil, err
	}

	// Collect every participating table, rank by catalog sizeBytes desc, apply the 5,000 ceiling.
	allTables := make([]DbFkTable, 0, len(byOid))
	for _, t := range byOid {
		allTables = append(allTables, t)
	}
	sort.SliceStable(allTables, func(i, j int) bool {
		if allTables[i].SizeBytes != allTables[j].SizeBytes {
			return allTables[i].SizeBytes > allTables[j].SizeBytes
		}
		if allTables[i].RowEstimate != allTables[j].RowEstimate {
			return allTables[i].RowEstimate > allTables[j].RowEstimate
		}
		return allTables[i].Oid < allTables[j].Oid
	})

	doc.TotalFkTables = len(allTables)
	doc.TotalFkEdges = len(raw)

	kept := map[int64]struct{}{}
	for _, t := range allTables {
		if len(doc.Tables) >= fkGraphTableCeiling {
			doc.Truncated = true
			break
		}
		doc.Tables = append(doc.Tables, t)
		kept[t.Oid] = struct{}{}
	}

	// Keep only edges whose BOTH endpoints survived the ceiling — the collected doc is never dangling.
	for _, r := range raw {
		_, haveChild := kept[r.childOid]
		_, haveParent := kept[r.parentOid]
		if haveChild && haveParent {
			doc.FkEdges = append(doc.FkEdges, r.edge)
		} else {
			doc.Truncated = true
		}
	}

	log.Printf("INFO: db-fk-graph: %s collected %d/%d tables, %d/%d edges from %s",
		alias, len(doc.Tables), doc.TotalFkTables, len(doc.FkEdges), doc.TotalFkEdges, host)
	return doc, nil
}

// fkActionLabel maps a pg_constraint confdeltype/confupdtype code to a readable action.
func fkActionLabel(code string) string {
	switch code {
	case "a":
		return "NO ACTION"
	case "r":
		return "RESTRICT"
	case "c":
		return "CASCADE"
	case "n":
		return "SET NULL"
	case "d":
		return "SET DEFAULT"
	}
	return ""
}

const postgresFkEdgesQuery = `
SELECT
	con.conname,
	ncl.nspname  AS child_schema,
	ccl.relname  AS child_table,
	ccl.oid::bigint AS child_oid,
	npa.nspname  AS parent_schema,
	cpa.relname  AS parent_table,
	cpa.oid::bigint AS parent_oid,
	con.confdeltype,
	con.confupdtype,
	COALESCE((
		SELECT array_agg(att.attname ORDER BY u.ord)
		FROM unnest(con.conkey) WITH ORDINALITY AS u(attnum, ord)
		JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = u.attnum
	), ARRAY[]::text[]) AS columns
FROM pg_constraint con
JOIN pg_class     ccl ON ccl.oid = con.conrelid
JOIN pg_namespace ncl ON ncl.oid = ccl.relnamespace
JOIN pg_class     cpa ON cpa.oid = con.confrelid
JOIN pg_namespace npa ON npa.oid = cpa.relnamespace
WHERE con.contype = 'f'
  AND ncl.nspname NOT IN ('pg_catalog','information_schema')
  AND npa.nspname NOT IN ('pg_catalog','information_schema')`

// postgresFkTableWeightsQuery sizes each FK-participating table from the CATALOG — relpages ×
// block_size and GREATEST(reltuples,0) — the PERF-6 prefilter path. It must NOT use
// pg_total_relation_size (the filesystem stat that PERF-6 measured at 3,256 ms / 221 MiB).
const postgresFkTableWeightsQuery = `
SELECT
	c.oid::bigint,
	n.nspname,
	c.relname,
	(c.relpages::bigint * current_setting('block_size')::int)::bigint AS size_bytes,
	GREATEST(c.reltuples, 0)::bigint                                  AS row_estimate
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.oid = ANY($1)`
