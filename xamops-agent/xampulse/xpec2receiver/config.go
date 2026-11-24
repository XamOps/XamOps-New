package xpec2receiver

import (
	"errors"
	"net/url"
	"strings"
	"time"

	"go.opentelemetry.io/collector/scraper/scraperhelper"
)

// Config for the xampulse_ec2 receiver.
type Config struct {
	scraperhelper.ControllerConfig `mapstructure:",squash"`

	// PSI reads /proc/pressure/{cpu,memory,io}.
	PSI bool `mapstructure:"psi"`
	// ENA reads the allowance counters from the ethtool statistics of interfaces whose driver is listed in
	// ENADrivers (default: the Elastic Network Adapter driver, "ena").
	ENA        bool     `mapstructure:"ena"`
	ENADrivers []string `mapstructure:"ena_drivers"`
	// IMDS reads Spot interruption, rebalance and scheduled maintenance notices from the instance metadata service.
	IMDS         bool          `mapstructure:"imds"`
	IMDSEndpoint string        `mapstructure:"imds_endpoint"`
	IMDSTimeout  time.Duration `mapstructure:"imds_timeout"`
	// Listen reads the listening sockets from /proc/net (for the exposure check: what a security group could expose).
	Listen bool `mapstructure:"listen"`
	// ProcRoot is where /proc is mounted (tests, or a collector running in a container with the host's /proc).
	ProcRoot string `mapstructure:"proc_root"`
}

func defaultConfig() *Config {
	cc := scraperhelper.NewDefaultControllerConfig()
	cc.CollectionInterval = 30 * time.Second
	return &Config{
		ControllerConfig: cc,
		PSI:              true,
		ENA:              true,
		ENADrivers:       []string{"ena"},
		IMDS:             true,
		Listen:           true,
		IMDSEndpoint:     "http://169.254.169.254",
		IMDSTimeout:      time.Second,
		ProcRoot:         "/proc",
	}
}

// Validate rejects configurations that could never work, so a bad rendered config fails at start-up, not silently.
func (c *Config) Validate() error {
	if c.IMDS {
		u, err := url.Parse(c.IMDSEndpoint)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			return errors.New("imds_endpoint must be an http(s) URL")
		}
		if c.IMDSTimeout <= 0 || c.IMDSTimeout > 10*time.Second {
			return errors.New("imds_timeout must be between 0 and 10s")
		}
	}
	if (c.PSI || c.Listen) && !strings.HasPrefix(c.ProcRoot, "/") {
		return errors.New("proc_root must be an absolute path")
	}
	if c.ENA && len(c.ENADrivers) == 0 {
		return errors.New("ena_drivers must list at least one driver when ena is enabled")
	}
	return nil
}
