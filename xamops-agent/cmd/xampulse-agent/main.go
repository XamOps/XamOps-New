// Command xampulse-agent is the XamPulse host agent. It does not share code with the legacy cmd/agent.
//
//	xampulse-agent run       --server URL [--state-dir DIR] [--otelcol PATH]
//	xampulse-agent enroll    --server URL [--state-dir DIR]
//	xampulse-agent uninstall [--state-dir DIR]
//	xampulse-agent version
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/xamops/agent/internal/xampulse"
)

// version is overridden at build time: -ldflags "-X main.version=1.0.0".
var version = "dev"

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	cmd := os.Args[1]
	fs := flag.NewFlagSet(cmd, flag.ExitOnError)
	server := fs.String("server", "", "XamOps base URL, for example https://live.xamops.com")
	stateDir := fs.String("state-dir", "/var/lib/xampulse", "directory for tokens, queue and the collector config")
	otelcol := fs.String("otelcol", "/opt/xampulse/xampulse-otelcol", "path of the xampulse-otelcol binary")
	release := fs.String("release-base-url", "", "release URL used for UPGRADE commands (optional)")
	_ = fs.Parse(os.Args[2:])

	agent := &xampulse.Agent{
		Version:        version,
		ServerURL:      *server,
		StateDir:       *stateDir,
		CollectorPath:  *otelcol,
		ReleaseBaseURL: *release,
		IMDS:           &xampulse.IMDS{},
		Uninstaller: xampulse.Uninstaller{
			StateDir:   *stateDir,
			InstallDir: "/opt/xampulse",
			UnitPath:   "/etc/systemd/system/xampulse-agent.service",
		},
	}

	switch cmd {
	case "version":
		fmt.Println(version)
	case "enroll":
		requireServer(*server)
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		if err := agent.EnsureEnrolled(ctx); err != nil {
			log.Fatalf("enrol failed: %v", err)
		}
		log.Println("enrolled")
	case "run":
		requireServer(*server)
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		log.Printf("xampulse-agent %s starting", version)
		err := agent.Run(ctx)
		switch {
		case err == nil, errors.Is(err, context.Canceled):
			return
		case errors.Is(err, xampulse.ErrUninstalled):
			os.Exit(xampulse.ExitUninstalled)
		default:
			log.Fatalf("xampulse-agent: %v", err)
		}
	case "uninstall":
		if err := agent.Uninstaller.Uninstall(); err != nil {
			log.Fatalf("uninstall failed: %v", err)
		}
		log.Println("uninstalled; collection stays off until XamOps answers INSTALL")
	default:
		usage()
		os.Exit(2)
	}
}

func requireServer(s string) {
	if s == "" {
		log.Fatal("--server is required")
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: xampulse-agent run|enroll|uninstall|version [flags]")
}
