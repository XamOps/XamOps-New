// Package xampulse is the XamPulse agent: it proves the instance identity to XamOps without any stored key,
// keeps short-lived tokens, renders the OpenTelemetry Collector configuration, supervises the collector and
// reports its health. JSON names match the Java records in com.xammer.cloud.xampulse.dto.agent.
package xampulse

// StsRequest is a SigV4-signed sts:GetCallerIdentity request that the server replays to STS.
type StsRequest struct {
	URL     string              `json:"url"`
	Method  string              `json:"method"`
	Headers map[string][]string `json:"headers"`
	Body    string              `json:"body"`
}

type EnrollChallengeRequest struct {
	Region string `json:"region"`
}

type EnrollChallengeResponse struct {
	Nonce        string `json:"nonce"`
	ExpiresInSec int    `json:"expiresInSec"`
}

type EnrollRequest struct {
	Nonce        string     `json:"nonce"`
	Region       string     `json:"region"`
	StsRequest   StsRequest `json:"stsRequest"`
	AgentVersion string     `json:"agentVersion"`
	OS           string     `json:"os"`
	Arch         string     `json:"arch"`
	Hostname     string     `json:"hostname"`
}

type EnrollResponse struct {
	AccessToken  string      `json:"accessToken"`
	RefreshToken string      `json:"refreshToken"`
	ExpiresAt    string      `json:"expiresAt"`
	Config       AgentConfig `json:"config"`
}

// EnrollPending is the body of a 202 answer: the server has not seen the instance in scope yet.
type EnrollPending struct {
	RetryAfterSec int    `json:"retryAfterSec"`
	Reason        string `json:"reason"`
}

type RefreshRequest struct {
	RefreshToken string `json:"refreshToken"`
}

type TokenPair struct {
	AccessToken  string `json:"accessToken"`
	RefreshToken string `json:"refreshToken"`
	ExpiresAt    string `json:"expiresAt"`
}

type QueueStatus struct {
	Bytes   int64 `json:"bytes"`
	Dropped int64 `json:"dropped"`
}

type SourceStatus struct {
	Source string `json:"source"`
	State  string `json:"state"`
	Error  string `json:"error,omitempty"`
}

type HeartbeatRequest struct {
	AgentVersion     string         `json:"agentVersion"`
	CollectorVersion string         `json:"collectorVersion"`
	UptimeSec        int64          `json:"uptimeSec"`
	Queue            QueueStatus    `json:"queue"`
	SourcesStatus    []SourceStatus `json:"sourcesStatus"`
	Errors           []string       `json:"errors"`
}

// Commands the server may return on a heartbeat.
const (
	CommandNone         = "NONE"
	CommandReloadConfig = "RELOAD_CONFIG"
	CommandUpgrade      = "UPGRADE"
	CommandUninstall    = "UNINSTALL"
)

type HeartbeatResponse struct {
	Command       string `json:"command"`
	ConfigVersion int64  `json:"configVersion"`
	UpgradeTo     string `json:"upgradeTo,omitempty"`
}

type Source struct {
	Kind      string   `json:"kind"` // SYSTEM, DOCKER, CUSTOM
	Paths     []string `json:"paths"`
	Parser    string   `json:"parser,omitempty"`
	Multiline string   `json:"multiline,omitempty"` // regular expression that starts a new record
}

type RedactionRule struct {
	Rule        string `json:"rule"`
	Pattern     string `json:"pattern"`
	Replacement string `json:"replacement"`
}

type Limits struct {
	MaxBytesPerSec int64 `json:"maxBytesPerSec"`
	QueueMaxBytes  int64 `json:"queueMaxBytes"`
}

// AgentConfig is the desired collection setup. Level T0 = metrics only, T1 = metrics and logs, T2 = also per-process metrics.
type AgentConfig struct {
	ConfigVersion int64           `json:"configVersion"`
	Level         string          `json:"level"`
	Sources       []Source        `json:"sources"`
	Redaction     []RedactionRule `json:"redaction"`
	Limits        Limits          `json:"limits"`
	GatewayURL    string          `json:"gatewayUrl"`
}
