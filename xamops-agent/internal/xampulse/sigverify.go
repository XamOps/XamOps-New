package xampulse

import (
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"fmt"
	"os"
	"strings"
)

// DefaultPinnedKeyPath is where an operator pins the release public key. No key ships with the agent: with no
// file here, auto-upgrade stays off and upgrades are manual.
const DefaultPinnedKeyPath = "/etc/xampulse/cosign.pub"

// PinnedKeyVerifier returns a verifier for detached signatures made like `cosign sign-blob --key`: a base64 ECDSA
// P-256 signature (ASN.1 DER) over the SHA-256 of the signed file. pubPEM is a PKIX public key in PEM form.
func PinnedKeyVerifier(pubPEM []byte) (func(sums, sig []byte) error, error) {
	block, _ := pem.Decode(pubPEM)
	if block == nil {
		return nil, errors.New("pinned key is not PEM")
	}
	k, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("pinned key: %w", err)
	}
	pub, ok := k.(*ecdsa.PublicKey)
	if !ok || pub.Curve.Params().Name != "P-256" {
		return nil, errors.New("pinned key must be an ECDSA P-256 public key")
	}
	return func(sums, sig []byte) error {
		raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(sig)))
		if err != nil {
			return errors.New("signature is not base64")
		}
		digest := sha256.Sum256(sums)
		if !ecdsa.VerifyASN1(pub, digest[:], raw) {
			return errors.New("signature does not match the pinned key")
		}
		return nil
	}, nil
}

// LoadPinnedVerifier reads the key file. A missing file returns (nil, nil): no key, auto-upgrade disabled.
func LoadPinnedVerifier(path string) (func(sums, sig []byte) error, error) {
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return PinnedKeyVerifier(b)
}
