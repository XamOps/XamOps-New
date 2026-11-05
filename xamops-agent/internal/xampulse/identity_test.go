package xampulse

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// The first vector is the AWS "get-vanilla" case from the published SigV4 test suite. The second was computed
// with an independent implementation (botocore) for the exact STS request this agent sends.
func TestSignV4PublishedVector(t *testing.T) {
	creds := Credentials{AccessKeyID: "AKIDEXAMPLE", SecretAccessKey: "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"}
	now := time.Date(2015, 8, 30, 12, 36, 0, 0, time.UTC)
	got, err := SignV4("GET", "https://example.amazonaws.com/", nil, "", creds, "us-east-1", "service", now)
	if err != nil {
		t.Fatal(err)
	}
	want := "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
		"SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"
	if got.Headers["authorization"] != want {
		t.Fatalf("authorization mismatch:\n got %s\nwant %s", got.Headers["authorization"], want)
	}
}

func TestSignGetCallerIdentityMatchesReferenceAndSignsNonce(t *testing.T) {
	creds := Credentials{AccessKeyID: "AKIDEXAMPLE", SecretAccessKey: "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", Token: "TOKEN123"}
	now := time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)
	req, err := SignGetCallerIdentity(creds, "ap-south-1", "abc123NONCE", now)
	if err != nil {
		t.Fatal(err)
	}
	if req.URL != "https://sts.ap-south-1.amazonaws.com/" || req.Method != "POST" || req.Body != "Action=GetCallerIdentity&Version=2011-06-15" {
		t.Fatalf("unexpected request: %+v", req)
	}
	auth := req.Headers["authorization"][0]
	wantSig := "9350f790b9f3bc52475857b46cd5b88264bdcbb952b2b3919141b00287b6198c"
	if !strings.Contains(auth, "Signature="+wantSig) {
		t.Fatalf("signature mismatch: %s", auth)
	}
	if !strings.Contains(auth, "SignedHeaders=content-type;host;x-amz-date;x-amz-security-token;x-xampulse-nonce") {
		t.Fatalf("nonce header is not signed: %s", auth)
	}
	if req.Headers[NonceHeader][0] != "abc123NONCE" {
		t.Fatal("nonce header value missing")
	}
	if _, err := SignGetCallerIdentity(creds, "ap-south-1", "", now); err == nil {
		t.Fatal("empty nonce must be rejected")
	}
}

func TestSignatureChangesWithNonce(t *testing.T) {
	creds := Credentials{AccessKeyID: "A", SecretAccessKey: "S"}
	now := time.Now()
	a, _ := SignGetCallerIdentity(creds, "us-east-1", "n1", now)
	b, _ := SignGetCallerIdentity(creds, "us-east-1", "n2", now)
	if a.Headers["authorization"][0] == b.Headers["authorization"][0] {
		t.Fatal("a proof for one nonce must not verify for another")
	}
}

func TestIMDSv2Flow(t *testing.T) {
	var sawToken bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPut && r.URL.Path == "/latest/api/token":
			sawToken = true
			_, _ = w.Write([]byte("tok"))
		case r.Header.Get("X-aws-ec2-metadata-token") != "tok":
			http.Error(w, "IMDSv1 is not allowed", http.StatusUnauthorized)
		case r.URL.Path == "/latest/dynamic/instance-identity/document":
			_, _ = w.Write([]byte(`{"accountId":"123456789012","instanceId":"i-0abc","region":"ap-south-1"}`))
		case r.URL.Path == "/latest/meta-data/iam/security-credentials/":
			_, _ = w.Write([]byte("my-role\n"))
		case r.URL.Path == "/latest/meta-data/iam/security-credentials/my-role":
			_, _ = w.Write([]byte(`{"AccessKeyId":"AK","SecretAccessKey":"SK","Token":"T"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	m := &IMDS{BaseURL: srv.URL}
	id, err := m.Identity(context.Background())
	if err != nil || id.InstanceID != "i-0abc" || id.Region != "ap-south-1" || id.AccountID != "123456789012" {
		t.Fatalf("identity: %+v %v", id, err)
	}
	c, err := m.RoleCredentials(context.Background())
	if err != nil || c.AccessKeyID != "AK" || c.Token != "T" {
		t.Fatalf("credentials: %+v %v", c, err)
	}
	if !sawToken {
		t.Fatal("IMDSv2 session token was never requested")
	}
}

func TestIMDSWithoutV2Fails(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "nope", http.StatusForbidden)
	}))
	defer srv.Close()
	if _, err := (&IMDS{BaseURL: srv.URL}).Identity(context.Background()); err == nil {
		t.Fatal("must fail when IMDSv2 is unavailable (no IMDSv1 fallback)")
	}
}
