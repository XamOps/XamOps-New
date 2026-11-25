package xampulse

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// An enrolled agent that receives UNINSTALL removes its credentials, writes the disabled marker and reports
// ErrUninstalled so the process can exit with a status systemd will not restart.
func TestAgentUninstallsOnServerCommand(t *testing.T) {
	var heartbeats int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/agent/xampulse/enroll/challenge":
			_ = json.NewEncoder(w).Encode(EnrollChallengeResponse{Nonce: "n", ExpiresInSec: 60})
		case "/api/v1/agent/xampulse/enroll":
			_ = json.NewEncoder(w).Encode(EnrollResponse{AccessToken: "acc", RefreshToken: "ref",
				ExpiresAt: time.Now().Add(24 * time.Hour).UTC().Format(time.RFC3339),
				Config:    AgentConfig{ConfigVersion: 1, Level: "T1", GatewayURL: "https://g.example.com/xampulse-ingest", Sources: []Source{{Kind: "SYSTEM"}}}})
		case "/api/v1/agent/xampulse/heartbeat":
			heartbeats++
			if r.Header.Get(TokenHeader) != "acc" {
				w.WriteHeader(401)
				return
			}
			_ = json.NewEncoder(w).Encode(HeartbeatResponse{Command: CommandUninstall})
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	state := t.TempDir()
	collector := filepath.Join(t.TempDir(), "otelcol.sh")
	if err := os.WriteFile(collector, []byte("#!/bin/sh\nexec sleep 30\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	a := &Agent{
		Version: "1.0.0", ServerURL: srv.URL, StateDir: state, CollectorPath: collector, IMDS: fakeIMDS{},
		Uninstaller: Uninstaller{StateDir: state, Systemctl: func(...string) error { return nil }},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	err := a.Run(ctx)
	if !errors.Is(err, ErrUninstalled) {
		t.Fatalf("want ErrUninstalled, got %v", err)
	}
	if !IsDisabled(state) {
		t.Fatal("disabled marker must be written")
	}
	if _, err := os.Stat(filepath.Join(state, TokenFileName)); !os.IsNotExist(err) {
		t.Fatal("tokens must be removed")
	}
	if heartbeats != 1 {
		t.Fatalf("heartbeats=%d", heartbeats)
	}
}

// A refused refresh makes the agent prove its identity again; a 403 there means it is no longer covered.
func TestAgentReenrolsThenUninstallsWhenNoLongerInScope(t *testing.T) {
	enrolls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/agent/xampulse/enroll/challenge":
			_ = json.NewEncoder(w).Encode(EnrollChallengeResponse{Nonce: "n", ExpiresInSec: 60})
		case "/api/v1/agent/xampulse/enroll":
			enrolls++
			w.WriteHeader(403)
		case "/api/v1/agent/xampulse/refresh":
			w.WriteHeader(401)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	state := t.TempDir()
	_ = TokenStore{Dir: state}.Save(StoredTokens{AccessToken: "old", RefreshToken: "r", ExpiresAt: "2020-01-01T00:00:00Z"})
	a := &Agent{Version: "1", ServerURL: srv.URL, StateDir: state, CollectorPath: "/bin/true", IMDS: fakeIMDS{},
		Uninstaller: Uninstaller{StateDir: state, Systemctl: func(...string) error { return nil }}}
	err := a.Run(context.Background())
	if !errors.Is(err, ErrUninstalled) {
		t.Fatalf("want ErrUninstalled, got %v", err)
	}
	if enrolls != 1 || !IsDisabled(state) {
		t.Fatalf("enrolls=%d disabled=%v", enrolls, IsDisabled(state))
	}
}

func TestAgentRefusesToStartWhenDisabledMarkerExists(t *testing.T) {
	state := t.TempDir()
	_ = os.WriteFile(filepath.Join(state, DisabledMarker), nil, 0o644)
	a := &Agent{ServerURL: "http://127.0.0.1:1", StateDir: state, IMDS: fakeIMDS{}}
	if err := a.Run(context.Background()); err == nil {
		t.Fatal("must not run while the marker exists")
	}
}
