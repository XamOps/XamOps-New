package xpec2receiver

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// psiSample is one line of a /proc/pressure file: the cumulative stall time in microseconds.
type psiSample struct {
	Resource string // cpu, memory, io
	Level    string // some, full
	TotalUS  uint64
}

var psiResources = []string{"cpu", "memory", "io"}

// readPSI reads /proc/pressure/{cpu,memory,io}. A missing directory (kernel without PSI, or PSI disabled with psi=0)
// returns os.ErrNotExist so the caller can report "not measurable here" once.
func readPSI(procRoot string) ([]psiSample, error) {
	var out []psiSample
	missing := 0
	for _, r := range psiResources {
		f, err := os.Open(filepath.Join(procRoot, "pressure", r))
		if err != nil {
			if os.IsNotExist(err) {
				missing++
				continue
			}
			return out, err
		}
		samples, err := parsePSI(r, f)
		f.Close()
		if err != nil {
			// PSI compiled in but disabled at boot reads as EOPNOTSUPP.
			return out, fmt.Errorf("read pressure/%s: %w", r, err)
		}
		out = append(out, samples...)
	}
	if missing == len(psiResources) {
		return nil, os.ErrNotExist
	}
	return out, nil
}

// parsePSI parses lines like "some avg10=0.00 avg60=0.00 avg300=0.00 total=123456". Only total is kept: the
// avg* values are 10/60/300 s moving averages that a 30 s scrape would sample, while the total gives the exact
// stall fraction between any two scrapes. cpu has a "full" line since Linux 5.13 (all zero at system level).
func parsePSI(resource string, r io.Reader) ([]psiSample, error) {
	var out []psiSample
	sc := bufio.NewScanner(r)
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) == 0 {
			continue
		}
		level := fields[0]
		if level != "some" && level != "full" {
			continue
		}
		for _, f := range fields[1:] {
			if v, ok := strings.CutPrefix(f, "total="); ok {
				n, err := strconv.ParseUint(v, 10, 64)
				if err != nil {
					return out, fmt.Errorf("bad total %q", v)
				}
				out = append(out, psiSample{Resource: resource, Level: level, TotalUS: n})
			}
		}
	}
	return out, sc.Err()
}
