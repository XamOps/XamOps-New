package xampulse

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// testdata/otelcol-components.txt is the real output of `xampulse-otelcol components` from a build that includes
// xampulse_ec2 (ocb v0.164.0, built 2026-11-12).
func TestComponentListingFromARealBuild(t *testing.T) {
	out, err := os.ReadFile(filepath.Join("testdata", "otelcol-components.txt"))
	if err != nil {
		t.Fatal(err)
	}
	// Collector 0.164 lists contrib components by their new names (host_metrics, otlp_http, ...); the old names the
	// template uses (hostmetrics, otlphttp, ...) still work as deprecated aliases but are not listed.
	for _, name := range []string{EC2ReceiverType, "host_metrics", "docker_stats", "otlp_http"} {
		if !componentListed(out, name) {
			t.Errorf("%s should be listed", name)
		}
	}
	if componentListed(out, "xampulse") || componentListed(out, "kafka") {
		t.Error("only exact component names may match")
	}
	older := strings.ReplaceAll(string(out), "- name: "+EC2ReceiverType, "- name: something_else")
	if componentListed([]byte(older), EC2ReceiverType) {
		t.Error("a collector built without xampulse_ec2 must not be treated as having it")
	}
}

func TestMissingOrBrokenCollectorBinaryMeansNoEC2Receiver(t *testing.T) {
	if collectorHasComponent(context.Background(), filepath.Join(t.TempDir(), "no-such-collector"), EC2ReceiverType) {
		t.Fatal("a collector that cannot be run must not get a config it might refuse")
	}
	if collectorHasComponent(context.Background(), "/bin/false", EC2ReceiverType) {
		t.Fatal("a failing components command must read as not supported")
	}
}
