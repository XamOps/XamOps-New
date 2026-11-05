package xampulse

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

var testOpts = RenderOptions{QueueDir: "/var/lib/xampulse/queue", AccessTokenFile: "/var/lib/xampulse/access-token"}

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
	if !strings.Contains(string(t1), "filelog/system0") || !strings.Contains(string(t1), "journald") || strings.Contains(string(t1), "process:") {
		t.Error("T1 has logs but no per-process metrics")
	}
	t2, _ := RenderCollectorConfig(fullConfig(t, "T2"), testOpts)
	if !strings.Contains(string(t2), "process:") {
		t.Error("T2 adds per-process metrics")
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
