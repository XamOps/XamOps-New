// Package version holds the agent's build version.
//
// The value is injected at build time by the linker:
//
//	go build -ldflags "-X github.com/xamops/agent/internal/version.Version=v2.1.0"
//
// It is deliberately a single var rather than a const in each package. Before this
// existed the string "1.0.0" was hardcoded in four places (manager.go, pusher.go twice,
// and main.go's log line), so every published image reported 1.0.0 no matter which tag it
// was built from. That value lands in agent_data_cache.agent_version, which made it
// impossible to tell which clusters had picked up a new build.
package version

// Version is the agent version. Overridden at build time; "dev" means a local build.
var Version = "dev"
