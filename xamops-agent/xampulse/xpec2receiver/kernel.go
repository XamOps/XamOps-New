package xpec2receiver

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// Operating-system limits that make a host slow or failing while CPU looks fine (Throttle Radar checks 9 to 12).
// All read from /proc; the per-process open-file counts need to read other users' /proc/<pid>/fd, which the agent
// can do because it runs as root, and a process it cannot read is skipped.
const (
	// MetricFileHandles: gauge {file}, state=allocated|max, from /proc/sys/fs/file-nr (the whole system).
	MetricFileHandles = "xp.fs.file_handles"
	// MetricProcessFiles: gauge {file} per process at or above half of its own soft open-file limit (ulimit -n);
	// attributes pid, name, limit. Processes well inside their limit are not reported (bounded series).
	MetricProcessFiles = "xp.process.open_files"
	// MetricLocalPorts: gauge {port}, state=used|range. Distinct local TCP ports in use inside
	// net.ipv4.ip_local_port_range (the ports outgoing connections take), and the size of that range.
	MetricLocalPorts = "xp.net.local_ports"
	// MetricTCPSegments: sum {segment}, cumulative since boot, type=out|retrans, from /proc/net/snmp.
	MetricTCPSegments = "xp.tcp.segments"
	// MetricListenQueue: sum {connection}, cumulative since boot, event=overflow|drop, from /proc/net/netstat.
	MetricListenQueue = "xp.tcp.listen_queue"
)

// maxProcessFiles bounds the per-process series; minProcessFiles ignores tiny limits that mean nothing.
const (
	maxProcessFiles = 20
	minProcessFiles = 64
)

// kernelStats is one read of every OS-limit source; a source that could not be read leaves its Have flag false.
type kernelStats struct {
	HaveFiles               bool
	FilesAllocated, FileMax float64

	HavePorts             bool
	PortsUsed, PortsRange float64

	HaveTCP              bool
	OutSegs, RetransSegs float64

	HaveListen                   bool
	ListenOverflows, ListenDrops float64

	Processes []processFiles
}

type processFiles struct {
	PID   int
	Name  string
	Open  float64
	Limit float64
}

// readKernel reads every source it can. It is an error only when none of them can be read.
func readKernel(procRoot string, processes bool) (kernelStats, error) {
	var k kernelStats
	var errs []error
	if a, m, err := readFileNr(procRoot); err == nil {
		k.HaveFiles, k.FilesAllocated, k.FileMax = true, a, m
	} else {
		errs = append(errs, err)
	}
	if used, size, err := readLocalPorts(procRoot); err == nil {
		k.HavePorts, k.PortsUsed, k.PortsRange = true, used, size
	} else {
		errs = append(errs, err)
	}
	if v, err := readProtoCounters(filepath.Join(procRoot, "net", "snmp"), "Tcp:", "OutSegs", "RetransSegs"); err == nil {
		k.HaveTCP, k.OutSegs, k.RetransSegs = true, v[0], v[1]
	} else {
		errs = append(errs, err)
	}
	if v, err := readProtoCounters(filepath.Join(procRoot, "net", "netstat"), "TcpExt:", "ListenOverflows", "ListenDrops"); err == nil {
		k.HaveListen, k.ListenOverflows, k.ListenDrops = true, v[0], v[1]
	} else {
		errs = append(errs, err)
	}
	if processes {
		k.Processes = readProcessFiles(procRoot)
	}
	if !k.HaveFiles && !k.HavePorts && !k.HaveTCP && !k.HaveListen {
		return k, errors.Join(errs...)
	}
	return k, nil
}

// readFileNr parses /proc/sys/fs/file-nr: "allocated unused max". Modern kernels always report unused as 0, so
// allocated is the number of open file handles.
func readFileNr(procRoot string) (allocated, max float64, err error) {
	b, err := os.ReadFile(filepath.Join(procRoot, "sys", "fs", "file-nr"))
	if err != nil {
		return 0, 0, err
	}
	f := strings.Fields(string(b))
	if len(f) < 3 {
		return 0, 0, fmt.Errorf("file-nr: unexpected %q", string(b))
	}
	a, err1 := strconv.ParseFloat(f[0], 64)
	m, err2 := strconv.ParseFloat(f[2], 64)
	if err1 != nil || err2 != nil || m <= 0 {
		return 0, 0, fmt.Errorf("file-nr: unexpected %q", string(b))
	}
	return a, m, nil
}

// readLocalPorts counts the distinct local TCP ports in use inside the ephemeral range, from /proc/net/tcp and tcp6
// (every state except LISTEN: established, TIME_WAIT and the rest all hold their port).
func readLocalPorts(procRoot string) (used, size float64, err error) {
	b, err := os.ReadFile(filepath.Join(procRoot, "sys", "net", "ipv4", "ip_local_port_range"))
	if err != nil {
		return 0, 0, err
	}
	f := strings.Fields(string(b))
	if len(f) < 2 {
		return 0, 0, fmt.Errorf("ip_local_port_range: unexpected %q", string(b))
	}
	lo, err1 := strconv.Atoi(f[0])
	hi, err2 := strconv.Atoi(f[1])
	if err1 != nil || err2 != nil || hi < lo {
		return 0, 0, fmt.Errorf("ip_local_port_range: unexpected %q", string(b))
	}
	ports := map[int]struct{}{}
	read := 0
	for _, name := range []string{"tcp", "tcp6"} {
		fh, err := os.Open(filepath.Join(procRoot, "net", name))
		if err != nil {
			continue
		}
		err = collectLocalPorts(fh, lo, hi, ports)
		fh.Close()
		if err == nil {
			read++
		}
	}
	if read == 0 {
		return 0, 0, fmt.Errorf("no readable /proc/net/tcp")
	}
	return float64(len(ports)), float64(hi - lo + 1), nil
}

func collectLocalPorts(r io.Reader, lo, hi int, into map[int]struct{}) error {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	first := true
	for sc.Scan() {
		if first {
			first = false
			continue
		}
		f := strings.Fields(sc.Text())
		if len(f) < 4 || f[3] == "0A" { // 0A = LISTEN
			continue
		}
		_, portHex, ok := strings.Cut(f[1], ":")
		if !ok {
			continue
		}
		port, err := strconv.ParseUint(portHex, 16, 16)
		if err != nil {
			continue
		}
		if p := int(port); p >= lo && p <= hi {
			into[p] = struct{}{}
		}
	}
	return sc.Err()
}

// readProtoCounters reads named counters from the kernel's "Proto: name name ...\nProto: value value ..." files
// (/proc/net/snmp, /proc/net/netstat): the header line and the value line that follows it share the prefix.
func readProtoCounters(path, prefix string, names ...string) ([]float64, error) {
	fh, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer fh.Close()
	return parseProtoCounters(fh, prefix, names...)
}

func parseProtoCounters(r io.Reader, prefix string, names ...string) ([]float64, error) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	var header []string
	for sc.Scan() {
		line := sc.Text()
		if !strings.HasPrefix(line, prefix) {
			continue
		}
		f := strings.Fields(line)[1:]
		if header == nil {
			header = f
			continue
		}
		out := make([]float64, len(names))
		for i, n := range names {
			idx := -1
			for j, h := range header {
				if h == n {
					idx = j
					break
				}
			}
			if idx < 0 || idx >= len(f) {
				return nil, fmt.Errorf("%s %s not reported", prefix, n)
			}
			v, err := strconv.ParseFloat(f[idx], 64)
			if err != nil {
				return nil, fmt.Errorf("%s %s: %w", prefix, n, err)
			}
			out[i] = v
		}
		return out, nil
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	return nil, fmt.Errorf("%s not found", prefix)
}

// readProcessFiles returns processes holding at least half of their own soft open-file limit, fullest first, at
// most maxProcessFiles. A process that exits mid-read or whose fd directory is not readable is skipped.
func readProcessFiles(procRoot string) []processFiles {
	entries, err := os.ReadDir(procRoot)
	if err != nil {
		return nil
	}
	var out []processFiles
	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil || !e.IsDir() {
			continue
		}
		dir := filepath.Join(procRoot, e.Name())
		limit, err := softOpenFileLimit(filepath.Join(dir, "limits"))
		if err != nil || limit < minProcessFiles {
			continue
		}
		fd, err := os.Open(filepath.Join(dir, "fd"))
		if err != nil {
			continue
		}
		names, err := fd.Readdirnames(-1)
		fd.Close()
		if err != nil || float64(len(names)) < limit/2 {
			continue
		}
		comm, _ := os.ReadFile(filepath.Join(dir, "comm"))
		out = append(out, processFiles{PID: pid, Name: strings.TrimSpace(string(comm)), Open: float64(len(names)), Limit: limit})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Open/out[i].Limit > out[j].Open/out[j].Limit })
	if len(out) > maxProcessFiles {
		out = out[:maxProcessFiles]
	}
	return out
}

// softOpenFileLimit reads "Max open files  <soft>  <hard>  files" from /proc/<pid>/limits. "unlimited" is no limit.
func softOpenFileLimit(path string) (float64, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0, err
	}
	for _, line := range strings.Split(string(b), "\n") {
		if !strings.HasPrefix(line, "Max open files") {
			continue
		}
		f := strings.Fields(strings.TrimPrefix(line, "Max open files"))
		if len(f) < 1 || f[0] == "unlimited" {
			return 0, fmt.Errorf("no open-file limit")
		}
		return strconv.ParseFloat(f[0], 64)
	}
	return 0, fmt.Errorf("no Max open files line")
}
