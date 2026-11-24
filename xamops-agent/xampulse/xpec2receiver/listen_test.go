package xpec2receiver

import (
	"context"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"go.uber.org/zap"
)

const tcpFixture = `  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 1001 1 0000000000000000 100 0 0 10 0
   1: 0100007F:18EB 00000000:0000 0A 00000000:00000000 00:00000000 00000000   999        0 1002 1 0000000000000000 100 0 0 10 0
   2: 0501000A:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 1003 1 0000000000000000 100 0 0 10 0
   3: 0501000A:0016 0A01000A:D431 01 00000000:00000000 02:000A7B2F 00000000     0        0 1004 2 0000000000000000 20 4 30 10 -1
`

const tcp6Fixture = `  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000000000000000000000000000:0016 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 2001 1 0000000000000000 100 0 0 10 0
   1: 00000000000000000000000001000000:0CEA 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000   999        0 2002 1 0000000000000000 100 0 0 10 0
   2: 0000000000000000FFFF00000100007F:2382 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000   999        0 2003 1 0000000000000000 100 0 0 10 0
`

const udpFixture = `   sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops
  100: 00000000:0035 00000000:0000 07 00000000:00000000 00:00000000 00000000     0        0 3001 2 0000000000000000 0
  101: 00000000:D3C1 00000000:0000 07 00000000:00000000 00:00000000 00000000     0        0 3002 2 0000000000000000 0
  102: 0501000A:0044 0101000A:0043 01 00000000:00000000 00:00000000 00000000     0        0 3003 2 0000000000000000 0
`

func writeNet(t *testing.T, files map[string]string) string {
	t.Helper()
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "net"), 0o755); err != nil {
		t.Fatal(err)
	}
	for name, body := range files {
		if err := os.WriteFile(filepath.Join(root, "net", name), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return root
}

func TestListeningSocketsFromTheKernelTables(t *testing.T) {
	root := writeNet(t, map[string]string{"tcp": tcpFixture, "tcp6": tcp6Fixture, "udp": udpFixture})
	got, err := readListening(root)
	if err != nil {
		t.Fatal(err)
	}
	var lines []string
	for _, s := range got {
		lines = append(lines, s.Protocol+" "+s.Address+":"+strconv.Itoa(s.Port)+" "+s.Bind)
	}
	want := []string{
		"tcp 0.0.0.0:22 all", "tcp :::22 all", "udp 0.0.0.0:53 all", "tcp ::1:3306 loopback",
		"tcp 127.0.0.1:6379 loopback", "tcp 10.0.1.5:8080 address", "tcp 127.0.0.1:9090 loopback",
	}
	if strings.Join(lines, "\n") != strings.Join(want, "\n") {
		t.Fatalf("got:\n%s\nwant:\n%s\n(established sockets and the ephemeral UDP client port are left out; ::ffff:127.0.0.1 reads as 127.0.0.1)",
			strings.Join(lines, "\n"), strings.Join(want, "\n"))
	}
}

func TestListeningIsAnErrorOnlyWhenNoTableCanBeRead(t *testing.T) {
	if _, err := readListening(writeNet(t, map[string]string{"tcp": tcpFixture})); err != nil {
		t.Fatalf("no IPv6 tables is fine: %v", err)
	}
	if _, err := readListening(t.TempDir()); err == nil {
		t.Fatal("no /proc/net at all must be reported")
	}
}

func TestScraperEmitsOneGaugePerSocketAndKeepsGoingWithoutIt(t *testing.T) {
	cfg := defaultConfig()
	cfg.PSI, cfg.ENA, cfg.IMDS = false, false, false
	cfg.ProcRoot = writeNet(t, map[string]string{"tcp": tcpFixture})
	src := defaultSources(cfg)
	s := newEC2Scraper(cfg, zap.NewNop(), src)
	md, err := s.scrape(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	ms := md.ResourceMetrics().At(0).ScopeMetrics().At(0).Metrics()
	if ms.Len() != 1 || ms.At(0).Name() != MetricListening {
		t.Fatalf("metrics: %d", ms.Len())
	}
	dps := ms.At(0).Gauge().DataPoints()
	if dps.Len() != 3 {
		t.Fatalf("3 listening TCP sockets, got %d", dps.Len())
	}
	a := dps.At(0).Attributes()
	port, _ := a.Get("port")
	bind, _ := a.Get("bind")
	if port.Str() != "22" || bind.Str() != "all" {
		t.Fatalf("first socket %v", a.AsRaw())
	}
	cfg.ProcRoot = t.TempDir()
	md, _ = s.scrape(context.Background())
	if md.ResourceMetrics().At(0).ScopeMetrics().At(0).Metrics().Len() != 0 {
		t.Fatal("no tables: no metric, and no error that would drop other sources")
	}
}
