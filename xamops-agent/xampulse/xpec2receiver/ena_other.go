//go:build !linux

package xpec2receiver

// ENA statistics only exist on Linux EC2 instances; elsewhere the source reports nothing.
func defaultStatsSource() statsSource {
	return func([]string) ([]nicStats, error) { return nil, nil }
}
