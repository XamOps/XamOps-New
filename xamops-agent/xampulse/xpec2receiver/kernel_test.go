package xpec2receiver

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"go.opentelemetry.io/collector/component/componenttest"
	"go.opentelemetry.io/collector/pdata/pmetric"
	"go.uber.org/zap"
)

// Real-format excerpts: /proc/net/snmp and /proc/net/netstat are "Proto: names" then "Proto: values".
const snmp = `Ip: Forwarding DefaultTTL InReceives
Ip: 1 64 123456
Tcp: RtoAlgorithm RtoMin RtoMax MaxConn ActiveOpens PassiveOpens AttemptFails EstabResets CurrEstab InSegs OutSegs RetransSegs InErrs OutRsts InCsumErrors
Tcp: 1 200 120000 -1 5000 300 12 40 25 900000 1000000 35000 0 120 0
Udp: InDatagrams NoPorts
Udp: 10 0
`

const netstat = `TcpExt: SyncookiesSent SyncookiesRecv ListenOverflows ListenDrops TCPTimeouts
TcpExt: 0 0 270 275 9
IpExt: InNoRoutes InTruncatedPkts
IpExt: 0 0
`

// /proc/net/tcp rows: sl, local, remote, state. 0A = LISTEN, 01 = ESTABLISHED, 06 = TIME_WAIT.
const tcp4 = `  sl  local_address rem_address   st tx_queue rx_queue
   0: 00000000:0050 00000000:0000 0A 00000000:00000000
   1: 0100007F:8000 0100007F:1F90 01 00000000:00000000
   2: 0100007F:8001 0100007F:1F90 06 00000000:00000000
   3: 0100007F:8001 0200007F:1F90 06 00000000:00000000
   4: 0100007F:1F90 0100007F:8000 01 00000000:00000000
`

const tcp6 = `  sl  local_address                         remote_address                        st
   0: 00000000000000000000000001000000:8002 00000000000000000000000001000000:01BB 01
`

func TestReadKernelReadsEveryOSLimitSourceFromProc(t *testing.T) {
	files := map[string]string{
		"sys/fs/file-nr":                   "9024\t0\t9223372036854775807\n",
		"sys/net/ipv4/ip_local_port_range": "32768\t60999\n",
		"net/tcp":                          tcp4,
		"net/tcp6":                         tcp6,
		"net/snmp":                         snmp,
		"net/netstat":                      netstat,
		"4121/limits":                      "Limit                     Soft Limit           Hard Limit           Units\nMax open files            1024                 524288               files\n",
		"4121/comm":                        "java\n",
		"77/limits":                        "Max open files            1024                 524288               files\n",
		"77/comm":                          "nginx\n",
		"88/limits":                        "Max open files            unlimited            unlimited            files\n",
		"88/comm":                          "odd\n",
	}
	for i := 0; i < 600; i++ { // java holds 600 of 1024: reported
		files[fmt.Sprintf("4121/fd/%d", i)] = ""
	}
	for i := 0; i < 10; i++ { // nginx holds 10 of 1024: not reported
		files[fmt.Sprintf("77/fd/%d", i)] = ""
	}
	files["88/fd/0"] = "" // no limit: not reported
	root := writeProc(t, files)

	k, err := readKernel(root, true)
	if err != nil {
		t.Fatal(err)
	}
	if !k.HaveFiles || k.FilesAllocated != 9024 || k.FileMax != 9223372036854775807 {
		t.Fatalf("file handles %+v", k)
	}
	// Ports 32768 and 32769 on IPv4 (TIME_WAIT twice on one port counts once), 32770 on IPv6; LISTEN :80 and the
	// server side :8080 are outside the range.
	if !k.HavePorts || k.PortsUsed != 3 || k.PortsRange != 28232 {
		t.Fatalf("local ports used %v of %v", k.PortsUsed, k.PortsRange)
	}
	if !k.HaveTCP || k.OutSegs != 1_000_000 || k.RetransSegs != 35_000 {
		t.Fatalf("tcp %v %v", k.OutSegs, k.RetransSegs)
	}
	if !k.HaveListen || k.ListenOverflows != 270 || k.ListenDrops != 275 {
		t.Fatalf("listen %v %v", k.ListenOverflows, k.ListenDrops)
	}
	if len(k.Processes) != 1 || k.Processes[0].Name != "java" || k.Processes[0].PID != 4121 || k.Processes[0].Open != 600 || k.Processes[0].Limit != 1024 {
		t.Fatalf("processes %+v", k.Processes)
	}
}

func TestReadKernelIsAnErrorOnlyWhenNothingCanBeRead(t *testing.T) {
	if _, err := readKernel(t.TempDir(), true); err == nil {
		t.Fatal("an empty /proc must be an error, not all zeros")
	}
	root := writeProc(t, map[string]string{"net/snmp": snmp})
	k, err := readKernel(root, false)
	if err != nil || !k.HaveTCP || k.HaveFiles || k.HavePorts || k.HaveListen {
		t.Fatalf("one readable source is enough, and the others stay unreported: %+v %v", k, err)
	}
	if _, err := parseProtoCounters(strings.NewReader("Tcp: OutSegs\nTcp: 5\n"), "Tcp:", "OutSegs", "RetransSegs"); err == nil {
		t.Fatal("a missing counter must be an error, never 0")
	}
}

func TestReadProcessFilesSkipsWhatItCannotRead(t *testing.T) {
	root := writeProc(t, map[string]string{"4121/limits": "Max open files 1024 4096 files\n", "4121/comm": "java\n"})
	// No fd directory (exited, or not readable): skipped without an error.
	if got := readProcessFiles(root); len(got) != 0 {
		t.Fatalf("%+v", got)
	}
	if err := os.MkdirAll(filepath.Join(root, "self"), 0o755); err != nil {
		t.Fatal(err)
	}
	if got := readProcessFiles(root); len(got) != 0 {
		t.Fatalf("non-numeric entries are not processes: %+v", got)
	}
}

func TestScrapeEmitsTheOSLimitsWithTheirShapes(t *testing.T) {
	src := testSources(nil, os.ErrNotExist, nil, os.ErrNotExist, staticIMDS{err: os.ErrNotExist})
	src.kernel = func(string, bool) (kernelStats, error) {
		return kernelStats{HaveFiles: true, FilesAllocated: 9024, FileMax: 9_000_000, HavePorts: true, PortsUsed: 25_000, PortsRange: 28_232,
			HaveTCP: true, OutSegs: 1_000_000, RetransSegs: 35_000, HaveListen: true, ListenOverflows: 270, ListenDrops: 275,
			Processes: []processFiles{{PID: 4121, Name: "java", Open: 990, Limit: 1024}}}, nil
	}
	s := newEC2Scraper(defaultConfig(), zap.NewNop(), src)
	if err := s.begin(context.Background(), componenttest.NewNopHost()); err != nil {
		t.Fatal(err)
	}
	md, _ := s.scrape(context.Background())
	m := metricsByName(md)

	byAttr := func(metric pmetric.Metric, key string) map[string]float64 {
		out := map[string]float64{}
		var dps pmetric.NumberDataPointSlice
		if metric.Type() == pmetric.MetricTypeSum {
			dps = metric.Sum().DataPoints()
		} else {
			dps = metric.Gauge().DataPoints()
		}
		for i := 0; i < dps.Len(); i++ {
			v, _ := dps.At(i).Attributes().Get(key)
			out[v.Str()] = dps.At(i).DoubleValue()
		}
		return out
	}
	if got := byAttr(m[MetricFileHandles], "state"); got["allocated"] != 9024 || got["max"] != 9_000_000 {
		t.Fatalf("file handles %v", got)
	}
	if got := byAttr(m[MetricLocalPorts], "state"); got["used"] != 25_000 || got["range"] != 28_232 {
		t.Fatalf("local ports %v", got)
	}
	seg := m[MetricTCPSegments]
	if seg.Type() != pmetric.MetricTypeSum || !seg.Sum().IsMonotonic() || seg.Sum().AggregationTemporality() != pmetric.AggregationTemporalityCumulative {
		t.Fatal("TCP segments must be a cumulative monotonic sum")
	}
	if got := byAttr(seg, "type"); got["out"] != 1_000_000 || got["retrans"] != 35_000 {
		t.Fatalf("segments %v", got)
	}
	if got := byAttr(m[MetricListenQueue], "event"); got["overflow"] != 270 || got["drop"] != 275 {
		t.Fatalf("listen queue %v", got)
	}
	p := m[MetricProcessFiles].Gauge().DataPoints().At(0)
	name, _ := p.Attributes().Get("name")
	limit, _ := p.Attributes().Get("limit")
	pid, _ := p.Attributes().Get("pid")
	if p.DoubleValue() != 990 || name.Str() != "java" || limit.Str() != "1024" || pid.Str() != "4121" {
		t.Fatalf("process files %v %v %v %v", p.DoubleValue(), name.Str(), limit.Str(), pid.Str())
	}
}
