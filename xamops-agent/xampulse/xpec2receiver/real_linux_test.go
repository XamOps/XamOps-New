//go:build linux

package xpec2receiver

import (
	"net"
	"os"
	"testing"
)

// TestRealKernel exercises the SIOCETHTOOL structs and /proc/pressure against a real Linux kernel. It is off by
// default; run it on a Linux host or container with XP_REAL_ETHTOOL set to a driver name present there, for example
// "veth" in a Docker container or "ena" on an EC2 instance:
//
//	GOOS=linux go test -c -o xpec2.test && XP_REAL_ETHTOOL=veth ./xpec2.test -test.run TestRealKernel -test.v
func TestRealKernel(t *testing.T) {
	driver := os.Getenv("XP_REAL_ETHTOOL")
	if driver == "" {
		t.Skip("set XP_REAL_ETHTOOL=<driver> to run against this kernel")
	}
	nics, err := linuxStats([]string{driver})
	if err != nil {
		t.Fatalf("ethtool: %v", err)
	}
	if len(nics) == 0 {
		t.Fatalf("no interface with driver %q", driver)
	}
	for _, n := range nics {
		if len(n.Stats) == 0 {
			t.Fatalf("%s (%s): ethtool returned no statistics", n.Device, n.Driver)
		}
		t.Logf("%s driver=%s stats=%d", n.Device, n.Driver, len(n.Stats))
		for name, v := range n.Stats {
			if name == "" {
				t.Fatalf("%s: empty statistic name (string table misread)", n.Device)
			}
			t.Logf("  %s=%d", name, v)
		}
	}
	psi, err := readPSI("/proc")
	t.Logf("psi: %+v err=%v", psi, err)
	if err == nil && len(psi) == 0 {
		t.Fatal("PSI present but nothing parsed")
	}
	if bt, err := readBootTime("/proc"); err != nil || bt.IsZero() {
		t.Fatalf("boot time: %v %v", bt, err)
	}
}

// TestRealKernelListening opens real listeners and finds them in this kernel's /proc/net tables, with the right
// address class. Runs on any Linux (no privileges needed).
func TestRealKernelListening(t *testing.T) {
	lo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer lo.Close()
	all, err := net.Listen("tcp", ":0")
	if err != nil {
		t.Fatal(err)
	}
	defer all.Close()
	socks, err := readListening("/proc")
	if err != nil {
		t.Fatal(err)
	}
	want := map[int]string{lo.Addr().(*net.TCPAddr).Port: "loopback", all.Addr().(*net.TCPAddr).Port: "all"}
	for _, s := range socks {
		if bind, ok := want[s.Port]; ok && s.Protocol == "tcp" {
			if s.Bind != bind {
				t.Fatalf("port %d: bind %s, want %s (%+v)", s.Port, s.Bind, bind, s)
			}
			delete(want, s.Port)
		}
	}
	if len(want) != 0 {
		t.Fatalf("not found in /proc/net: %v; read %+v", want, socks)
	}
}
