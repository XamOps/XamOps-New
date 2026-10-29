package pusher

import "testing"

// SEC-11: the EXPLAIN validator must accept a single read query, reject stacked statements,
// and reject anything that does not begin with SELECT or WITH.
func TestValidateExplainQuery(t *testing.T) {
	cases := []struct {
		name    string
		query   string
		wantErr bool
	}{
		{"single select", "SELECT * FROM users WHERE id = 1", false},
		{"single select trailing semicolon", "SELECT 1;", false},
		{"cte with", "WITH t AS (SELECT 1) SELECT * FROM t", false},
		{"lowercase select", "select id from orders", false},
		{"leading whitespace", "   SELECT 1  ", false},
		{"stacked statements", "SELECT 1; DROP TABLE users", true},
		{"stacked with trailing semicolon", "SELECT 1; DELETE FROM users;", true},
		{"non-select update", "UPDATE users SET name = 'x'", true},
		{"non-select delete", "DELETE FROM users", true},
		{"non-select insert", "INSERT INTO users VALUES (1)", true},
		{"empty", "   ", true},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := validateExplainQuery(tc.query)
			if tc.wantErr && err == nil {
				t.Fatalf("validateExplainQuery(%q): expected an error, got nil", tc.query)
			}
			if !tc.wantErr && err != nil {
				t.Fatalf("validateExplainQuery(%q): expected no error, got %v", tc.query, err)
			}
		})
	}
}
