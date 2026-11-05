package xampulse

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"os"
	"path/filepath"
	"testing"
)

// A throw-away key made inside the test; no key is shipped or pinned anywhere.
func testKey(t *testing.T) (*ecdsa.PrivateKey, []byte) {
	t.Helper()
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&k.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	return k, pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der})
}

func sign(t *testing.T, k *ecdsa.PrivateKey, data []byte) []byte {
	t.Helper()
	d := sha256.Sum256(data)
	sig, err := ecdsa.SignASN1(rand.Reader, k, d[:])
	if err != nil {
		t.Fatal(err)
	}
	return []byte(base64.StdEncoding.EncodeToString(sig))
}

func TestPinnedKeyVerifierAcceptsOnlyTheMatchingSignature(t *testing.T) {
	k, pub := testKey(t)
	verify, err := PinnedKeyVerifier(pub)
	if err != nil {
		t.Fatal(err)
	}
	sums := []byte("abc  file\n")
	if err := verify(sums, sign(t, k, sums)); err != nil {
		t.Fatalf("valid signature rejected: %v", err)
	}
	if err := verify([]byte("tampered"), sign(t, k, sums)); err == nil {
		t.Fatal("signature over other content must fail")
	}
	other, _ := testKey(t)
	if err := verify(sums, sign(t, other, sums)); err == nil {
		t.Fatal("signature from another key must fail")
	}
	if err := verify(sums, []byte("!!not base64")); err == nil {
		t.Fatal("garbage must fail")
	}
}

func TestPinnedKeyVerifierRejectsBadKeys(t *testing.T) {
	if _, err := PinnedKeyVerifier([]byte("nope")); err == nil {
		t.Fatal("non PEM must fail")
	}
	k, _ := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(&k.PublicKey)
	if _, err := PinnedKeyVerifier(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der})); err == nil {
		t.Fatal("only P-256 is accepted")
	}
}

func TestLoadPinnedVerifierMissingFileMeansNoAutoUpgrade(t *testing.T) {
	v, err := LoadPinnedVerifier(filepath.Join(t.TempDir(), "absent.pub"))
	if v != nil || err != nil {
		t.Fatalf("want nil,nil got verifier=%t err=%v", v != nil, err)
	}
	_, pub := testKey(t)
	p := filepath.Join(t.TempDir(), "k.pub")
	if err := os.WriteFile(p, pub, 0o644); err != nil {
		t.Fatal(err)
	}
	if v, err := LoadPinnedVerifier(p); v == nil || err != nil {
		t.Fatalf("want verifier, got verifier=%t err=%v", v != nil, err)
	}
}
