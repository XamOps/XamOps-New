package xampulse

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func releaseServer(files map[string]string, sumFor map[string]string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/xampulse/1.2.3/SHA256SUMS" {
			var out string
			for name, sum := range sumFor {
				out += sum + "  " + name + "\n"
			}
			_, _ = w.Write([]byte(out))
			return
		}
		if r.URL.Path == "/xampulse/1.2.3/SHA256SUMS.sig" {
			_, _ = w.Write([]byte("sig"))
			return
		}
		const prefix = "/xampulse/1.2.3/"
		if len(r.URL.Path) > len(prefix) {
			if c, ok := files[r.URL.Path[len(prefix):]]; ok {
				_, _ = w.Write([]byte(c))
				return
			}
		}
		http.NotFound(w, r)
	}))
}

func okVerify(sums, sig []byte) error { return nil }
func sha(s string) string             { h := sha256.Sum256([]byte(s)); return hex.EncodeToString(h[:]) }

func TestStageInstallsWhenChecksumAndSignatureMatch(t *testing.T) {
	srv := releaseServer(map[string]string{"xampulse-agent_linux_amd64": "binary-bytes"},
		map[string]string{"xampulse-agent_linux_amd64": sha("binary-bytes")})
	defer srv.Close()
	dir := t.TempDir()
	u := &Updater{ReleaseBaseURL: srv.URL + "/xampulse", BinDir: dir, VerifySignature: okVerify}
	warn, err := u.Stage(context.Background(), "1.2.3", []string{"xampulse-agent_linux_amd64"})
	if err != nil {
		t.Fatal(err)
	}
	if warn != "" {
		t.Fatalf("unexpected warning %q", warn)
	}
	st, err := os.Stat(filepath.Join(dir, "xampulse-agent_linux_amd64"))
	if err != nil || st.Mode().Perm() != 0o755 {
		t.Fatalf("staged file: %v %v", st, err)
	}
}

func TestStageRejectsBadChecksumAndLeavesNothing(t *testing.T) {
	srv := releaseServer(map[string]string{"xampulse-agent_linux_amd64": "tampered"},
		map[string]string{"xampulse-agent_linux_amd64": sha("original")})
	defer srv.Close()
	dir := t.TempDir()
	u := &Updater{ReleaseBaseURL: srv.URL + "/xampulse", BinDir: dir, VerifySignature: okVerify}
	_, err := u.Stage(context.Background(), "1.2.3", []string{"xampulse-agent_linux_amd64"})
	if !errors.Is(err, ErrChecksum) {
		t.Fatalf("want ErrChecksum, got %v", err)
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 0 {
		t.Fatalf("a rejected download must leave no file behind: %v", entries)
	}
}

func TestStageRejectsFileNotListedAndBadVersion(t *testing.T) {
	srv := releaseServer(map[string]string{"x": "y"}, map[string]string{})
	defer srv.Close()
	u := &Updater{ReleaseBaseURL: srv.URL + "/xampulse", BinDir: t.TempDir(), VerifySignature: okVerify}
	if _, err := u.Stage(context.Background(), "1.2.3", []string{"x"}); err == nil {
		t.Fatal("an unlisted file must be refused")
	}
	if _, err := u.Stage(context.Background(), "../../etc", []string{"x"}); err == nil {
		t.Fatal("path-like version must be refused")
	}
}

func TestStageSignatureHook(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/xampulse/1.2.3/SHA256SUMS":
			_, _ = w.Write([]byte(sha("b") + "  f\n"))
		case "/xampulse/1.2.3/SHA256SUMS.sig":
			_, _ = w.Write([]byte("sig"))
		case "/xampulse/1.2.3/f":
			_, _ = w.Write([]byte("b"))
		}
	}))
	defer srv.Close()
	u := &Updater{ReleaseBaseURL: srv.URL + "/xampulse", BinDir: t.TempDir(),
		VerifySignature: func(sums, sig []byte) error { return errors.New("bad signature") }}
	if _, err := u.Stage(context.Background(), "1.2.3", []string{"f"}); err == nil {
		t.Fatal("a failing signature check must stop the update before any download")
	}
}

func TestParseSums(t *testing.T) {
	m := ParseSums([]byte(sha("a") + "  file-a\n" + sha("b") + " *file-b\nbad line\n"))
	if m["file-a"] != sha("a") || m["file-b"] != sha("b") || len(m) != 2 {
		t.Fatalf("%v", m)
	}
}

func TestStageRefusesWithoutAVerifier(t *testing.T) {
	srv := releaseServer(map[string]string{"f": "b"}, map[string]string{"f": sha("b")})
	defer srv.Close()
	dir := t.TempDir()
	u := &Updater{ReleaseBaseURL: srv.URL + "/xampulse", BinDir: dir}
	if _, err := u.Stage(context.Background(), "1.2.3", []string{"f"}); !errors.Is(err, ErrNoSigningKey) {
		t.Fatalf("want ErrNoSigningKey, got %v", err)
	}
	if entries, _ := os.ReadDir(dir); len(entries) != 0 {
		t.Fatalf("nothing may be downloaded: %v", entries)
	}
}
