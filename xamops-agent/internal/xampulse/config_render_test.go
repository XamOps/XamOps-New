package xampulse

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

var testOpts = RenderOptions{QueueDir: "/var/lib/xampulse/queue", AccessTokenFile: "/var/lib/xampulse/access-token", Conntrack: true, EC2Receiver: true, FilterProcessor: true}

func TestPerCoreAndProcessesOnlyWhenTheCollectorHasTheFilterProcessor(t *testing.T) {
	old := testOpts
	old.FilterProcessor = false
	out, err := RenderCollectorConfig(fullConfig(t, "T1"), old)
	if err != nil {
		t.Fatal(err)
	}
	for _, s := range []string{"filter/", "hostmetrics/percpu", "transform/percpu", "process:"} {
		if strings.Contains(string(out), s) {
			t.Errorf("an older collector without the filter processor must not get %q", s)
		}
	}
	t1, err := RenderCollectorConfig(fullConfig(t, "T1"), testOpts)
	if err != nil {
		t.Fatal(err)
	}
	for _, s := range []string{"filter/processes:", "hostmetrics/percpu:", "transform/percpu:", "xp.cpu.core.utilization", "metrics/percpu:"} {
		if !strings.Contains(string(t1), s) {
			t.Errorf("T1 with the filter processor should render %q", s)
		}
	}
	t0, err := RenderCollectorConfig(fullConfig(t, "T0"), testOpts)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(t0), "percpu") || strings.Contains(string(t0), "process:") {
		t.Error("T0 stays minimal: no per-core CPU and no processes")
	}
}

func TestEC2ReceiverOnlyWhenTheCollectorHasIt(t *testing.T) {
	old := testOpts
	old.EC2Receiver = false
	out, err := RenderCollectorConfig(fullConfig(t, "T1"), old)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(out), EC2ReceiverType) {
		t.Fatal("an older collector without xampulse_ec2 would refuse the whole config")
	}
	with, err := RenderCollectorConfig(fullConfig(t, "T0"), testOpts)
	if err != nil {
		t.Fatal(err)
	}
	s := string(with)
	if !strings.Contains(s, "  xampulse_ec2:\n    collection_interval: 60s") || !strings.Contains(s, "receivers: [hostmetrics, xampulse_ec2]") {
		t.Fatalf("T0 must scrape xampulse_ec2 at its own interval and wire it into the metrics pipeline:\n%s", s)
	}
}

func TestConntrackMetricsOnlyWhenTheHostHasConntrack(t *testing.T) {
	without := testOpts
	without.Conntrack = false
	out, err := RenderCollectorConfig(fullConfig(t, "T1"), without)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(out), "conntrack") {
		t.Fatal("conntrack metrics must stay off when nf_conntrack is absent: the scraper would fail every interval")
	}
	with, err := RenderCollectorConfig(fullConfig(t, "T1"), testOpts)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(with), "system.network.conntrack.count:") {
		t.Fatal("conntrack metrics must be enabled when the host exposes the table")
	}
}

func TestLogsAreShapedForSearchSeverityAndContainers(t *testing.T) {
	out, err := RenderCollectorConfig(fullConfig(t, "T1"), testOpts)
	if err != nil {
		t.Fatal(err)
	}
	s := string(out)
	for _, want := range []string{
		"parse_from: body.PRIORITY",        // journald severity
		`to: resource["service.name"]`,     // journald SYSLOG_IDENTIFIER becomes the service
		"from: body.MESSAGE",               // the message, not the whole journal entry, is the body
		`to: resource["container.id"]`,     // docker json logs carry their container id
		"container.restarts:",              // restart counts for the Containers tab
		"container.cpu.throttling_data.throttled_periods:",
		"devices: ['^loop[0-9]+$', '^ram[0-9]+$']",
		"interfaces: ['^veth']",
	} {
		if !strings.Contains(s, want) {
			t.Errorf("rendered T1 config lacks %q", want)
		}
	}
}

func loadRedactionCases(t *testing.T) []struct {
	Rule        string `json:"rule"`
	Pattern     string `json:"pattern"`
	Replacement string `json:"replacement"`
	Positives   []struct {
		Input  string `json:"input"`
		Output string `json:"output"`
	} `json:"positives"`
	Negatives []string `json:"negatives"`
} {
	b, err := os.ReadFile("testdata/redaction-cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Rule        string `json:"rule"`
		Pattern     string `json:"pattern"`
		Replacement string `json:"replacement"`
		Positives   []struct {
			Input  string `json:"input"`
			Output string `json:"output"`
		} `json:"positives"`
		Negatives []string `json:"negatives"`
	}
	if err := json.Unmarshal(b, &cases); err != nil {
		t.Fatal(err)
	}
	return cases
}

func defaultRedaction(t *testing.T) []RedactionRule {
	var out []RedactionRule
	for _, c := range loadRedactionCases(t) {
		out = append(out, RedactionRule{Rule: c.Rule, Pattern: c.Pattern, Replacement: c.Replacement})
	}
	return out
}

func fullConfig(t *testing.T, level string) AgentConfig {
	return AgentConfig{
		ConfigVersion: 7, Level: level, GatewayURL: "https://live.xamops.com/xampulse-ingest",
		Sources: []Source{
			{Kind: "SYSTEM"},
			{Kind: "DOCKER"},
			{Kind: "CUSTOM", Paths: []string{"/var/log/app/*.log"}, Multiline: `^\d{4}-\d{2}-\d{2}`},
		},
		Redaction: defaultRedaction(t),
		Limits:    Limits{MaxBytesPerSec: 1 << 20, QueueMaxBytes: 256 << 20},
	}
}

func TestRedactionCasesActuallyMask(t *testing.T) {
	for _, c := range loadRedactionCases(t) {
		re, err := regexp.Compile(c.Pattern)
		if err != nil {
			t.Fatalf("%s: %v", c.Rule, err)
		}
		for _, p := range c.Positives {
			if got := re.ReplaceAllString(p.Input, c.Replacement); got != p.Output {
				t.Errorf("%s: %q -> %q, want %q", c.Rule, p.Input, got, p.Output)
			}
		}
		for _, n := range c.Negatives {
			if got := re.ReplaceAllString(n, c.Replacement); got != n {
				t.Errorf("%s: negative %q was changed to %q", c.Rule, n, got)
			}
		}
	}
}

func TestRenderGolden(t *testing.T) {
	for _, level := range []string{"T0", "T1", "T2"} {
		got, err := RenderCollectorConfig(fullConfig(t, level), testOpts)
		if err != nil {
			t.Fatalf("%s: %v", level, err)
		}
		golden := filepath.Join("testdata", "linux-"+strings.ToLower(level)+".golden.yaml")
		if os.Getenv("UPDATE_GOLDEN") == "1" {
			if err := os.WriteFile(golden, got, 0o644); err != nil {
				t.Fatal(err)
			}
		}
		want, err := os.ReadFile(golden)
		if err != nil {
			t.Fatalf("missing golden %s (run with UPDATE_GOLDEN=1): %v", golden, err)
		}
		if string(want) != string(got) {
			t.Errorf("%s: rendered config differs from %s (run with UPDATE_GOLDEN=1 and review the diff)", level, golden)
		}
	}
}

func TestRenderContainsNoSecretOnlyFileReference(t *testing.T) {
	out, err := RenderCollectorConfig(fullConfig(t, "T1"), testOpts)
	if err != nil {
		t.Fatal(err)
	}
	s := string(out)
	if regexp.MustCompile(`(AKIA|ASIA)[A-Z0-9]{16}`).MatchString(s) {
		t.Error("rendered YAML contains an AWS access key")
	}
	for _, bad := range []string{"Bearer ey", "xak_", "refreshToken", "password:"} {
		if strings.Contains(s, bad) {
			t.Errorf("rendered YAML contains %q", bad)
		}
	}
	if !strings.Contains(s, "filename: '/var/lib/xampulse/access-token'") {
		t.Error("token must be referenced by file path")
	}
}

func TestRenderLevels(t *testing.T) {
	t0, _ := RenderCollectorConfig(fullConfig(t, "T0"), testOpts)
	if strings.Contains(string(t0), "filelog/") || strings.Contains(string(t0), "logs:") {
		t.Error("T0 is metrics only")
	}
	t1, _ := RenderCollectorConfig(fullConfig(t, "T1"), testOpts)
	// T1 (2026-11-10 decision): logs plus the significant processes only (filtered at ~1%).
	if !strings.Contains(string(t1), "filelog/system0") || !strings.Contains(string(t1), "journald") ||
		!strings.Contains(string(t1), "process:") || !strings.Contains(string(t1), "filter/processes") {
		t.Error("T1 has logs and the significant processes")
	}
	t2, _ := RenderCollectorConfig(fullConfig(t, "T2"), testOpts)
	if !strings.Contains(string(t2), "process:") || strings.Contains(string(t2), "filter/processes") {
		t.Error("T2 keeps every process, unfiltered")
	}
	if strings.Contains(string(t2), "cmdline") {
		t.Error("process command lines must never be collected")
	}
}

func TestRenderRejectsBadInput(t *testing.T) {
	cases := map[string]func(c *AgentConfig){
		"http gateway":      func(c *AgentConfig) { c.GatewayURL = "http://evil.example.com" },
		"gateway injection": func(c *AgentConfig) { c.GatewayURL = "https://a.example.com/x'\nfoo: bar" },
		"bad level":         func(c *AgentConfig) { c.Level = "T9" },
		"bad regex":         func(c *AgentConfig) { c.Redaction = []RedactionRule{{Rule: "x", Pattern: "([", Replacement: "r"}} },
		"relative path":     func(c *AgentConfig) { c.Sources = []Source{{Kind: "CUSTOM", Paths: []string{"var/log/x"}}} },
		"traversal": func(c *AgentConfig) {
			c.Sources = []Source{{Kind: "CUSTOM", Paths: []string{"/var/log/../../etc/shadow"}}}
		},
		"ssh dir": func(c *AgentConfig) { c.Sources = []Source{{Kind: "CUSTOM", Paths: []string{"/home/u/.ssh/id_rsa"}}} },
		"own state": func(c *AgentConfig) {
			c.Sources = []Source{{Kind: "CUSTOM", Paths: []string{"/var/lib/xampulse/agent-token.json"}}}
		},
		"unknown kind": func(c *AgentConfig) { c.Sources = []Source{{Kind: "KAFKA"}} },
		"bad multiline": func(c *AgentConfig) {
			c.Sources = []Source{{Kind: "CUSTOM", Paths: []string{"/var/log/a"}, Multiline: "(("}}
		},
	}
	for name, mutate := range cases {
		cfg := fullConfig(t, "T1")
		mutate(&cfg)
		if _, err := RenderCollectorConfig(cfg, testOpts); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
	if _, err := RenderCollectorConfig(fullConfig(t, "T1"), RenderOptions{}); err == nil {
		t.Error("missing options must fail")
	}
}

func TestOttlReplaceEscapesQuotesAndBackslashes(t *testing.T) {
	got := ottlReplace(`a\d"b`, `x"y`)
	want := `replace_pattern(body.string, "a\\d\"b", "x\"y")`
	if got != want {
		t.Fatalf("got %s want %s", got, want)
	}
	if yamlQuote("it's") != "'it''s'" {
		t.Fatal("yaml quoting")
	}
}
