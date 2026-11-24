//go:build linux

package xpec2receiver

import (
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"runtime"
	"unsafe"

	"golang.org/x/sys/unix"
)

// From <linux/sockios.h> and <linux/ethtool.h>.
const (
	siocEthtool      = 0x8946
	ethtoolGDrvinfo  = 0x00000003
	ethtoolGStrings  = 0x0000001b
	ethtoolGStats    = 0x0000001d
	ethSSStats       = 1
	maxEthtoolStats  = 1 << 14 // guard against a nonsensical count from a buggy driver
	gstringsHdrBytes = 12      // struct ethtool_gstrings: cmd, string_set, len
	gstatsHdrBytes   = 8       // struct ethtool_stats: cmd, n_stats
)

// struct ethtool_drvinfo (196 bytes).
type ethtoolDrvinfo struct {
	Cmd         uint32
	Driver      [32]byte
	Version     [32]byte
	FwVersion   [32]byte
	BusInfo     [32]byte
	EromVersion [32]byte
	Reserved2   [12]byte
	NPrivFlags  uint32
	NStats      uint32
	TestinfoLen uint32
	EedumpLen   uint32
	RegdumpLen  uint32
}

// struct ifreq with ifr_data: 16-byte name, then a 24-byte union whose first member is the data pointer.
type ifreqData struct {
	Name [unix.IFNAMSIZ]byte
	Data unsafe.Pointer
	_    [24 - unsafe.Sizeof(uintptr(0))]byte
}

func ethtoolIoctl(fd int, name string, data unsafe.Pointer) error {
	var ifr ifreqData
	if len(name) >= unix.IFNAMSIZ {
		return fmt.Errorf("interface name %q too long", name)
	}
	copy(ifr.Name[:], name)
	ifr.Data = data
	_, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(fd), uintptr(siocEthtool), uintptr(unsafe.Pointer(&ifr)))
	runtime.KeepAlive(&ifr)
	if errno != 0 {
		return errno
	}
	return nil
}

// ethtoolStats reads one interface's driver name and named statistics.
func ethtoolStats(fd int, name string) (string, map[string]uint64, error) {
	info := ethtoolDrvinfo{Cmd: ethtoolGDrvinfo}
	if err := ethtoolIoctl(fd, name, unsafe.Pointer(&info)); err != nil {
		return "", nil, err
	}
	runtime.KeepAlive(&info)
	driver := cString(info.Driver[:])
	n := int(info.NStats)
	if n == 0 {
		return driver, map[string]uint64{}, nil
	}
	if n > maxEthtoolStats {
		return driver, nil, fmt.Errorf("%s reports %d statistics", name, n)
	}

	strs := make([]byte, gstringsHdrBytes+n*ethGStringLen)
	binary.NativeEndian.PutUint32(strs[0:], ethtoolGStrings)
	binary.NativeEndian.PutUint32(strs[4:], ethSSStats)
	binary.NativeEndian.PutUint32(strs[8:], uint32(n))
	if err := ethtoolIoctl(fd, name, unsafe.Pointer(&strs[0])); err != nil {
		return driver, nil, err
	}
	runtime.KeepAlive(strs)
	if got := int(binary.NativeEndian.Uint32(strs[8:])); got < n {
		n = got // the driver returned fewer names than it announced
	}

	vals := make([]byte, gstatsHdrBytes+n*8)
	binary.NativeEndian.PutUint32(vals[0:], ethtoolGStats)
	binary.NativeEndian.PutUint32(vals[4:], uint32(n))
	if err := ethtoolIoctl(fd, name, unsafe.Pointer(&vals[0])); err != nil {
		return driver, nil, err
	}
	runtime.KeepAlive(vals)
	if got := int(binary.NativeEndian.Uint32(vals[4:])); got < n {
		n = got
	}
	return driver, decodeStats(strs[gstringsHdrBytes:], vals[gstatsHdrBytes:], n), nil
}

func linuxStats(drivers []string) ([]nicStats, error) {
	ifs, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	defer unix.Close(fd)
	var out []nicStats
	var errs []error
	for _, in := range ifs {
		if in.Flags&net.FlagLoopback != 0 {
			continue
		}
		driver, stats, err := ethtoolStats(fd, in.Name)
		if err != nil {
			// Interfaces without ethtool support (some virtual ones) answer EOPNOTSUPP; that is not a failure.
			if !errors.Is(err, unix.EOPNOTSUPP) && !errors.Is(err, unix.ENODEV) && wanted(driver, drivers) {
				errs = append(errs, fmt.Errorf("%s: %w", in.Name, err))
			}
			continue
		}
		if wanted(driver, drivers) {
			out = append(out, nicStats{Device: in.Name, Driver: driver, Stats: stats})
		}
	}
	return out, errors.Join(errs...)
}

func defaultStatsSource() statsSource { return linuxStats }
