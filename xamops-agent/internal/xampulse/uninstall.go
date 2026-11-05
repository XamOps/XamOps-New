package xampulse

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
)

// DisabledMarker is the file that tells the install document not to bring the agent back while the server
// cannot be asked. Its name must match xamops-agent/scripts/xampulse_install_linux.sh.
const DisabledMarker = "disabled"

// Uninstaller removes the agent's local footprint. Paths and the system calls are fields so tests can run it
// against a temp directory without touching systemd.
type Uninstaller struct {
	StateDir   string
	InstallDir string // /opt/xampulse
	UnitPath   string // /etc/systemd/system/xampulse-agent.service
	// Systemctl runs systemctl with args; nil uses the real binary and ignores failures.
	Systemctl func(args ...string) error
}

func (u Uninstaller) systemctl(args ...string) {
	if u.Systemctl != nil {
		_ = u.Systemctl(args...)
		return
	}
	_ = exec.Command("systemctl", args...).Run()
}

// ExitUninstalled is the process exit status after an uninstall. The unit lists it in RestartPreventExitStatus so
// systemd does not restart the agent.
const ExitUninstalled = 78

// Uninstall removes the tokens, staged binaries and, when permitted, the unit and the install directory, and
// writes the disabled marker. The service runs as an unprivileged user on a read-only file system, so the unit
// and /opt removal normally fail with a permission error; that is tolerated because the unit has
// ConditionPathExists=!<marker> and the next install-document run (desired state SKIP) removes them as root.
// The running process is expected to exit with ExitUninstalled after this returns.
func (u Uninstaller) Uninstall() error {
	u.systemctl("disable", "xampulse-agent.service")
	if u.UnitPath != "" {
		if err := os.Remove(u.UnitPath); err != nil && !os.IsNotExist(err) && !os.IsPermission(err) && !isReadOnlyFS(err) {
			return err
		}
	}
	u.systemctl("daemon-reload")
	if u.InstallDir != "" {
		if err := os.RemoveAll(u.InstallDir); err != nil && !os.IsPermission(err) && !isReadOnlyFS(err) {
			return err
		}
	}
	for _, f := range []string{TokenFileName, AccessTokenFileName, "installed.sha256"} {
		if err := os.Remove(filepath.Join(u.StateDir, f)); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	_ = os.RemoveAll(filepath.Join(u.StateDir, "bin"))
	if err := os.MkdirAll(u.StateDir, 0o750); err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(u.StateDir, DisabledMarker), nil, 0o640)
}

func isReadOnlyFS(err error) bool {
	return errors.Is(err, syscall.EROFS)
}

// IsDisabled reports whether the local marker exists.
func IsDisabled(stateDir string) bool {
	_, err := os.Stat(filepath.Join(stateDir, DisabledMarker))
	return err == nil
}
