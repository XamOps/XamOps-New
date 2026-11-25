package xampulse

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// ErrRevoked: the server refused the refresh token. The agent must uninstall itself.
var ErrRevoked = errors.New("xampulse: credentials were revoked")

// TokenFileName holds the access and refresh token (mode 0600). AccessTokenFileName holds only the access
// token, which the collector's bearertokenauth extension reads and re-reads when it changes.
const (
	TokenFileName       = "agent-token.json"
	AccessTokenFileName = "access-token"
)

// StoredTokens is the on-disk shape.
type StoredTokens struct {
	AccessToken  string `json:"accessToken"`
	RefreshToken string `json:"refreshToken"`
	ExpiresAt    string `json:"expiresAt"`
}

// TokenStore persists tokens under the state directory with owner-only permissions.
type TokenStore struct {
	Dir string
}

func (s TokenStore) path() string       { return filepath.Join(s.Dir, TokenFileName) }
func (s TokenStore) AccessPath() string { return filepath.Join(s.Dir, AccessTokenFileName) }

// Save writes both files atomically (temp file in the same directory, then rename) with mode 0600.
func (s TokenStore) Save(t StoredTokens) error {
	b, err := json.Marshal(t)
	if err != nil {
		return err
	}
	if err := writeFileAtomic(s.path(), b, 0o600); err != nil {
		return err
	}
	return writeFileAtomic(s.AccessPath(), []byte(t.AccessToken), 0o600)
}

// Load returns os.ErrNotExist when the agent has not enrolled yet.
func (s TokenStore) Load() (StoredTokens, error) {
	var t StoredTokens
	b, err := os.ReadFile(s.path())
	if err != nil {
		return t, err
	}
	if err := json.Unmarshal(b, &t); err != nil {
		return t, fmt.Errorf("token file is corrupt: %w", err)
	}
	return t, nil
}

func writeFileAtomic(path string, data []byte, mode os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".tmp-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	cleanup := func() { _ = os.Remove(name) }
	if err := tmp.Chmod(mode); err != nil {
		tmp.Close()
		cleanup()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		cleanup()
		return err
	}
	if err := tmp.Close(); err != nil {
		cleanup()
		return err
	}
	if err := os.Rename(name, path); err != nil {
		cleanup()
		return err
	}
	return nil
}

// TokenManager hands out a valid access token, refreshing (with rotation) before expiry.
type TokenManager struct {
	Store     TokenStore
	ServerURL string
	HTTP      *http.Client
	Now       func() time.Time
	// RefreshBefore is how long before expiry a refresh is attempted. Default 10 minutes.
	RefreshBefore time.Duration

	mu sync.Mutex
}

func (m *TokenManager) now() time.Time {
	if m.Now != nil {
		return m.Now()
	}
	return time.Now()
}

// Access returns a usable access token. A 401/403 from the refresh endpoint yields ErrRevoked.
func (m *TokenManager) Access(ctx context.Context) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	t, err := m.Store.Load()
	if err != nil {
		return "", err
	}
	before := m.RefreshBefore
	if before <= 0 {
		before = 10 * time.Minute
	}
	exp, perr := time.Parse(time.RFC3339, t.ExpiresAt)
	if perr == nil && m.now().Add(before).Before(exp) {
		return t.AccessToken, nil
	}
	return m.refreshLocked(ctx, t)
}

// ForceRefresh rotates the tokens now (used after a 401 from the API).
func (m *TokenManager) ForceRefresh(ctx context.Context) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	t, err := m.Store.Load()
	if err != nil {
		return "", err
	}
	return m.refreshLocked(ctx, t)
}

func (m *TokenManager) refreshLocked(ctx context.Context, t StoredTokens) (string, error) {
	body, _ := json.Marshal(RefreshRequest{RefreshToken: t.RefreshToken})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(m.ServerURL, "/")+agentAPIPath+"/refresh", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	c := m.HTTP
	if c == nil {
		c = &http.Client{Timeout: 20 * time.Second}
	}
	resp, err := c.Do(req)
	if err != nil {
		return "", fmt.Errorf("refresh: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	switch {
	case resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden:
		return "", ErrRevoked
	case resp.StatusCode != http.StatusOK:
		return "", fmt.Errorf("refresh: status %d", resp.StatusCode)
	}
	var pair TokenPair
	if err := json.Unmarshal(raw, &pair); err != nil || pair.AccessToken == "" || pair.RefreshToken == "" {
		return "", errors.New("refresh: bad response")
	}
	next := StoredTokens{AccessToken: pair.AccessToken, RefreshToken: pair.RefreshToken, ExpiresAt: pair.ExpiresAt}
	if err := m.Store.Save(next); err != nil {
		return "", fmt.Errorf("refresh: could not store rotated tokens: %w", err)
	}
	return next.AccessToken, nil
}
