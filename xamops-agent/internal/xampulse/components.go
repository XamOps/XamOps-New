package xampulse

import (
	"context"
	"os/exec"
	"regexp"
	"time"
)

// EC2ReceiverType is XamPulse's own receiver (PSI, ENA allowance counters, IMDS notices), built into
// xampulse-otelcol from xampulse/xpec2receiver.
const EC2ReceiverType = "xampulse_ec2"

// FilterProcessorType is the OpenTelemetry filter processor; per-core CPU and significant-process collection need it.
const FilterProcessorType = "filter"

// collectorHasComponent asks the collector binary which components it was built with ("<bin> components") and
// reports whether name is among them. A collector that does not know a receiver refuses the whole config, so the
// agent only renders xampulse_ec2 when the collector on this host has it: an agent updated ahead of its collector
// (for example when the collector download failed) keeps collecting everything else instead of crash-looping.
func collectorHasComponent(ctx context.Context, bin, name string) bool {
	return collectorComponents(ctx, bin)(name)
}

// collectorComponents runs "<bin> components" once and returns a lookup over its listing, so checking several
// components costs one run (and at most one timeout) instead of one per component. Any failure means "not listed".
func collectorComponents(ctx context.Context, bin string) func(name string) bool {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, bin, "components").Output()
	if err != nil {
		return func(string) bool { return false }
	}
	return func(name string) bool { return componentListed(out, name) }
}

// componentListed matches "- name: <name>" lines of the components listing (any section; names are unique).
func componentListed(out []byte, name string) bool {
	re := regexp.MustCompile(`(?m)^\s*-\s*name:\s*` + regexp.QuoteMeta(name) + `\s*$`)
	return re.Match(out)
}
