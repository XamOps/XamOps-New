package collector

import (
	"reflect"
	"strings"
	"testing"
)

// TestLockWaitsQueryNoStatementText is the SEC invariant: the lock-waits query pins session
// identity / wait / age columns only and NEVER selects pg_stat_activity.query (the statement text).
// query_start and query_id are identity/age fields and are allowed.
func TestLockWaitsQueryNoStatementText(t *testing.T) {
	for _, v := range []int{130000, 140000, 160000, 0} {
		q := postgresLockWaitsQuery(v)
		for _, forbidden := range []string{"a.query,", "a.query ", "a.query\n", "a.query\t"} {
			if strings.Contains(q, forbidden) {
				t.Errorf("lock-waits query must not select the statement text a.query (version %d):\n%s", v, q)
			}
		}
		if !strings.Contains(q, "pg_blocking_pids") {
			t.Errorf("lock-waits query must use pg_blocking_pids (version %d):\n%s", v, q)
		}
		if !strings.Contains(q, "make_interval(secs => $1)") {
			t.Errorf("lock-waits query must bound long-running/idle-in-transaction by the $1 threshold (version %d):\n%s", v, q)
		}
	}
	// query_id (statement hash) is selected only on PG14+, '' below.
	if !strings.Contains(postgresLockWaitsQuery(140000), "a.query_id") {
		t.Errorf("PG14+ should expose the query_id hash")
	}
	if strings.Contains(postgresLockWaitsQuery(130000), "a.query_id") {
		t.Errorf("PG<14 must not reference a.query_id (column does not exist)")
	}
}

// TestDbLockWaitHasNoQueryTextField asserts the DTO cannot carry statement text: no field whose
// JSON tag is exactly "query", "statement", or "queryText".
func TestDbLockWaitHasNoQueryTextField(t *testing.T) {
	ty := reflect.TypeOf(DbLockWait{})
	for i := 0; i < ty.NumField(); i++ {
		tag := ty.Field(i).Tag.Get("json")
		name := strings.Split(tag, ",")[0]
		switch name {
		case "query", "statement", "queryText":
			t.Errorf("DbLockWait must not carry statement text (field %s / json %q)", ty.Field(i).Name, name)
		}
	}
}

// TestClassifyLockWait covers the category precedence derived from identity/wait/age only.
func TestClassifyLockWait(t *testing.T) {
	blockers := map[int]bool{42: true}
	cases := []struct {
		w    DbLockWait
		want string
	}{
		{DbLockWait{PID: 1, BlockedByPids: "42"}, "blocked"},
		{DbLockWait{PID: 42}, "blocking"},
		{DbLockWait{PID: 7, State: "idle in transaction"}, "idle_in_transaction"},
		{DbLockWait{PID: 8, State: "active"}, "long_running"},
	}
	for _, c := range cases {
		if got := classifyLockWait(c.w, blockers); got != c.want {
			t.Errorf("classifyLockWait(%+v) = %q, want %q", c.w, got, c.want)
		}
	}
}

func TestSplitPids(t *testing.T) {
	got := splitPids("12,34, 56")
	want := []int{12, 34, 56}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("splitPids = %v, want %v", got, want)
	}
	if len(splitPids("")) != 0 {
		t.Errorf("splitPids(\"\") must be empty")
	}
}
