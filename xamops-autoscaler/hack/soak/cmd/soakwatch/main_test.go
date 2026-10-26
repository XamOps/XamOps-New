package main

import (
	"bytes"
	"context"
	"io"
	"path/filepath"
	"strings"
	"testing"
)

type noRunner struct{ t *testing.T }

func (n noRunner) Start(context.Context, []string) (io.ReadCloser, func() error, error) {
	n.t.Fatal("kubectl must not run")
	return nil, nil, nil
}

func TestWatchWithoutContextExits2(t *testing.T) {
	var stderr bytes.Buffer
	code := run(context.Background(), []string{"watch", "--out", t.TempDir()}, io.Discard, &stderr, noRunner{t})
	if code != 2 || !strings.Contains(stderr.String(), "--context is required") {
		t.Fatalf("code = %d, stderr = %s", code, stderr.String())
	}
}

func TestCheckExitCodes(t *testing.T) {
	for _, tc := range []struct {
		fixture string
		code    int
	}{
		{"clean", 0},
		{"i2-short-dip", 1},
		{"missing-streams", 3},
	} {
		t.Run(tc.fixture, func(t *testing.T) {
			var stdout bytes.Buffer
			dir := filepath.Join("..", "..", "invariants", "testdata", tc.fixture)
			report := filepath.Join(t.TempDir(), "report.json")
			code := run(context.Background(), []string{"check", "--dir", dir, "--report", report}, &stdout, io.Discard, noRunner{t})
			if code != tc.code {
				t.Fatalf("code = %d, want %d\n%s", code, tc.code, stdout.String())
			}
			if !strings.Contains(stdout.String(), "I2") {
				t.Errorf("no verdict lines:\n%s", stdout.String())
			}
		})
	}
}
