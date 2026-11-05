package xampulse

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// ErrChecksum: a downloaded file did not match the published SHA-256.
var ErrChecksum = errors.New("xampulse: checksum mismatch")

var versionRe = regexp.MustCompile(`^[A-Za-z0-9._-]{1,32}$`)

// ErrNoSigningKey: auto-upgrade is disabled because no release public key is pinned on this host.
var ErrNoSigningKey = errors.New("xampulse: no pinned release key; upgrades are manual")

// Updater downloads release files into the state directory's bin folder, verifies them and swaps them in
// atomically. The service unit makes /opt read-only, so staged binaries live under the state directory and
// the supervisor and the agent entry point prefer them when present.
type Updater struct {
	ReleaseBaseURL string // e.g. https://releases.xamops.com/xampulse
	BinDir         string // <state-dir>/bin
	HTTP           *http.Client
	// VerifySignature is called with the SHA256SUMS file and its detached signature before any binary is
	// downloaded. It is required: with nil, Stage refuses with ErrNoSigningKey, so an agent never upgrades itself
	// from a checksum served next to the binary. Build it from a pinned public key with PinnedKeyVerifier.
	VerifySignature func(sums, sig []byte) error
}

func (u *Updater) httpc() *http.Client {
	if u.HTTP != nil {
		return u.HTTP
	}
	return &http.Client{Timeout: 5 * time.Minute}
}

func (u *Updater) get(ctx context.Context, url string, limit int64) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	resp, err := u.httpc().Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GET %s: status %d", url, resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, limit))
}

// ParseSums reads "<sha256>  <file>" lines.
func ParseSums(b []byte) map[string]string {
	out := map[string]string{}
	sc := bufio.NewScanner(strings.NewReader(string(b)))
	for sc.Scan() {
		f := strings.Fields(sc.Text())
		if len(f) == 2 && len(f[0]) == 64 {
			out[strings.TrimPrefix(f[1], "*")] = strings.ToLower(f[0])
		}
	}
	return out
}

// Stage downloads the named files for version into BinDir, each swapped in only after its checksum matched.
// A signature verifier is mandatory. The returned warning is kept for callers and is always empty now.
func (u *Updater) Stage(ctx context.Context, version string, files []string) (warning string, err error) {
	if !versionRe.MatchString(version) {
		return "", fmt.Errorf("invalid version %q", version)
	}
	if u.VerifySignature == nil {
		return "", ErrNoSigningKey
	}
	base := strings.TrimRight(u.ReleaseBaseURL, "/") + "/" + version
	sums, err := u.get(ctx, base+"/SHA256SUMS", 1<<20)
	if err != nil {
		return "", err
	}
	sig, err := u.get(ctx, base+"/SHA256SUMS.sig", 64<<10)
	if err != nil {
		return "", err
	}
	if err := u.VerifySignature(sums, sig); err != nil {
		return "", fmt.Errorf("release signature: %w", err)
	}
	expected := ParseSums(sums)
	for _, name := range files {
		want, ok := expected[name]
		if !ok {
			return warning, fmt.Errorf("%s is not listed in SHA256SUMS", name)
		}
		if err := u.download(ctx, base+"/"+name, filepath.Join(u.BinDir, name), want); err != nil {
			return warning, err
		}
	}
	return warning, nil
}

func (u *Updater) download(ctx context.Context, url, dest, wantSHA string) error {
	if err := os.MkdirAll(u.BinDir, 0o750); err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	resp, err := u.httpc().Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("GET %s: status %d", url, resp.StatusCode)
	}
	tmp, err := os.CreateTemp(u.BinDir, ".dl-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName) // no-op after a successful rename
	h := sha256.New()
	if _, err := io.Copy(io.MultiWriter(tmp, h), io.LimitReader(resp.Body, 512<<20)); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Chmod(0o755); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if got := hex.EncodeToString(h.Sum(nil)); got != strings.ToLower(wantSHA) {
		return fmt.Errorf("%w for %s", ErrChecksum, filepath.Base(dest))
	}
	return os.Rename(tmpName, dest)
}
