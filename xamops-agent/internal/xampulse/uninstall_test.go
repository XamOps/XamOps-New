package xampulse

import (
	"os"
	"path/filepath"
	"testing"
)

func TestUninstallWritesMarkerAndRemovesTokens(t *testing.T) {
	state := t.TempDir()
	install := filepath.Join(t.TempDir(), "opt")
	unitDir := t.TempDir()
	unit := filepath.Join(unitDir, "xampulse-agent.service")
	for _, p := range []string{filepath.Join(state, TokenFileName), filepath.Join(state, AccessTokenFileName), unit, filepath.Join(install, "xampulse-agent")} {
		_ = os.MkdirAll(filepath.Dir(p), 0o755)
		if err := os.WriteFile(p, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	var calls [][]string
	u := Uninstaller{StateDir: state, InstallDir: install, UnitPath: unit,
		Systemctl: func(args ...string) error { calls = append(calls, args); return nil }}
	if err := u.Uninstall(); err != nil {
		t.Fatal(err)
	}
	if !IsDisabled(state) {
		t.Fatal("disabled marker must exist")
	}
	for _, p := range []string{filepath.Join(state, TokenFileName), filepath.Join(state, AccessTokenFileName), unit, install} {
		if _, err := os.Stat(p); !os.IsNotExist(err) {
			t.Fatalf("%s should be gone (%v)", p, err)
		}
	}
	if len(calls) < 2 || calls[0][0] != "disable" {
		t.Fatalf("systemctl calls %v", calls)
	}
}

func TestUninstallIsIdempotent(t *testing.T) {
	state := t.TempDir()
	u := Uninstaller{StateDir: state, Systemctl: func(...string) error { return nil }}
	if err := u.Uninstall(); err != nil {
		t.Fatal(err)
	}
	if err := u.Uninstall(); err != nil {
		t.Fatalf("second uninstall must also succeed: %v", err)
	}
	if !IsDisabled(state) {
		t.Fatal("marker")
	}
}

func TestIsDisabledFalseByDefault(t *testing.T) {
	if IsDisabled(t.TempDir()) {
		t.Fatal("no marker yet")
	}
}
