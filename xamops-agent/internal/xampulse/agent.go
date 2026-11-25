package xampulse

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"
)

// Agent ties enrolment, token upkeep, config rendering, collector supervision and heartbeats together.
type Agent struct {
	Version       string
	ServerURL     string
	StateDir      string
	CollectorPath string
	// ReleaseBaseURL enables UPGRADE commands (optional).
	ReleaseBaseURL string
	// PinnedKeyPath is the release public key; empty means DefaultPinnedKeyPath. Without the file UPGRADE is ignored.
	PinnedKeyPath string
	Uninstaller   Uninstaller
	IMDS          Credentialer

	started time.Time
	mu      sync.Mutex
	cfg     AgentConfig
}

func (a *Agent) store() TokenStore { return TokenStore{Dir: a.StateDir} }

func (a *Agent) queueDir() string   { return filepath.Join(a.StateDir, "queue") }
func (a *Agent) configPath() string { return filepath.Join(a.StateDir, "collector.yaml") }

// EnsureEnrolled returns the initial config, enrolling when there are no stored tokens.
func (a *Agent) EnsureEnrolled(ctx context.Context) error {
	if _, err := a.store().Load(); err == nil {
		return nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	e := &Enroller{ServerURL: a.ServerURL, IMDS: a.IMDS, AgentVersion: a.Version}
	res, err := e.Enroll(ctx)
	if err != nil {
		return err
	}
	if err := a.store().Save(StoredTokens{AccessToken: res.AccessToken, RefreshToken: res.RefreshToken, ExpiresAt: res.ExpiresAt}); err != nil {
		return err
	}
	a.mu.Lock()
	a.cfg = res.Config
	a.mu.Unlock()
	return nil
}

// Run enrols if needed, starts the collector and reports until ctx ends or the server removes the agent.
// When the server refuses the stored credentials (rotation reuse, expiry) the agent proves its identity again once
// before giving up; a refusal there means the instance is no longer covered and the agent uninstalls itself.
// It returns ErrUninstalled after that so the caller can exit with ExitUninstalled.
func (a *Agent) Run(ctx context.Context) error {
	const maxReenrols = 3
	reenrols := 0
	for {
		err := a.runOnce(ctx)
		if !errors.Is(err, ErrRevoked) {
			return err
		}
		reenrols++
		if reenrols > maxReenrols {
			return a.handleAuthError(err)
		}
		log.Printf("xampulse: credentials refused; proving identity again (%d/%d)", reenrols, maxReenrols)
		_ = os.Remove(filepath.Join(a.StateDir, TokenFileName))
		_ = os.Remove(filepath.Join(a.StateDir, AccessTokenFileName))
		a.mu.Lock()
		a.cfg = AgentConfig{}
		a.mu.Unlock()
		if eerr := a.EnsureEnrolled(ctx); eerr != nil {
			return a.handleAuthError(eerr)
		}
	}
}

func (a *Agent) runOnce(ctx context.Context) error {
	a.started = time.Now()
	if IsDisabled(a.StateDir) {
		return fmt.Errorf("collection is disabled by %s", filepath.Join(a.StateDir, DisabledMarker))
	}
	if err := a.EnsureEnrolled(ctx); err != nil {
		return a.handleAuthError(err)
	}
	tokens := &TokenManager{Store: a.store(), ServerURL: a.ServerURL}
	api := &API{ServerURL: a.ServerURL, Tokens: tokens}

	if _, err := tokens.Access(ctx); err != nil {
		return err
	}
	a.mu.Lock()
	cfg := a.cfg
	a.mu.Unlock()
	if cfg.GatewayURL == "" { // restart of an enrolled agent: pull the config
		fetched, err := api.FetchConfig(ctx, 0)
		if err != nil {
			return err
		}
		if fetched == nil {
			return errors.New("server returned no config")
		}
		cfg = *fetched
		a.mu.Lock()
		a.cfg = cfg
		a.mu.Unlock()
	}

	sup := &Supervisor{BinaryFunc: a.collectorBinary, ConfigPath: a.configPath(), Stdout: os.Stdout, Stderr: os.Stderr}
	if err := a.render(sup, cfg); err != nil {
		return err
	}
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	supDone := make(chan error, 1)
	go func() { supDone <- sup.Run(runCtx) }()

	hbErr := api.Loop(runCtx, HeartbeatInterval, statusFunc(func(c context.Context) HeartbeatRequest {
		return a.status(c, sup)
	}), func(resp HeartbeatResponse) error {
		return a.onCommand(runCtx, api, sup, resp)
	})
	cancel()
	<-supDone
	if hbErr == nil || errors.Is(hbErr, context.Canceled) {
		return nil
	}
	return hbErr
}

type statusFunc func(ctx context.Context) HeartbeatRequest

func (f statusFunc) Status(ctx context.Context) HeartbeatRequest { return f(ctx) }

var errUninstalled = errors.New("xampulse: uninstalled by server")

// ErrUninstalled is returned by Run after the server asked the agent to remove itself.
var ErrUninstalled = errUninstalled

func (a *Agent) handleAuthError(err error) error {
	if errors.Is(err, ErrRevoked) || errors.Is(err, ErrNotInScope) {
		log.Printf("xampulse: %v; uninstalling", err)
		if uerr := a.Uninstaller.Uninstall(); uerr != nil {
			log.Printf("xampulse: uninstall: %v", uerr)
		}
		return ErrUninstalled
	}
	return err
}

func (a *Agent) collectorBinary() string {
	staged := filepath.Join(a.StateDir, "bin", fmt.Sprintf("xampulse-otelcol_linux_%s", runtime.GOARCH))
	if st, err := os.Stat(staged); err == nil && !st.IsDir() {
		return staged
	}
	return a.CollectorPath
}

func (a *Agent) render(sup *Supervisor, cfg AgentConfig) error {
	_, conntrackErr := os.Stat(ConntrackCountFile)
	has := collectorComponents(context.Background(), a.collectorBinary())
	data, err := RenderCollectorConfig(cfg, RenderOptions{
		QueueDir:        a.queueDir(),
		AccessTokenFile: a.store().AccessPath(),
		Conntrack:       conntrackErr == nil,
		EC2Receiver:     has(EC2ReceiverType),
		FilterProcessor: has(FilterProcessorType),
	})
	if err != nil {
		return fmt.Errorf("render collector config: %w", err)
	}
	return sup.WriteConfig(data)
}

func (a *Agent) status(ctx context.Context, sup *Supervisor) HeartbeatRequest {
	var errs []string
	state := "RUNNING"
	if !sup.Healthy(ctx) {
		state = "UNHEALTHY"
		errs = append(errs, "collector health check failed")
	}
	a.mu.Lock()
	cfg := a.cfg
	a.mu.Unlock()
	var statuses []SourceStatus
	for _, s := range cfg.Sources {
		statuses = append(statuses, SourceStatus{Source: s.Kind, State: state})
	}
	return HeartbeatRequest{
		AgentVersion:     a.Version,
		CollectorVersion: "bundled",
		UptimeSec:        int64(time.Since(a.started).Seconds()),
		Queue:            QueueStatus{Bytes: QueueBytes(a.queueDir()), Dropped: DroppedItems(ctx, "")},
		SourcesStatus:    statuses,
		Errors:           errs,
	}
}

func (a *Agent) onCommand(ctx context.Context, api *API, sup *Supervisor, resp HeartbeatResponse) error {
	switch resp.Command {
	case CommandReloadConfig:
		a.mu.Lock()
		have := a.cfg.ConfigVersion
		a.mu.Unlock()
		fetched, err := api.FetchConfig(ctx, have)
		if err != nil {
			return err
		}
		if fetched == nil {
			return nil
		}
		if err := a.render(sup, *fetched); err != nil {
			log.Printf("xampulse: keeping the previous config: %v", err)
			return nil
		}
		a.mu.Lock()
		a.cfg = *fetched
		a.mu.Unlock()
		sup.Reload()
	case CommandUpgrade:
		if a.ReleaseBaseURL == "" || resp.UpgradeTo == "" {
			log.Printf("xampulse: UPGRADE ignored (no release URL or version)")
			return nil
		}
		keyPath := a.PinnedKeyPath
		if keyPath == "" {
			keyPath = DefaultPinnedKeyPath
		}
		verify, err := LoadPinnedVerifier(keyPath)
		if err != nil {
			log.Printf("xampulse: UPGRADE ignored (pinned key unusable): %v", err)
			return nil
		}
		if verify == nil {
			log.Printf("xampulse: UPGRADE ignored (no pinned release key at %s); upgrades are manual", keyPath)
			return nil
		}
		u := &Updater{ReleaseBaseURL: a.ReleaseBaseURL, BinDir: filepath.Join(a.StateDir, "bin"), VerifySignature: verify}
		warn, err := u.Stage(ctx, resp.UpgradeTo, []string{
			fmt.Sprintf("xampulse-agent_linux_%s", runtime.GOARCH),
			fmt.Sprintf("xampulse-otelcol_linux_%s", runtime.GOARCH),
		})
		if err != nil {
			log.Printf("xampulse: upgrade not applied: %v", err)
			return nil
		}
		if warn != "" {
			log.Printf("xampulse: %s", warn)
		}
		sup.Reload() // picks up the staged collector; the agent binary is replaced on the next service start
	case CommandUninstall:
		if err := a.Uninstaller.Uninstall(); err != nil {
			log.Printf("xampulse: uninstall: %v", err)
		}
		return ErrUninstalled
	}
	return nil
}
