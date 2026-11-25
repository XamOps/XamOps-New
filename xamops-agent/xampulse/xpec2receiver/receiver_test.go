package xpec2receiver

import (
	"context"
	"encoding/binary"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"go.opentelemetry.io/collector/component/componenttest"
	"go.opentelemetry.io/collector/consumer/consumertest"
	"go.opentelemetry.io/collector/pdata/pmetric"
	"go.opentelemetry.io/collector/receiver/receivertest"
	"go.uber.org/zap"
	"go.uber.org/zap/zaptest/observer"
)

// ---------------------------------------------------------------- PSI

const psiCPU = "some avg10=1.53 avg60=0.87 avg300=0.40 total=2500000\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=0\n"
const psiMem = "some avg10=0.00 avg60=0.00 avg300=0.00 total=1200\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=800\n"

func writeProc(t *testing.T, files map[string]string) string {
	t.Helper()
	root := t.TempDir()
	for name, body := range files {
		p := filepath.Join(root, name)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return root
}

func TestPSIParsesTotalsInMicroseconds(t *testing.T) {
	root := writeProc(t, map[string]string{"pressure/cpu": psiCPU, "pressure/memory": psiMem, "pressure/io": psiMem})
	got, err := readPSI(root)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 6 {
		t.Fatalf("want some+full for 3 resources, got %d: %+v", len(got), got)
	}
	if got[0] != (psiSample{Resource: "cpu", Level: "some", TotalUS: 2_500_000}) {
		t.Fatalf("cpu some = %+v", got[0])
	}
}

func TestPSIMissingIsNotExistAndGarbageIsAnError(t *testing.T) {
	if _, err := readPSI(t.TempDir()); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("no /proc/pressure must be ErrNotExist, got %v", err)
	}
	root := writeProc(t, map[string]string{"pressure/cpu": "some avg10=0.00 total=abc\n"})
	if _, err := readPSI(root); err == nil {
		t.Fatal("a non-numeric total must be an error, not a silent zero")
	}
}

// ---------------------------------------------------------------- ethtool decoding

func TestDecodeStatsPairsNamesWithValues(t *testing.T) {
	names := make([]byte, 3*ethGStringLen)
	copy(names[0:], "rx_packets")
	copy(names[ethGStringLen:], "pps_allowance_exceeded")
	copy(names[2*ethGStringLen:], "conntrack_allowance_available")
	vals := make([]byte, 3*8)
	binary.NativeEndian.PutUint64(vals[0:], 1)
	binary.NativeEndian.PutUint64(vals[8:], 4210)
	binary.NativeEndian.PutUint64(vals[16:], 131072)
	got := decodeStats(names, vals, 3)
	if got["pps_allowance_exceeded"] != 4210 || got["conntrack_allowance_available"] != 131072 || got["rx_packets"] != 1 {
		t.Fatalf("got %v", got)
	}
	if len(decodeStats(names, vals[:8], 3)) != 1 {
		t.Fatal("a short values buffer must not be read past its end")
	}
}

// ---------------------------------------------------------------- IMDS

type fakeIMDS struct {
	tokens, gets atomic.Int32
	spot         string
	rebalance    string
	scheduled    string
	rejectToken  atomic.Bool
}

func (f *fakeIMDS) handler(t *testing.T) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/latest/api/token" {
			if r.Method != http.MethodPut || r.Header.Get("X-aws-ec2-metadata-token-ttl-seconds") == "" {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			f.tokens.Add(1)
			_, _ = w.Write([]byte("tok-1"))
			return
		}
		f.gets.Add(1)
		if r.Header.Get("X-aws-ec2-metadata-token") != "tok-1" || f.rejectToken.Load() {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		body := map[string]string{
			"/latest/meta-data/spot/instance-action":             f.spot,
			"/latest/meta-data/events/recommendations/rebalance": f.rebalance,
			"/latest/meta-data/events/maintenance/scheduled":     f.scheduled,
		}[r.URL.Path]
		if body == "" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_, _ = w.Write([]byte(body))
	})
}

func TestIMDSNoNoticesIsAllZeroAndTheTokenIsReused(t *testing.T) {
	f := &fakeIMDS{scheduled: "[]"}
	srv := httptest.NewServer(f.handler(t))
	defer srv.Close()
	c := newIMDSClient(srv.URL, time.Second)
	for i := 0; i < 3; i++ {
		st, err := c.state(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		if st != (imdsState{}) {
			t.Fatalf("no notice must read as zero, got %+v", st)
		}
	}
	if f.tokens.Load() != 1 {
		t.Fatalf("token must be cached, fetched %d times", f.tokens.Load())
	}
}

func TestIMDSReadsSpotRebalanceAndOnlyActiveMaintenance(t *testing.T) {
	f := &fakeIMDS{
		spot:      `{"action": "terminate", "time": "2026-11-11T08:22:00Z"}`,
		rebalance: `{"noticeTime": "2026-11-11T08:15:00Z"}`,
		scheduled: `[{"NotBefore":"21 Jan 2027 09:00:43 GMT","Code":"system-reboot","State":"active"},
		             {"NotBefore":"22 Jan 2027 09:00:43 GMT","Code":"system-reboot","State":"canceled"}]`,
	}
	srv := httptest.NewServer(f.handler(t))
	defer srv.Close()
	st, err := newIMDSClient(srv.URL, time.Second).state(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	want := imdsState{SpotAction: "terminate", SpotTime: "2026-11-11T08:22:00Z", RebalanceNotice: "2026-11-11T08:15:00Z",
		ScheduledActive: 1, ScheduledEarliest: "21 Jan 2027 09:00:43 GMT"}
	if st != want {
		t.Fatalf("got %+v want %+v", st, want)
	}
}

func TestIMDSRejectedTokenIsDroppedAndRefetched(t *testing.T) {
	f := &fakeIMDS{scheduled: "[]"}
	srv := httptest.NewServer(f.handler(t))
	defer srv.Close()
	c := newIMDSClient(srv.URL, time.Second)
	if _, err := c.state(context.Background()); err != nil {
		t.Fatal(err)
	}
	f.rejectToken.Store(true)
	if _, err := c.state(context.Background()); err == nil {
		t.Fatal("a rejected token must surface as an error")
	}
	f.rejectToken.Store(false)
	if _, err := c.state(context.Background()); err != nil {
		t.Fatal(err)
	}
	if f.tokens.Load() != 2 {
		t.Fatalf("after a 401 a fresh token must be fetched, got %d token requests", f.tokens.Load())
	}
}

func TestIMDSUnreachableBacksOffInsteadOfTimingOutEveryScrape(t *testing.T) {
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		time.Sleep(300 * time.Millisecond) // slower than the client timeout: looks like no IMDS at all
	}))
	defer srv.Close()
	c := newIMDSClient(srv.URL, 50*time.Millisecond)
	now := time.Unix(1_800_000_000, 0)
	c.now = func() time.Time { return now }
	for i := 0; i < 5; i++ {
		if _, err := c.state(context.Background()); !errors.Is(err, errNotEC2) {
			t.Fatalf("want errNotEC2, got %v", err)
		}
	}
	if hits.Load() != 1 {
		t.Fatalf("only one attempt within the back-off window, got %d", hits.Load())
	}
	now = now.Add(6 * time.Minute)
	_, _ = c.state(context.Background())
	if hits.Load() != 2 {
		t.Fatalf("must retry after the back-off window, got %d", hits.Load())
	}
}

// ---------------------------------------------------------------- scraper

type staticIMDS struct {
	st  imdsState
	err error
}

func (s staticIMDS) state(context.Context) (imdsState, error) { return s.st, s.err }

func testSources(psi []psiSample, psiErr error, nics []nicStats, nicErr error, im imdsReader) sources {
	return sources{
		psi:      func(string) ([]psiSample, error) { return psi, psiErr },
		stats:    func([]string) ([]nicStats, error) { return nics, nicErr },
		imds:     im,
		bootTime: func(string) (time.Time, error) { return time.Unix(1_700_000_000, 0), nil },
		now:      func() time.Time { return time.Unix(1_800_000_000, 0) },
	}
}

func metricsByName(md pmetric.Metrics) map[string]pmetric.Metric {
	out := map[string]pmetric.Metric{}
	ms := md.ResourceMetrics().At(0).ScopeMetrics().At(0).Metrics()
	for i := 0; i < ms.Len(); i++ {
		out[ms.At(i).Name()] = ms.At(i)
	}
	return out
}

func TestScrapeEmitsEverySignalWithTheRightShape(t *testing.T) {
	src := testSources(
		[]psiSample{{"cpu", "some", 2_500_000}, {"memory", "full", 800}}, nil,
		[]nicStats{{Device: "ens5", Driver: "ena", Stats: map[string]uint64{
			"rx_packets": 99, "pps_allowance_exceeded": 4210, "bw_in_allowance_exceeded": 0, "conntrack_allowance_available": 131072}}}, nil,
		staticIMDS{st: imdsState{SpotAction: "stop", SpotTime: "2026-11-11T08:22:00Z", ScheduledActive: 2}})
	s := newEC2Scraper(defaultConfig(), zap.NewNop(), src)
	if err := s.begin(context.Background(), componenttest.NewNopHost()); err != nil {
		t.Fatal(err)
	}
	md, err := s.scrape(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	m := metricsByName(md)

	stall := m[MetricPressureStall]
	if stall.Type() != pmetric.MetricTypeSum || !stall.Sum().IsMonotonic() || stall.Sum().AggregationTemporality() != pmetric.AggregationTemporalityCumulative {
		t.Fatal("stall time must be a cumulative monotonic sum")
	}
	dp := stall.Sum().DataPoints().At(0)
	if dp.DoubleValue() != 2.5 || !dp.StartTimestamp().AsTime().Equal(time.Unix(1_700_000_000, 0)) {
		t.Fatalf("stall seconds %v start %v", dp.DoubleValue(), dp.StartTimestamp().AsTime())
	}
	if r, _ := dp.Attributes().Get("resource"); r.Str() != "cpu" {
		t.Fatal("resource attribute")
	}

	ena := m[MetricENAExceeded]
	if ena.Sum().DataPoints().Len() != 2 {
		t.Fatalf("only the allowance counters the NIC reports (bw_in, pps), got %d", ena.Sum().DataPoints().Len())
	}
	seen := map[string]int64{}
	for i := 0; i < ena.Sum().DataPoints().Len(); i++ {
		p := ena.Sum().DataPoints().At(i)
		a, _ := p.Attributes().Get("allowance")
		d, _ := p.Attributes().Get("device")
		if d.Str() != "ens5" {
			t.Fatal("device attribute")
		}
		seen[a.Str()] = p.IntValue()
	}
	if seen["pps"] != 4210 || seen["bw_in"] != 0 {
		t.Fatalf("allowances %v", seen)
	}
	if v := m[MetricENAConntrackAvail].Gauge().DataPoints().At(0).IntValue(); v != 131072 {
		t.Fatalf("conntrack available %d", v)
	}

	spot := m[MetricSpotInterruption].Gauge().DataPoints().At(0)
	if a, _ := spot.Attributes().Get("action"); spot.IntValue() != 1 || a.Str() != "stop" {
		t.Fatal("spot notice must be 1 with its action")
	}
	if m[MetricRebalance].Gauge().DataPoints().At(0).IntValue() != 0 {
		t.Fatal("no rebalance recommendation must read 0, not be absent")
	}
	if m[MetricMaintenance].Gauge().DataPoints().At(0).IntValue() != 2 {
		t.Fatal("scheduled maintenance count")
	}
}

func TestUnavailableSourcesEmitNothingLogOnceAndNeverDropTheOthers(t *testing.T) {
	core, logs := observer.New(zap.InfoLevel)
	src := testSources(nil, os.ErrNotExist, nil, nil, staticIMDS{err: errNotEC2})
	src.psi = func(string) ([]psiSample, error) { return nil, os.ErrNotExist }
	s := newEC2Scraper(defaultConfig(), zap.New(core), src)
	for i := 0; i < 5; i++ {
		md, err := s.scrape(context.Background())
		if err != nil {
			t.Fatal("a missing source is not a scrape error")
		}
		if n := md.ResourceMetrics().At(0).ScopeMetrics().At(0).Metrics().Len(); n != 0 {
			t.Fatalf("nothing measurable must emit nothing, got %d metrics", n)
		}
	}
	if logs.Len() != 3 {
		t.Fatalf("each unavailable source is logged once, got %d: %v", logs.Len(), logs.All())
	}

	// PSI fails hard; ENA and IMDS must still be reported.
	src2 := testSources(nil, errors.New("EOPNOTSUPP"), []nicStats{{Device: "ens5", Driver: "ena",
		Stats: map[string]uint64{"pps_allowance_exceeded": 1}}}, nil, staticIMDS{})
	md, _ := newEC2Scraper(defaultConfig(), zap.NewNop(), src2).scrape(context.Background())
	m := metricsByName(md)
	if _, ok := m[MetricPressureStall]; ok {
		t.Fatal("a failed PSI read must not emit stall time")
	}
	if _, ok := m[MetricENAExceeded]; !ok {
		t.Fatal("ENA must be reported even though PSI failed")
	}
	if _, ok := m[MetricSpotInterruption]; !ok {
		t.Fatal("IMDS must be reported even though PSI failed")
	}
}

func TestOldENADriverWithoutAllowanceCountersIsUnavailableNotZero(t *testing.T) {
	src := testSources(nil, os.ErrNotExist, []nicStats{{Device: "eth0", Driver: "ena", Stats: map[string]uint64{"rx_packets": 5}}}, nil, staticIMDS{err: errNotEC2})
	md, _ := newEC2Scraper(defaultConfig(), zap.NewNop(), src).scrape(context.Background())
	if _, ok := metricsByName(md)[MetricENAExceeded]; ok {
		t.Fatal("no allowance counters must emit nothing, never a misleading zero")
	}
}

// ---------------------------------------------------------------- factory and config

func TestFactoryBuildsAReceiverAndConfigValidates(t *testing.T) {
	f := NewFactory()
	if f.Type().String() != TypeStr {
		t.Fatal(f.Type())
	}
	cfg := f.CreateDefaultConfig().(*Config)
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	r, err := f.CreateMetrics(context.Background(), receivertest.NewNopSettings(f.Type()), cfg, consumertest.NewNop())
	if err != nil || r == nil {
		t.Fatalf("create: %v", err)
	}
	bad := *cfg
	bad.IMDSEndpoint = "169.254.169.254"
	if bad.Validate() == nil {
		t.Fatal("an endpoint without a scheme must be rejected")
	}
	bad = *cfg
	bad.ENADrivers = nil
	if bad.Validate() == nil || !strings.Contains(bad.Validate().Error(), "ena_drivers") {
		t.Fatal("ENA enabled with no drivers must be rejected")
	}
}
