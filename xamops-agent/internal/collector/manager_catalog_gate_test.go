package collector

import (
	"testing"
	"time"
)

// TestCatalogGate asserts the hourly catalog collectors run on the first cycle and skip on an
// immediate second cycle within the interval, independently per collector name.
func TestCatalogGate(t *testing.T) {
	m := &Manager{}
	if !m.catalogDue("db-access-grants", time.Hour) {
		t.Fatalf("first call must be due")
	}
	if m.catalogDue("db-access-grants", time.Hour) {
		t.Fatalf("immediate second call within the interval must skip")
	}
	// A different collector name is tracked independently.
	if !m.catalogDue("db-fk-graph", time.Hour) {
		t.Fatalf("a different collector name must be due on its own first call")
	}
	// A zero interval makes every call due (elapsed >= 0 is not < 0).
	if !m.catalogDue("db-access-grants", 0) {
		t.Fatalf("a zero interval must always be due")
	}
}
