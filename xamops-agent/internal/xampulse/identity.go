package xampulse

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

// Credentials are the temporary instance-profile credentials. They are held in memory only.
type Credentials struct {
	AccessKeyID     string `json:"AccessKeyId"`
	SecretAccessKey string `json:"SecretAccessKey"`
	Token           string `json:"Token"`
}

// InstanceIdentity is the subset of the IMDS identity document the agent needs.
type InstanceIdentity struct {
	AccountID  string `json:"accountId"`
	InstanceID string `json:"instanceId"`
	Region     string `json:"region"`
}

// IMDS talks to the EC2 instance metadata service with IMDSv2 only (session token on every call).
type IMDS struct {
	BaseURL string // default http://169.254.169.254
	HTTP    *http.Client
}

func (m *IMDS) base() string {
	if m.BaseURL == "" {
		return "http://169.254.169.254"
	}
	return strings.TrimRight(m.BaseURL, "/")
}

func (m *IMDS) client() *http.Client {
	if m.HTTP != nil {
		return m.HTTP
	}
	return &http.Client{Timeout: 3 * time.Second}
}

func (m *IMDS) token(ctx context.Context) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, m.base()+"/latest/api/token", nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("X-aws-ec2-metadata-token-ttl-seconds", "60")
	resp, err := m.client().Do(req)
	if err != nil {
		return "", fmt.Errorf("imds token: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("imds token: status %d (IMDSv2 must be enabled)", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1024))
	return strings.TrimSpace(string(b)), err
}

func (m *IMDS) get(ctx context.Context, path string) ([]byte, error) {
	tok, err := m.token(ctx)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, m.base()+path, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-aws-ec2-metadata-token", tok)
	resp, err := m.client().Do(req)
	if err != nil {
		return nil, fmt.Errorf("imds %s: %w", path, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("imds %s: status %d", path, resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 64*1024))
}

// Identity reads the instance identity document (account, instance id, region).
func (m *IMDS) Identity(ctx context.Context) (InstanceIdentity, error) {
	b, err := m.get(ctx, "/latest/dynamic/instance-identity/document")
	if err != nil {
		return InstanceIdentity{}, err
	}
	var id InstanceIdentity
	if err := json.Unmarshal(b, &id); err != nil {
		return InstanceIdentity{}, fmt.Errorf("imds identity: %w", err)
	}
	if id.InstanceID == "" || id.Region == "" {
		return InstanceIdentity{}, errors.New("imds identity: incomplete document")
	}
	return id, nil
}

// RoleCredentials reads the instance-profile credentials.
func (m *IMDS) RoleCredentials(ctx context.Context) (Credentials, error) {
	names, err := m.get(ctx, "/latest/meta-data/iam/security-credentials/")
	if err != nil {
		return Credentials{}, err
	}
	role := strings.TrimSpace(strings.SplitN(string(names), "\n", 2)[0])
	if role == "" {
		return Credentials{}, errors.New("imds: instance has no instance profile role")
	}
	b, err := m.get(ctx, "/latest/meta-data/iam/security-credentials/"+url.PathEscape(role))
	if err != nil {
		return Credentials{}, err
	}
	var c Credentials
	if err := json.Unmarshal(b, &c); err != nil {
		return Credentials{}, fmt.Errorf("imds credentials: %w", err)
	}
	if c.AccessKeyID == "" || c.SecretAccessKey == "" {
		return Credentials{}, errors.New("imds credentials: empty")
	}
	return c, nil
}

// NonceHeader is the signed header that binds the identity proof to one server challenge.
const NonceHeader = "x-xampulse-nonce"

const (
	stsBody        = "Action=GetCallerIdentity&Version=2011-06-15"
	stsContentType = "application/x-www-form-urlencoded; charset=utf-8"
	sigAlgorithm   = "AWS4-HMAC-SHA256"
)

// SignedRequest is the result of SigV4 signing: the headers to send (including Authorization) and the
// names that were signed, in canonical order.
type SignedRequest struct {
	Headers       map[string]string
	SignedHeaders string
	Signature     string
}

// SignV4 signs a request with AWS Signature Version 4 using only the standard library. headers must contain the
// headers to sign (lower-case or not; they are normalised). Host and x-amz-date are added by the caller.
func SignV4(method, rawURL string, headers map[string]string, body string, creds Credentials, region, service string, now time.Time) (SignedRequest, error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return SignedRequest{}, err
	}
	amzDate := now.UTC().Format("20060102T150405Z")
	date := now.UTC().Format("20060102")

	norm := map[string]string{}
	for k, v := range headers {
		norm[strings.ToLower(strings.TrimSpace(k))] = strings.Join(strings.Fields(v), " ")
	}
	norm["host"] = u.Host
	norm["x-amz-date"] = amzDate
	if creds.Token != "" {
		norm["x-amz-security-token"] = creds.Token
	}

	names := make([]string, 0, len(norm))
	for k := range norm {
		names = append(names, k)
	}
	sort.Strings(names)
	var canonHeaders strings.Builder
	for _, k := range names {
		canonHeaders.WriteString(k + ":" + norm[k] + "\n")
	}
	signedHeaders := strings.Join(names, ";")

	path := u.EscapedPath()
	if path == "" {
		path = "/"
	}
	payloadHash := hexSHA256(body)
	canonicalRequest := strings.Join([]string{
		method, path, canonicalQuery(u), canonHeaders.String(), signedHeaders, payloadHash,
	}, "\n")

	scope := date + "/" + region + "/" + service + "/aws4_request"
	stringToSign := strings.Join([]string{sigAlgorithm, amzDate, scope, hexSHA256(canonicalRequest)}, "\n")

	key := hmacSHA256([]byte("AWS4"+creds.SecretAccessKey), date)
	key = hmacSHA256(key, region)
	key = hmacSHA256(key, service)
	key = hmacSHA256(key, "aws4_request")
	signature := hex.EncodeToString(hmacSHA256(key, stringToSign))

	norm["authorization"] = fmt.Sprintf("%s Credential=%s/%s, SignedHeaders=%s, Signature=%s",
		sigAlgorithm, creds.AccessKeyID, scope, signedHeaders, signature)
	return SignedRequest{Headers: norm, SignedHeaders: signedHeaders, Signature: signature}, nil
}

func canonicalQuery(u *url.URL) string {
	q := u.Query()
	keys := make([]string, 0, len(q))
	for k := range q {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var parts []string
	for _, k := range keys {
		vals := q[k]
		sort.Strings(vals)
		for _, v := range vals {
			parts = append(parts, awsEscape(k)+"="+awsEscape(v))
		}
	}
	return strings.Join(parts, "&")
}

func awsEscape(s string) string {
	return strings.ReplaceAll(url.QueryEscape(s), "+", "%20")
}

func hexSHA256(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

func hmacSHA256(key []byte, data string) []byte {
	m := hmac.New(sha256.New, key)
	m.Write([]byte(data))
	return m.Sum(nil)
}

// SignGetCallerIdentity builds the identity proof for a server nonce. The nonce is a signed header, so the
// proof cannot be replayed for another challenge. Uses the regional STS endpoint.
func SignGetCallerIdentity(creds Credentials, region, nonce string, now time.Time) (StsRequest, error) {
	if nonce == "" {
		return StsRequest{}, errors.New("nonce is required")
	}
	endpoint := "https://sts." + region + ".amazonaws.com/"
	signed, err := SignV4(http.MethodPost, endpoint, map[string]string{
		"content-type": stsContentType,
		NonceHeader:    nonce,
	}, stsBody, creds, region, "sts", now)
	if err != nil {
		return StsRequest{}, err
	}
	hdrs := map[string][]string{}
	for k, v := range signed.Headers {
		hdrs[k] = []string{v}
	}
	return StsRequest{URL: endpoint, Method: http.MethodPost, Headers: hdrs, Body: stsBody}, nil
}
