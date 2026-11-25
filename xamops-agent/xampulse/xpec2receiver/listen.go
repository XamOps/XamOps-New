package xpec2receiver

import (
	"bufio"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// MetricListening is one listening socket: gauge 1 with protocol, port, bind (all / loopback / address) and address.
// Read from /proc/net/{tcp,tcp6,udp,udp6}, which any user can read; no process names (those need root).
const MetricListening = "xp.net.listening"

// maxListening bounds the series a host can produce, whatever runs on it.
const maxListening = 200

// ephemeralUDP: unconnected UDP sockets at or above this port are almost always clients waiting for a reply (DNS
// lookups), not services; they are left out so the list shows what a firewall rule could expose.
const ephemeralUDP = 32768

type listenSocket struct {
	Protocol string // tcp or udp
	Address  string // as text: 0.0.0.0, ::, 127.0.0.1, 10.0.1.5
	Port     int
	Bind     string // all, loopback or address
}

// readListening returns the host's listening TCP sockets and its bound UDP sockets below the ephemeral range,
// deduplicated and sorted. A missing file (no IPv6) is skipped; it is an error only when none can be read.
func readListening(procRoot string) ([]listenSocket, error) {
	seen := map[string]listenSocket{}
	read := 0
	var lastErr error
	for _, f := range []struct{ file, proto, state string }{
		{"tcp", "tcp", "0A"}, {"tcp6", "tcp", "0A"}, {"udp", "udp", "07"}, {"udp6", "udp", "07"},
	} {
		fh, err := os.Open(filepath.Join(procRoot, "net", f.file))
		if err != nil {
			lastErr = err
			continue
		}
		socks, err := parseNetSockets(fh, f.proto, f.state)
		fh.Close()
		if err != nil {
			lastErr = err
			continue
		}
		read++
		for _, s := range socks {
			if s.Protocol == "udp" && s.Port >= ephemeralUDP {
				continue
			}
			seen[fmt.Sprintf("%s|%s|%d", s.Protocol, s.Address, s.Port)] = s
		}
	}
	if read == 0 {
		if lastErr == nil {
			lastErr = os.ErrNotExist
		}
		return nil, lastErr
	}
	out := make([]listenSocket, 0, len(seen))
	for _, s := range seen {
		out = append(out, s)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Port != out[j].Port {
			return out[i].Port < out[j].Port
		}
		if out[i].Protocol != out[j].Protocol {
			return out[i].Protocol < out[j].Protocol
		}
		return out[i].Address < out[j].Address
	})
	if len(out) > maxListening {
		out = out[:maxListening]
	}
	return out, nil
}

// parseNetSockets reads the kernel's socket table format: "sl local_address rem_address st ...", with the local
// address as hex IP (each 32-bit word in host byte order, little-endian on x86 and arm64) and hex port.
func parseNetSockets(r io.Reader, proto, state string) ([]listenSocket, error) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	var out []listenSocket
	first := true
	for sc.Scan() {
		if first { // header
			first = false
			continue
		}
		f := strings.Fields(sc.Text())
		if len(f) < 4 || f[3] != state {
			continue
		}
		hostHex, portHex, ok := strings.Cut(f[1], ":")
		if !ok {
			continue
		}
		port, err := strconv.ParseUint(portHex, 16, 16)
		if err != nil || port == 0 {
			continue
		}
		ip, err := kernelIP(hostHex)
		if err != nil {
			continue
		}
		out = append(out, listenSocket{Protocol: proto, Address: ip.String(), Port: int(port), Bind: bindClass(ip)})
	}
	return out, sc.Err()
}

func kernelIP(h string) (net.IP, error) {
	b, err := hex.DecodeString(h)
	if err != nil || (len(b) != 4 && len(b) != 16) {
		return nil, fmt.Errorf("bad address %q", h)
	}
	ip := make(net.IP, len(b))
	for w := 0; w < len(b); w += 4 { // each 32-bit word is little-endian
		ip[w], ip[w+1], ip[w+2], ip[w+3] = b[w+3], b[w+2], b[w+1], b[w]
	}
	if v4 := ip.To4(); v4 != nil && len(b) == 16 && !ip.IsUnspecified() {
		return v4, nil // ::ffff:a.b.c.d
	}
	return ip, nil
}

func bindClass(ip net.IP) string {
	switch {
	case ip.IsUnspecified():
		return "all"
	case ip.IsLoopback():
		return "loopback"
	default:
		return "address"
	}
}
