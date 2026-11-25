package xampulse

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// fakeCollector writes a shell script standing in for the collector. It appends a line to starts.log on
// every start, then either exits 1 (crash) or sleeps until terminated.
func fakeCollector(t *testing.T, crash bool) (bin, startsLog string) {
	t.Helper()
	dir := t.TempDir()
	startsLog = filepath.Join(dir, "starts.log")
	body := "#!/bin/sh\necho \"$@\" >> '" + startsLog + "'\n"
	if crash {
		body += "exit 1\n"
	} else {
		body += "exec sleep 30\n"
	}
	bin = filepath.Join(dir, "collector.sh")
	if err := os.WriteFile(bin, []byte(body), 0o755); err != nil {
		t.Fatal(err)
	}
	return bin, startsLog
}

func startCount(t *testing.T, path string) int {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	return len(strings.Split(strings.TrimSpace(string(b)), "\n"))
}

func TestSupervisorCrashLoopBacksOffExponentially(t *testing.T) {
	bin, startsLog := fakeCollector(t, true)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var waits []time.Duration
	s := &Supervisor{
		Binary: bin, ConfigPath: filepath.Join(t.TempDir(), "c.yaml"),
		BackoffBase: time.Second, BackoffMax: 5 * time.Second,
		Sleep: func(_ context.Context, d time.Duration) error {
			waits = append(waits, d)
			if len(waits) == 5 {
				cancel()
			}
			return nil
		},
	}
	err := s.Run(ctx)
	if err == nil {
		t.Fatal("Run must return the context error")
	}
	want := []time.Duration{1 * time.Second, 2 * time.Second, 4 * time.Second, 5 * time.Second, 5 * time.Second}
	if len(waits) != len(want) {
		t.Fatalf("waits %v", waits)
	}
	for i := range want {
		if waits[i] != want[i] {
			t.Fatalf("backoff %d: got %v want %v (all %v)", i, waits[i], want[i], waits)
		}
	}
	if startCount(t, startsLog) != 5 {
		t.Fatalf("expected 5 starts, got %d", startCount(t, startsLog))
	}
	if !strings.Contains(readFile(t, startsLog), "--config") {
		t.Fatal("collector must be started with --config <path>")
	}
}

func readFile(t *testing.T, p string) string {
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestSupervisorRestartsOnConfigChange(t *testing.T) {
	bin, startsLog := fakeCollector(t, false)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	s := &Supervisor{Binary: bin, ConfigPath: filepath.Join(t.TempDir(), "c.yaml"), StopGrace: 2 * time.Second}
	go func() { done <- s.Run(ctx) }()

	waitFor(t, func() bool { return startCount(t, startsLog) == 1 }, "first start")
	if err := s.WriteConfig([]byte("a: 1\n")); err != nil {
		t.Fatal(err)
	}
	s.Reload()
	waitFor(t, func() bool { return startCount(t, startsLog) == 2 }, "restart after reload")
	if s.Crashes() != 0 {
		t.Fatalf("a requested reload is not a crash (crashes=%d)", s.Crashes())
	}
	if s.Restarts() != 1 {
		t.Fatalf("restarts=%d", s.Restarts())
	}
	cancel()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("Run did not stop the child and return")
	}
}

func waitFor(t *testing.T, cond func() bool, what string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func TestSupervisorWriteConfigIsAtomicAndReadable(t *testing.T) {
	p := filepath.Join(t.TempDir(), "sub", "c.yaml")
	s := &Supervisor{ConfigPath: p}
	if err := s.WriteConfig([]byte("x: 1\n")); err != nil {
		t.Fatal(err)
	}
	if readFile(t, p) != "x: 1\n" {
		t.Fatal("content")
	}
	entries, _ := os.ReadDir(filepath.Dir(p))
	if len(entries) != 1 {
		t.Fatalf("temporary files left behind: %v", entries)
	}
}
