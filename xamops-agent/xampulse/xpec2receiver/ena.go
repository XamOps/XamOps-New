package xpec2receiver

import (
	"encoding/binary"
	"strings"
)

// ethGStringLen is ETH_GSTRING_LEN: every ethtool statistic name is a fixed 32-byte, NUL-padded string.
const ethGStringLen = 32

// decodeStats pairs the i-th 32-byte name (ETHTOOL_GSTRINGS) with the i-th 64-bit value (ETHTOOL_GSTATS), in the
// machine's byte order, as the kernel writes them.
func decodeStats(names, values []byte, n int) map[string]uint64 {
	out := make(map[string]uint64, n)
	for i := 0; i < n && (i+1)*ethGStringLen <= len(names) && (i+1)*8 <= len(values); i++ {
		out[cString(names[i*ethGStringLen:(i+1)*ethGStringLen])] = binary.NativeEndian.Uint64(values[i*8:])
	}
	return out
}

// nicStats is one interface's ethtool statistics, already filtered to the configured drivers.
type nicStats struct {
	Device string
	Driver string
	Stats  map[string]uint64
}

// The ENA driver's allowance counters (ethtool -S), and the attribute value each becomes. AWS documents them in
// "Monitor network performance for ENA settings on your EC2 instance".
var enaAllowances = map[string]string{
	"bw_in_allowance_exceeded":     "bw_in",
	"bw_out_allowance_exceeded":    "bw_out",
	"pps_allowance_exceeded":       "pps",
	"conntrack_allowance_exceeded": "conntrack",
	"linklocal_allowance_exceeded": "linklocal",
}

const enaConntrackAvailable = "conntrack_allowance_available"

// statsSource lists interfaces and their ethtool statistics. The Linux implementation uses the SIOCETHTOOL ioctl
// (ETHTOOL_GDRVINFO, ETHTOOL_GSTRINGS, ETHTOOL_GSTATS), which the kernel allows without CAP_NET_ADMIN.
type statsSource func(drivers []string) ([]nicStats, error)

// cString trims a fixed-size, NUL-padded C string.
func cString(b []byte) string {
	if i := strings.IndexByte(string(b), 0); i >= 0 {
		return string(b[:i])
	}
	return string(b)
}

func wanted(driver string, drivers []string) bool {
	for _, d := range drivers {
		if d == driver {
			return true
		}
	}
	return false
}
