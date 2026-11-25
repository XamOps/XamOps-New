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

func refreshServer(status int, next TokenPair, got *RefreshRequest) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/agent/xampulse/refresh" {
			http.NotFound(w, r)
			return
		}
		if got != nil {
			_ = json.NewDecoder(r.Body).Decode(got)
		}
		if status != 200 {
			w.WriteHeader(status)
			return
		}
		_ = json.NewEncoder(w).Encode(next)
	}))
}

func TestTokenRotationOnExpiry(t *testing.T) {
	dir := t.TempDir()
	store := TokenStore{Dir: dir}
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	_ = store.Save(StoredTokens{AccessToken: "old", RefreshToken: "r1", ExpiresAt: now.Add(5 * time.Minute).Format(time.RFC3339)})
	var got RefreshRequest
	srv := refreshServer(200, TokenPair{AccessToken: "new", RefreshToken: "r2", ExpiresAt: now.Add(24 * time.Hour).Format(time.RFC3339)}, &got)
	defer srv.Close()
	m := &TokenManager{Store: store, ServerURL: srv.URL, Now: func() time.Time { return now }}
	tok, err := m.Access(context.Background())
	if err != nil || tok != "new" {
		t.Fatalf("expected rotated token, got %q %v", tok, err)
	}
	if got.RefreshToken != "r1" {
		t.Fatalf("refresh must present the stored refresh token, sent %q", got.RefreshToken)
	}
	st, _ := store.Load()
	if st.RefreshToken != "r2" {
		t.Fatal("rotated refresh token must be stored")
	}
	b, _ := os.ReadFile(store.AccessPath())
	if string(b) != "new" {
		t.Fatal("collector token file must follow the access token")
	}
	info, _ := os.Stat(filepath.Join(dir, TokenFileName))
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("token file mode %o", info.Mode().Perm())
	}
}

func TestTokenStillValidIsNotRefreshed(t *testing.T) {
	dir := t.TempDir()
	store := TokenStore{Dir: dir}
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	_ = store.Save(StoredTokens{AccessToken: "ok", RefreshToken: "r1", ExpiresAt: now.Add(3 * time.Hour).Format(time.RFC3339)})
	m := &TokenManager{Store: store, ServerURL: "http://127.0.0.1:1", Now: func() time.Time { return now }}
	tok, err := m.Access(context.Background())
	if err != nil || tok != "ok" {
		t.Fatalf("got %q %v", tok, err)
	}
}

func TestExpiredRefreshTokenMeansRevoked(t *testing.T) {
	dir := t.TempDir()
	store := TokenStore{Dir: dir}
	_ = store.Save(StoredTokens{AccessToken: "old", RefreshToken: "r1", ExpiresAt: "2020-01-01T00:00:00Z"})
	for _, code := range []int{401, 403} {
		srv := refreshServer(code, TokenPair{}, nil)
		m := &TokenManager{Store: store, ServerURL: srv.URL}
		_, err := m.Access(context.Background())
		srv.Close()
		if !errors.Is(err, ErrRevoked) {
			t.Fatalf("status %d: want ErrRevoked, got %v", code, err)
		}
	}
}

func TestTokenLoadMissingIsNotExist(t *testing.T) {
	_, err := TokenStore{Dir: t.TempDir()}.Load()
	if !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("want ErrNotExist, got %v", err)
	}
}

func TestHeartbeatSendsTokenHeaderNotAuthorization(t *testing.T) {
	dir := t.TempDir()
	store := TokenStore{Dir: dir}
	_ = store.Save(StoredTokens{AccessToken: "acc", RefreshToken: "r", ExpiresAt: time.Now().Add(time.Hour).Format(time.RFC3339)})
	var header, auth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header, auth = r.Header.Get(TokenHeader), r.Header.Get("Authorization")
		_ = json.NewEncoder(w).Encode(HeartbeatResponse{Command: CommandReloadConfig, ConfigVersion: 9})
	}))
	defer srv.Close()
	api := &API{ServerURL: srv.URL, Tokens: &TokenManager{Store: store, ServerURL: srv.URL}}
	resp, err := api.Heartbeat(context.Background(), HeartbeatRequest{AgentVersion: "1"})
	if err != nil || resp.Command != CommandReloadConfig || resp.ConfigVersion != 9 {
		t.Fatalf("%+v %v", resp, err)
	}
	if header != "acc" || auth != "" {
		t.Fatalf("token must travel in %s only (header=%q authorization=%q)", TokenHeader, header, auth)
	}
}
