package xampulse

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

type fakeIMDS struct{}

func (fakeIMDS) Identity(context.Context) (InstanceIdentity, error) {
	return InstanceIdentity{AccountID: "123456789012", InstanceID: "i-0abc", Region: "ap-south-1"}, nil
}
func (fakeIMDS) RoleCredentials(context.Context) (Credentials, error) {
	return Credentials{AccessKeyID: "AK", SecretAccessKey: "SK", Token: "T"}, nil
}

// enrolServer answers challenge normally and enrol with the given statuses in order (last repeats).
func enrolServer(t *testing.T, statuses []int, seen *[]EnrollRequest) *httptest.Server {
	t.Helper()
	var n int32
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/agent/xampulse/enroll/challenge":
			_ = json.NewEncoder(w).Encode(EnrollChallengeResponse{Nonce: "nonce-1", ExpiresInSec: 60})
		case "/api/v1/agent/xampulse/enroll":
			var req EnrollRequest
			_ = json.NewDecoder(r.Body).Decode(&req)
			if seen != nil {
				*seen = append(*seen, req)
			}
			i := int(atomic.AddInt32(&n, 1)) - 1
			if i >= len(statuses) {
				i = len(statuses) - 1
			}
			switch statuses[i] {
			case 200:
				_ = json.NewEncoder(w).Encode(EnrollResponse{AccessToken: "acc", RefreshToken: "ref", ExpiresAt: "2030-01-01T00:00:00Z",
					Config: AgentConfig{ConfigVersion: 3, Level: "T1", GatewayURL: "https://g.example.com/xampulse-ingest"}})
			case 202:
				w.WriteHeader(202)
				_ = json.NewEncoder(w).Encode(EnrollPending{RetryAfterSec: 1, Reason: "PENDING_TAG_SYNC"})
			default:
				w.WriteHeader(statuses[i])
			}
		default:
			http.NotFound(w, r)
		}
	}))
}

func noSleep(slept *[]time.Duration) func(context.Context, time.Duration) error {
	return func(_ context.Context, d time.Duration) error { *slept = append(*slept, d); return nil }
}

func TestEnrollRetriesPendingThenSucceeds(t *testing.T) {
	var seen []EnrollRequest
	srv := enrolServer(t, []int{202, 202, 200}, &seen)
	defer srv.Close()
	var slept []time.Duration
	e := &Enroller{ServerURL: srv.URL, IMDS: fakeIMDS{}, AgentVersion: "1.0.0", Sleep: noSleep(&slept)}
	res, err := e.Enroll(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if res.AccessToken != "acc" || res.Config.ConfigVersion != 3 {
		t.Fatalf("unexpected result %+v", res)
	}
	if len(slept) != 2 {
		t.Fatalf("expected two waits for two 202 answers, got %d", len(slept))
	}
	if len(seen) != 3 || seen[0].Nonce != "nonce-1" || seen[0].StsRequest.Headers[NonceHeader][0] != "nonce-1" {
		t.Fatalf("enrol request must carry the nonce as a signed header: %+v", seen)
	}
	if seen[0].Region != "ap-south-1" || seen[0].AgentVersion != "1.0.0" {
		t.Fatalf("unexpected request %+v", seen[0])
	}
}

func TestEnrollStopsOnForbidden(t *testing.T) {
	srv := enrolServer(t, []int{403}, nil)
	defer srv.Close()
	var slept []time.Duration
	e := &Enroller{ServerURL: srv.URL, IMDS: fakeIMDS{}, Sleep: noSleep(&slept)}
	_, err := e.Enroll(context.Background())
	if !errors.Is(err, ErrNotInScope) {
		t.Fatalf("want ErrNotInScope, got %v", err)
	}
	if len(slept) != 0 {
		t.Fatal("403 must not be retried")
	}
}

func TestEnrollGivesUpWhenPendingTooLong(t *testing.T) {
	srv := enrolServer(t, []int{202}, nil)
	defer srv.Close()
	now := time.Now()
	e := &Enroller{ServerURL: srv.URL, IMDS: fakeIMDS{}, MaxWait: 3 * time.Second,
		Sleep: func(_ context.Context, d time.Duration) error { now = now.Add(d); return nil },
		Now:   func() time.Time { return now }}
	_, err := e.Enroll(context.Background())
	if !errors.Is(err, ErrEnrollTimeout) {
		t.Fatalf("want ErrEnrollTimeout, got %v", err)
	}
}

func TestAgentEnrollStoresTokensWithOwnerOnlyMode(t *testing.T) {
	srv := enrolServer(t, []int{200}, nil)
	defer srv.Close()
	dir := t.TempDir()
	a := &Agent{ServerURL: srv.URL, StateDir: dir, IMDS: fakeIMDS{}, Version: "1.0.0"}
	if err := a.EnsureEnrolled(context.Background()); err != nil {
		t.Fatal(err)
	}
	for _, f := range []string{TokenFileName, AccessTokenFileName} {
		st, err := os.Stat(filepath.Join(dir, f))
		if err != nil {
			t.Fatal(err)
		}
		if st.Mode().Perm() != 0o600 {
			t.Fatalf("%s must be 0600, is %o", f, st.Mode().Perm())
		}
	}
	// A second call must not enrol again.
	srv.Close()
	if err := a.EnsureEnrolled(context.Background()); err != nil {
		t.Fatalf("already enrolled agent must not call the server: %v", err)
	}
}

func TestBackoffCapsAndGrows(t *testing.T) {
	if Backoff(0, time.Second, time.Minute) != time.Second || Backoff(3, time.Second, time.Minute) != 8*time.Second {
		t.Fatal("backoff should double")
	}
	if Backoff(20, time.Second, time.Minute) != time.Minute {
		t.Fatal("backoff must cap")
	}
}
