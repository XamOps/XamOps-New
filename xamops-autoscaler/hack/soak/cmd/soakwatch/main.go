// Command soakwatch records a CloudK8s soak window and checks its invariants.
//
// Usage:
//
//	soakwatch watch --context <kube-context> --out <dir> [--duration 2h]
//	                [--agent-namespace xamops-system] [--agent-selector app=xamops-automation]
//	                [--lease xamops-automation-leader] [--append]
//	soakwatch check --dir <dir> [--max-parallel-drains 1] [--leader-tolerance 2s] [--report <file>]
//
// watch requires an explicit --context and refuses to run without it (it never falls
// back to the current context). It only runs read-only kubectl verbs (get, logs,
// config), records nodes, PDBs, Deployments, StatefulSets, ReplicaSets, pods, events
// and the leader Lease as `--watch --output-watch-events -o json` streams plus the
// agent logs, one timestamped JSONL file per stream, restarting any watch the
// apiserver closes, until --duration elapses or SIGINT/SIGTERM. It writes
// watch-meta.json on exit.
//
// check evaluates I1 (no PDB violated), I2 (HA floor), I6 (concurrent drains) and
// I14 (only the lease holder acts) on every recorded event, prints one verdict line
// per invariant plus breaches, and writes invariants-report.json (default
// <dir>/invariants-report.json). Exit status: 0 all PASS, 1 any FAIL, 3 no FAIL but
// some NOT-EVALUATED, 2 usage or input error.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/xamops/automation/hack/soak/invariants"
	"github.com/xamops/automation/hack/soak/watch"
)

const usage = `usage:
  soakwatch watch --context <kube-context> --out <dir> [--duration 2h] [--agent-namespace xamops-system]
                  [--agent-selector app=xamops-automation] [--lease xamops-automation-leader] [--append]
  soakwatch check --dir <dir> [--max-parallel-drains 1] [--leader-tolerance 2s] [--report <file>]
`

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	os.Exit(run(ctx, os.Args[1:], os.Stdout, os.Stderr, watch.ExecRunner{Stderr: os.Stderr}))
}

func run(ctx context.Context, args []string, stdout, stderr io.Writer, runner watch.Runner) int {
	if len(args) == 0 {
		fmt.Fprint(stderr, usage)
		return 2
	}
	switch args[0] {
	case "watch":
		return runWatch(ctx, args[1:], stderr, runner)
	case "check":
		return runCheck(args[1:], stdout, stderr)
	case "-h", "--help", "help":
		fmt.Fprint(stdout, usage)
		return 0
	}
	fmt.Fprintf(stderr, "unknown command %q\n%s", args[0], usage)
	return 2
}

func runWatch(ctx context.Context, args []string, stderr io.Writer, runner watch.Runner) int {
	fs := flag.NewFlagSet("watch", flag.ContinueOnError)
	fs.SetOutput(stderr)
	kctx := fs.String("context", "", "kube context to watch (required; never the current context)")
	out := fs.String("out", "", "output run directory (required)")
	dur := fs.Duration("duration", 2*time.Hour, "soak window length")
	ns := fs.String("agent-namespace", "xamops-system", "agent namespace")
	sel := fs.String("agent-selector", "app=xamops-automation", "agent pod label selector")
	lease := fs.String("lease", "xamops-automation-leader", "leader-election Lease name")
	appendOut := fs.Bool("append", false, "allow writing into a non-empty --out directory")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *kctx == "" {
		fmt.Fprintf(stderr, "soakwatch watch: --context is required (refusing to use the current kube context)\n%s", usage)
		return 2
	}
	if *out == "" {
		fmt.Fprintf(stderr, "soakwatch watch: --out is required\n%s", usage)
		return 2
	}
	if entries, err := os.ReadDir(*out); err == nil && len(entries) > 0 && !*appendOut {
		fmt.Fprintf(stderr, "soakwatch watch: %s is not empty (use --append to add to it)\n", *out)
		return 2
	}
	w := watch.New(watch.Config{
		Context: *kctx, OutDir: *out, Duration: *dur, AgentNamespace: *ns, AgentSelector: *sel, LeaseName: *lease,
		Logf: func(f string, a ...interface{}) { fmt.Fprintf(stderr, f+"\n", a...) },
	}, runner, nil)
	if err := w.Run(ctx); err != nil {
		fmt.Fprintf(stderr, "soakwatch watch: %v\n", err)
		return 1
	}
	return 0
}

func runCheck(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("check", flag.ContinueOnError)
	fs.SetOutput(stderr)
	dir := fs.String("dir", "", "run directory written by soakwatch watch (required)")
	maxDrains := fs.Int("max-parallel-drains", 1, "drain budget for I6")
	tol := fs.Duration("leader-tolerance", 2*time.Second, "lease/log clock tolerance for I14")
	reportPath := fs.String("report", "", "report file (default <dir>/invariants-report.json)")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *dir == "" {
		fmt.Fprintf(stderr, "soakwatch check: --dir is required\n%s", usage)
		return 2
	}
	tl, err := invariants.Load(*dir)
	if err != nil {
		fmt.Fprintf(stderr, "soakwatch check: %v\n", err)
		return 2
	}
	opts := invariants.DefaultOptions()
	opts.MaxParallelDrains, opts.LeaderTolerance = *maxDrains, *tol
	rep := invariants.Check(tl, opts)
	fmt.Fprint(stdout, rep.Text())

	if *reportPath == "" {
		*reportPath = filepath.Join(*dir, "invariants-report.json")
	}
	b, err := json.MarshalIndent(rep, "", "  ")
	if err == nil {
		err = os.WriteFile(*reportPath, append(b, '\n'), 0o644)
	}
	if err != nil {
		fmt.Fprintf(stderr, "soakwatch check: write report: %v\n", err)
		return 2
	}
	code := 0
	for _, r := range rep.Invariants {
		switch r.Verdict {
		case invariants.Fail:
			return 1
		case invariants.NotEvaluated:
			code = 3
		}
	}
	return code
}
