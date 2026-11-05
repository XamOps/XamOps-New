package xampulse

import (
	"bytes"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"text/template"

	assets "github.com/xamops/agent/xampulse"
)

// RenderOptions are the host-local values that are not part of the server configuration.
type RenderOptions struct {
	QueueDir        string // collector file_storage directory (persistent sending queue)
	AccessTokenFile string // file the bearertokenauth extension reads; the token itself is never rendered
	MemoryLimitMiB  int    // default 256
}

type fileLog struct {
	Name      string
	Paths     []string
	Multiline string
	Docker    bool
}

type renderModel struct {
	RenderOptions
	Metrics         bool
	ProcessMetrics  bool
	MetricsInterval string
	DockerEnabled   bool
	FileLogs        []fileLog
	Journald        bool
	Redaction       []RedactionRule
	GatewayURL      string
	QueueSize       int
	SpikeLimitMiB   int
	MetricReceivers []string
	LogReceivers    []string
}

// Default log locations used when the SYSTEM source lists no paths.
var defaultSystemPaths = []string{
	"/var/log/syslog", "/var/log/messages", "/var/log/auth.log", "/var/log/secure", "/var/log/kern.log",
}

// RenderCollectorConfig turns the desired AgentConfig into OpenTelemetry Collector YAML. It validates paths,
// the gateway URL and every redaction pattern so a bad server config cannot produce a collector that fails
// at start up or leaks the data it should mask.
func RenderCollectorConfig(cfg AgentConfig, opts RenderOptions) ([]byte, error) {
	if !strings.HasPrefix(cfg.GatewayURL, "https://") && !strings.HasPrefix(cfg.GatewayURL, "http://localhost") {
		return nil, errors.New("gatewayUrl must be https")
	}
	if strings.ContainsAny(cfg.GatewayURL, " \n\r\t\"'") {
		return nil, errors.New("gatewayUrl contains illegal characters")
	}
	if opts.QueueDir == "" || opts.AccessTokenFile == "" {
		return nil, errors.New("queue dir and access token file are required")
	}
	level := strings.ToUpper(cfg.Level)
	if level == "" {
		level = "T1"
	}
	if level != "T0" && level != "T1" && level != "T2" {
		return nil, fmt.Errorf("unknown collection level %q", cfg.Level)
	}
	for _, r := range cfg.Redaction {
		if _, err := regexp.Compile(r.Pattern); err != nil {
			return nil, fmt.Errorf("redaction rule %q: %w", r.Rule, err)
		}
	}

	m := renderModel{
		RenderOptions:   opts,
		Metrics:         true,
		ProcessMetrics:  level == "T2",
		MetricsInterval: "30s",
		Redaction:       cfg.Redaction,
		GatewayURL:      strings.TrimRight(cfg.GatewayURL, "/"),
	}
	if level == "T0" {
		m.MetricsInterval = "60s"
	}
	if m.MemoryLimitMiB <= 0 {
		m.MemoryLimitMiB = 256
	}
	m.SpikeLimitMiB = m.MemoryLimitMiB / 5
	m.QueueSize = queueItems(cfg.Limits.QueueMaxBytes)

	if level != "T0" {
		for i, s := range cfg.Sources {
			switch strings.ToUpper(s.Kind) {
			case "SYSTEM":
				var files []string
				for _, p := range pathsOrDefault(s.Paths, defaultSystemPaths) {
					if p == "journald" {
						m.Journald = true
						continue
					}
					if err := validPath(p); err != nil {
						return nil, err
					}
					files = append(files, p)
				}
				if len(files) > 0 {
					m.FileLogs = append(m.FileLogs, fileLog{Name: fmt.Sprintf("system%d", i), Paths: files, Multiline: s.Multiline})
				}
			case "DOCKER":
				m.DockerEnabled = true
				m.FileLogs = append(m.FileLogs, fileLog{Name: fmt.Sprintf("docker%d", i), Paths: []string{"/var/lib/docker/containers/*/*-json.log"}, Docker: true})
			case "CUSTOM":
				for _, p := range s.Paths {
					if err := validPath(p); err != nil {
						return nil, err
					}
				}
				if len(s.Paths) > 0 {
					if s.Multiline != "" {
						if _, err := regexp.Compile(s.Multiline); err != nil {
							return nil, fmt.Errorf("custom source multiline: %w", err)
						}
					}
					m.FileLogs = append(m.FileLogs, fileLog{Name: fmt.Sprintf("custom%d", i), Paths: s.Paths, Multiline: s.Multiline})
				}
			default:
				return nil, fmt.Errorf("unknown source kind %q", s.Kind)
			}
		}
	}

	m.MetricReceivers = []string{"hostmetrics"}
	if m.DockerEnabled {
		m.MetricReceivers = append(m.MetricReceivers, "docker_stats")
	}
	for _, f := range m.FileLogs {
		m.LogReceivers = append(m.LogReceivers, "filelog/"+f.Name)
	}
	if m.Journald {
		m.LogReceivers = append(m.LogReceivers, "journald")
	}

	funcs := template.FuncMap{
		"yq":          yamlQuote,
		"ottlReplace": ottlReplace,
		"join":        strings.Join,
	}
	raw, err := assets.Templates.ReadFile("templates/linux.yaml.tmpl")
	if err != nil {
		return nil, err
	}
	tpl, err := template.New("linux").Funcs(funcs).Option("missingkey=error").Parse(string(raw))
	if err != nil {
		return nil, err
	}
	var buf bytes.Buffer
	if err := tpl.Execute(&buf, m); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func pathsOrDefault(p, def []string) []string {
	if len(p) == 0 {
		return append([]string{"journald"}, def...)
	}
	return p
}

// validPath accepts absolute paths and globs and rejects anything that could break out of the YAML scalar or
// point at a place that is never a log (credentials, keys, the agent's own state).
func validPath(p string) error {
	if !strings.HasPrefix(p, "/") {
		return fmt.Errorf("log path %q must be absolute", p)
	}
	if strings.ContainsAny(p, "\n\r\x00") || strings.Contains(p, "..") {
		return fmt.Errorf("log path %q is not allowed", p)
	}
	for _, banned := range []string{"/var/lib/xampulse", "/.ssh/", "/etc/shadow", "/etc/ssl/private", "/proc/", "/sys/"} {
		if strings.Contains(p, banned) {
			return fmt.Errorf("log path %q is not allowed", p)
		}
	}
	return nil
}

// queueItems converts a byte budget into a queue length. A batch is at most about 64 KiB compressed.
func queueItems(maxBytes int64) int {
	const perBatch = 64 * 1024
	if maxBytes <= 0 {
		return 1000
	}
	n := int(maxBytes / perBatch)
	if n < 100 {
		return 100
	}
	if n > 100000 {
		return 100000
	}
	return n
}

// yamlQuote renders s as a YAML single-quoted scalar.
func yamlQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", "''") + "'"
}

// ottlReplace renders one OTTL statement that masks matches of pattern in the log body. Quotes and backslashes
// are escaped for the OTTL string literal; the caller then quotes the whole statement for YAML.
func ottlReplace(pattern, replacement string) string {
	esc := func(s string) string {
		s = strings.ReplaceAll(s, `\`, `\\`)
		return strings.ReplaceAll(s, `"`, `\"`)
	}
	return fmt.Sprintf(`replace_pattern(body.string, "%s", "%s")`, esc(pattern), esc(replacement))
}
