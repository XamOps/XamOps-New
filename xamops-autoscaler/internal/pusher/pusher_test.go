package pusher

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
)

func beat(t *testing.T, extras func() HeartbeatExtras) map[string]interface{} {
	t.Helper()
	var body []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ = io.ReadAll(r.Body)
	}))
	defer srv.Close()
	p := New(srv.URL, "k", "c", "123", 1, 0)
	if extras != nil {
		p.SetHeartbeatExtras(extras)
	}
	if err := p.Heartbeat(); err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatal(err)
	}
	return m
}

// (a) With no closure registered the body is today's three-field heartbeat.
func TestHeartbeatWithoutExtrasIsUnchanged(t *testing.T) {
	m := beat(t, nil)
	want := map[string]interface{}{"clusterName": "c", "cloudAccountId": "123", "agentVersion": "1.0.0-autoscaler"}
	if !reflect.DeepEqual(m, want) {
		t.Errorf("body = %v, want %v", m, want)
	}
}

// (b) A standby (Leader=false) sends leader:false and NO features / drain timeout,
// even when the flag is on.
func TestHeartbeatStandbyAdvertisesNothing(t *testing.T) {
	m := beat(t, func() HeartbeatExtras {
		return HeartbeatExtras{Leader: false, Features: []string{"manual-v1"}, DrainTimeoutSeconds: 1200}
	})
	if m["leader"] != false {
		t.Errorf("leader = %v", m["leader"])
	}
	if _, ok := m["features"]; ok {
		t.Errorf("standby sent features: %v", m)
	}
	if _, ok := m["drainTimeoutSeconds"]; ok {
		t.Errorf("standby sent drainTimeoutSeconds: %v", m)
	}
}

// (c) A leader with the flag off sends leader:true and the drain timeout, no features.
func TestHeartbeatLeaderFlagOff(t *testing.T) {
	m := beat(t, func() HeartbeatExtras { return HeartbeatExtras{Leader: true, DrainTimeoutSeconds: 1200} })
	if m["leader"] != true || m["drainTimeoutSeconds"] != float64(1200) {
		t.Errorf("body = %v", m)
	}
	if _, ok := m["features"]; ok {
		t.Errorf("flag off must not advertise features: %v", m)
	}
}

// (d) A leader with the flag on advertises manual-v1.
func TestHeartbeatLeaderFlagOn(t *testing.T) {
	m := beat(t, func() HeartbeatExtras {
		return HeartbeatExtras{Leader: true, Features: []string{"manual-v1"}, DrainTimeoutSeconds: 600}
	})
	f, _ := m["features"].([]interface{})
	if m["leader"] != true || len(f) != 1 || f[0] != "manual-v1" || m["drainTimeoutSeconds"] != float64(600) {
		t.Errorf("body = %v", m)
	}
}
