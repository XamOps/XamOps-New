package xampulse

import (
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// Supervisor runs the OpenTelemetry Collector as a child process: restart with exponential backoff after a
// crash, restart on a config change, health check on the collector's health_check extension.
type Supervisor struct {
	Binary string
	// BinaryFunc, when set, is asked for the binary at every start (a staged upgrade takes effect on restart).
	BinaryFunc func() string
	Args       []string // arguments before --config
	ConfigPath string
	HealthURL  string // default http://localhost:13133/
	Env        []string
	Stdout     io.Writer
	Stderr     io.Writer

	// Tuning; zero values use the defaults noted.
	BackoffBase time.Duration // 1s
	BackoffMax  time.Duration // 1m
	StableAfter time.Duration // 2m: a run this long resets the crash counter
	StopGrace   time.Duration // 10s between SIGTERM and SIGKILL

	// Sleep is injectable for tests.
	Sleep func(ctx context.Context, d time.Duration) error

	restarts atomic.Int64
	crashes  atomic.Int64
	reloadCh chan struct{}
	initOnce sync.Once
	mu       sync.Mutex
	cancel   context.CancelFunc // cancels the running child only
}

func (s *Supervisor) init() {
	s.initOnce.Do(func() { s.reloadCh = make(chan struct{}, 1) })
}

func (s *Supervisor) defaults() (base, max, stable, grace time.Duration) {
	base, max, stable, grace = s.BackoffBase, s.BackoffMax, s.StableAfter, s.StopGrace
	if base <= 0 {
		base = time.Second
	}
	if max <= 0 {
		max = time.Minute
	}
	if stable <= 0 {
		stable = 2 * time.Minute
	}
	if grace <= 0 {
		grace = 10 * time.Second
	}
	return
}

// Restarts is how many times the child has been started after the first start.
func (s *Supervisor) Restarts() int64 { return s.restarts.Load() }

// Crashes is the consecutive crash count used for backoff.
func (s *Supervisor) Crashes() int64 { return s.crashes.Load() }

// WriteConfig atomically replaces the collector config file.
func (s *Supervisor) WriteConfig(data []byte) error {
	return writeFileAtomic(s.ConfigPath, data, 0o640)
}

// Reload restarts the child so that a new config file takes effect. Safe to call at any time.
func (s *Supervisor) Reload() {
	s.init()
	select {
	case s.reloadCh <- struct{}{}:
	default:
	}
	s.mu.Lock()
	if s.cancel != nil {
		s.cancel()
	}
	s.mu.Unlock()
}

// Run blocks until ctx ends. The child is always stopped on return.
func (s *Supervisor) Run(ctx context.Context) error {
	s.init()
	base, max, stable, grace := s.defaults()
	first := true
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if !first {
			s.restarts.Add(1)
		}
		first = false

		childCtx, cancel := context.WithCancel(ctx)
		s.mu.Lock()
		s.cancel = cancel
		s.mu.Unlock()

		started := time.Now()
		err := s.runOnce(childCtx, grace)
		cancel()

		if ctx.Err() != nil {
			return ctx.Err()
		}
		// A requested reload is not a crash: restart immediately.
		select {
		case <-s.reloadCh:
			s.crashes.Store(0)
			continue
		default:
		}
		if time.Since(started) >= stable {
			s.crashes.Store(0)
		}
		n := s.crashes.Add(1)
		wait := Backoff(int(n-1), base, max)
		log.Printf("xampulse: collector exited (%v); restarting in %s (crash %d)", err, wait, n)
		if serr := s.sleep(ctx, wait); serr != nil {
			return serr
		}
	}
}

func (s *Supervisor) sleep(ctx context.Context, d time.Duration) error {
	if s.Sleep != nil {
		return s.Sleep(ctx, d)
	}
	return sleepCtx(ctx, d)
}

func (s *Supervisor) runOnce(ctx context.Context, grace time.Duration) error {
	args := append(append([]string{}, s.Args...), "--config", s.ConfigPath)
	bin := s.Binary
	if s.BinaryFunc != nil {
		bin = s.BinaryFunc()
	}
	cmd := exec.Command(bin, args...)
	cmd.Env = append(os.Environ(), s.Env...)
	cmd.Stdout = s.Stdout
	cmd.Stderr = s.Stderr
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start: %w", err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		return err
	case <-ctx.Done():
		_ = cmd.Process.Signal(syscall.SIGTERM)
		select {
		case err := <-done:
			return err
		case <-time.After(grace):
			_ = cmd.Process.Kill()
			return <-done
		}
	}
}

// Healthy asks the collector's health_check endpoint.
func (s *Supervisor) Healthy(ctx context.Context) bool {
	url := s.HealthURL
	if url == "" {
		url = "http://localhost:13133/"
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return false
	}
	c := http.Client{Timeout: 2 * time.Second}
	resp, err := c.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == http.StatusOK
}
