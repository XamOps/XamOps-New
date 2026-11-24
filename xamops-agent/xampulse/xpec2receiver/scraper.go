package xpec2receiver

import (
	"bufio"
	"context"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"go.opentelemetry.io/collector/component"
	"go.opentelemetry.io/collector/pdata/pcommon"
	"go.opentelemetry.io/collector/pdata/pmetric"
	"go.uber.org/zap"
)

const scopeName = "github.com/xamops/agent/xampulse/xpec2receiver"

// Metric names (see doc.go).
const (
	MetricPressureStall     = "xp.pressure.stall_time"
	MetricENAExceeded       = "xp.ena.allowance_exceeded"
	MetricENAConntrackAvail = "xp.ena.conntrack_allowance_available"
	MetricSpotInterruption  = "xp.ec2.spot.interruption"
	MetricRebalance         = "xp.ec2.rebalance_recommendation"
	MetricMaintenance       = "xp.ec2.maintenance.scheduled"
)

type imdsReader interface {
	state(ctx context.Context) (imdsState, error)
}

type sources struct {
	psi      func(procRoot string) ([]psiSample, error)
	listen   func(procRoot string) ([]listenSocket, error)
	kernel   func(procRoot string, processes bool) (kernelStats, error)
	stats    statsSource
	imds     imdsReader
	bootTime func(procRoot string) (time.Time, error)
	now      func() time.Time
}

func defaultSources(c *Config) sources {
	return sources{
		psi:      readPSI,
		listen:   readListening,
		kernel:   readKernel,
		stats:    defaultStatsSource(),
		imds:     newIMDSClient(c.IMDSEndpoint, c.IMDSTimeout),
		bootTime: readBootTime,
		now:      time.Now,
	}
}

type ec2Scraper struct {
	cfg    *Config
	log    *zap.Logger
	src    sources
	start  pcommon.Timestamp
	status map[string]string // last reported state per source, so each change is logged once
}

func newEC2Scraper(cfg *Config, log *zap.Logger, src sources) *ec2Scraper {
	return &ec2Scraper{cfg: cfg, log: log, src: src, status: map[string]string{}}
}

// begin is the scraper's StartFunc: counters are stamped with the boot time they count from.
func (s *ec2Scraper) begin(context.Context, component.Host) error {
	t, err := s.src.bootTime(s.cfg.ProcRoot)
	if err != nil {
		t = s.src.now()
	}
	s.start = pcommon.NewTimestampFromTime(t)
	return nil
}

// report logs a source's state only when it changes ("ok", "unavailable: ...", "error: ...").
func (s *ec2Scraper) report(source, state string, err error) {
	if s.status[source] == state {
		return
	}
	s.status[source] = state
	switch {
	case state == "ok":
		s.log.Info("xampulse_ec2 source is reporting", zap.String("source", source))
	case strings.HasPrefix(state, "unavailable"):
		s.log.Info("xampulse_ec2 source not available on this host", zap.String("source", source), zap.String("reason", state))
	default:
		s.log.Warn("xampulse_ec2 source failed", zap.String("source", source), zap.Error(err))
	}
}

// scrape never returns an error: one source failing must not drop the others' points.
func (s *ec2Scraper) scrape(ctx context.Context) (pmetric.Metrics, error) {
	md := pmetric.NewMetrics()
	sm := md.ResourceMetrics().AppendEmpty().ScopeMetrics().AppendEmpty()
	sm.Scope().SetName(scopeName)
	now := pcommon.NewTimestampFromTime(s.src.now())
	if s.start == 0 {
		s.start = now
	}
	if s.cfg.PSI {
		s.scrapePSI(sm, now)
	}
	if s.cfg.ENA {
		s.scrapeENA(sm, now)
	}
	if s.cfg.IMDS {
		s.scrapeIMDS(ctx, sm, now)
	}
	if s.cfg.Listen && s.src.listen != nil {
		s.scrapeListening(sm, now)
	}
	if s.cfg.Kernel && s.src.kernel != nil {
		s.scrapeKernel(sm, now)
	}
	return md, nil
}

// scrapeKernel emits the OS-limit gauges and counters. Each source is reported only when it was read.
func (s *ec2Scraper) scrapeKernel(sm pmetric.ScopeMetrics, now pcommon.Timestamp) {
	k, err := s.src.kernel(s.cfg.ProcRoot, s.cfg.ProcessFiles)
	if err != nil {
		s.report("kernel", "error: "+err.Error(), err)
		return
	}
	s.report("kernel", "ok", nil)
	gauge := func(m pmetric.Metric, v float64, attrs ...string) {
		dp := m.Gauge().DataPoints().AppendEmpty()
		dp.SetTimestamp(now)
		dp.SetDoubleValue(v)
		for i := 0; i+1 < len(attrs); i += 2 {
			dp.Attributes().PutStr(attrs[i], attrs[i+1])
		}
	}
	sum := func(m pmetric.Metric, v float64, key, val string) {
		dp := m.Sum().DataPoints().AppendEmpty()
		dp.SetStartTimestamp(s.start)
		dp.SetTimestamp(now)
		dp.SetDoubleValue(v)
		dp.Attributes().PutStr(key, val)
	}
	if k.HaveFiles {
		m := newGauge(sm, MetricFileHandles, "{file}", "Open file handles in the whole system, and the system limit (fs.file-max).")
		gauge(m, k.FilesAllocated, "state", "allocated")
		gauge(m, k.FileMax, "state", "max")
	}
	if len(k.Processes) > 0 {
		m := newGauge(sm, MetricProcessFiles, "{file}", "Open files of a process holding at least half of its own open-file limit.")
		for _, p := range k.Processes {
			gauge(m, p.Open, "pid", strconv.Itoa(p.PID), "name", p.Name, "limit", strconv.FormatFloat(p.Limit, 'f', -1, 64))
		}
	}
	if k.HavePorts {
		m := newGauge(sm, MetricLocalPorts, "{port}", "Local TCP ports in use inside the ephemeral range, and the size of the range.")
		gauge(m, k.PortsUsed, "state", "used")
		gauge(m, k.PortsRange, "state", "range")
	}
	if k.HaveTCP {
		m := newSum(sm, MetricTCPSegments, "{segment}", "TCP segments sent, and segments sent again (retransmitted), since boot.")
		sum(m, k.OutSegs, "type", "out")
		sum(m, k.RetransSegs, "type", "retrans")
	}
	if k.HaveListen {
		m := newSum(sm, MetricListenQueue, "{connection}", "Connections dropped because a listen queue was full, since boot.")
		sum(m, k.ListenOverflows, "event", "overflow")
		sum(m, k.ListenDrops, "event", "drop")
	}
}

func (s *ec2Scraper) scrapeListening(sm pmetric.ScopeMetrics, now pcommon.Timestamp) {
	socks, err := s.src.listen(s.cfg.ProcRoot)
	if err != nil {
		s.report("listen", "error: "+err.Error(), err)
		return
	}
	s.report("listen", "ok", nil)
	m := newGauge(sm, MetricListening, "{socket}", "A listening socket (TCP) or bound UDP socket below the ephemeral range.")
	for _, l := range socks {
		dp := m.Gauge().DataPoints().AppendEmpty()
		dp.SetTimestamp(now)
		dp.SetDoubleValue(1)
		dp.Attributes().PutStr("protocol", l.Protocol)
		dp.Attributes().PutStr("port", strconv.Itoa(l.Port))
		dp.Attributes().PutStr("bind", l.Bind)
		dp.Attributes().PutStr("address", l.Address)
	}
}

func (s *ec2Scraper) scrapePSI(sm pmetric.ScopeMetrics, now pcommon.Timestamp) {
	samples, err := s.src.psi(s.cfg.ProcRoot)
	switch {
	case errors.Is(err, os.ErrNotExist):
		s.report("psi", "unavailable: kernel has no /proc/pressure (PSI disabled)", nil)
		return
	case err != nil && len(samples) == 0:
		s.report("psi", "error: "+err.Error(), err)
		return
	}
	s.report("psi", "ok", nil)
	m := newSum(sm, MetricPressureStall, "s", "Total time tasks were stalled waiting for the resource (PSI).")
	for _, p := range samples {
		dp := m.Sum().DataPoints().AppendEmpty()
		dp.SetStartTimestamp(s.start)
		dp.SetTimestamp(now)
		dp.SetDoubleValue(float64(p.TotalUS) / 1e6)
		dp.Attributes().PutStr("resource", p.Resource)
		dp.Attributes().PutStr("level", p.Level)
	}
}

func (s *ec2Scraper) scrapeENA(sm pmetric.ScopeMetrics, now pcommon.Timestamp) {
	nics, err := s.src.stats(s.cfg.ENADrivers)
	if err != nil && len(nics) == 0 {
		s.report("ena", "error: "+err.Error(), err)
		return
	}
	if len(nics) == 0 {
		s.report("ena", "unavailable: no interface uses the ENA driver", nil)
		return
	}
	var exceeded, avail pmetric.Metric
	found := false
	sort.Slice(nics, func(i, j int) bool { return nics[i].Device < nics[j].Device })
	for _, nic := range nics {
		names := make([]string, 0, len(enaAllowances))
		for k := range enaAllowances {
			names = append(names, k)
		}
		sort.Strings(names)
		for _, stat := range names {
			v, ok := nic.Stats[stat]
			if !ok {
				continue
			}
			if !found {
				exceeded = newSum(sm, MetricENAExceeded, "{packet}", "Packets queued or dropped because the instance exceeded an ENA allowance.")
				found = true
			}
			dp := exceeded.Sum().DataPoints().AppendEmpty()
			dp.SetStartTimestamp(s.start)
			dp.SetTimestamp(now)
			dp.SetIntValue(int64(v))
			dp.Attributes().PutStr("device", nic.Device)
			dp.Attributes().PutStr("allowance", enaAllowances[stat])
		}
		if v, ok := nic.Stats[enaConntrackAvailable]; ok {
			if avail == (pmetric.Metric{}) {
				avail = sm.Metrics().AppendEmpty()
				avail.SetName(MetricENAConntrackAvail)
				avail.SetUnit("{connection}")
				avail.SetDescription("Tracked connections the instance can still open before reaching its conntrack allowance.")
				avail.SetEmptyGauge()
			}
			dp := avail.Gauge().DataPoints().AppendEmpty()
			dp.SetTimestamp(now)
			dp.SetIntValue(int64(v))
			dp.Attributes().PutStr("device", nic.Device)
		}
	}
	if !found {
		// Older ENA drivers do not expose the allowance counters.
		s.report("ena", "unavailable: the ENA driver does not report allowance counters (update the ENA driver)", nil)
		return
	}
	s.report("ena", "ok", nil)
}

func (s *ec2Scraper) scrapeIMDS(ctx context.Context, sm pmetric.ScopeMetrics, now pcommon.Timestamp) {
	st, err := s.src.imds.state(ctx)
	if errors.Is(err, errNotEC2) {
		s.report("imds", "unavailable: instance metadata service did not answer", nil)
		return
	}
	if err != nil {
		s.report("imds", "error: "+err.Error(), err)
		return
	}
	s.report("imds", "ok", nil)
	g := newGauge(sm, MetricSpotInterruption, "1", "1 while EC2 has issued a Spot interruption notice for this instance.")
	dp := g.Gauge().DataPoints().AppendEmpty()
	dp.SetTimestamp(now)
	if st.SpotAction != "" {
		dp.SetIntValue(1)
		dp.Attributes().PutStr("action", st.SpotAction)
		dp.Attributes().PutStr("time", st.SpotTime)
	} else {
		dp.SetIntValue(0)
	}
	g = newGauge(sm, MetricRebalance, "1", "1 while EC2 recommends rebalancing this Spot Instance (elevated interruption risk).")
	dp = g.Gauge().DataPoints().AppendEmpty()
	dp.SetTimestamp(now)
	if st.RebalanceNotice != "" {
		dp.SetIntValue(1)
		dp.Attributes().PutStr("notice_time", st.RebalanceNotice)
	} else {
		dp.SetIntValue(0)
	}
	g = newGauge(sm, MetricMaintenance, "{event}", "Active scheduled maintenance events for this instance.")
	dp = g.Gauge().DataPoints().AppendEmpty()
	dp.SetTimestamp(now)
	dp.SetIntValue(int64(st.ScheduledActive))
	if st.ScheduledEarliest != "" {
		dp.Attributes().PutStr("not_before", st.ScheduledEarliest)
	}
}

func newSum(sm pmetric.ScopeMetrics, name, unit, desc string) pmetric.Metric {
	m := sm.Metrics().AppendEmpty()
	m.SetName(name)
	m.SetUnit(unit)
	m.SetDescription(desc)
	sum := m.SetEmptySum()
	sum.SetIsMonotonic(true)
	sum.SetAggregationTemporality(pmetric.AggregationTemporalityCumulative)
	return m
}

func newGauge(sm pmetric.ScopeMetrics, name, unit, desc string) pmetric.Metric {
	m := sm.Metrics().AppendEmpty()
	m.SetName(name)
	m.SetUnit(unit)
	m.SetDescription(desc)
	m.SetEmptyGauge()
	return m
}

// readBootTime reads btime from /proc/stat: PSI totals and ENA counters both count from boot.
func readBootTime(procRoot string) (time.Time, error) {
	f, err := os.Open(filepath.Join(procRoot, "stat"))
	if err != nil {
		return time.Time{}, err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if v, ok := strings.CutPrefix(sc.Text(), "btime "); ok {
			n, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64)
			if err != nil {
				return time.Time{}, err
			}
			return time.Unix(n, 0), nil
		}
	}
	return time.Time{}, errors.New("no btime in stat")
}
