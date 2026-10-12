#!/usr/bin/env python3
"""In-cluster stand-in for the XamOps backend agent API (harness only).

Implements exactly the contract that xamops-agent and xamops-autoscaler call
(see the Phase 1 contract in the harness report), and nothing that could reach
a real XamOps environment:

  GET  /api/v1/agent/autoscaling-policy  -> PolicyBundle JSON from POLICY_FILE
  POST /api/v1/agent/ingest              -> 200, payload recorded
  POST /api/v1/agent/heartbeat           -> 200, recorded (autoscaler)
  GET  /api/v1/agent/config              -> 200 {"openobserve": {}} (no credentials)
  GET  /api/v1/agent/commands            -> 204 (no pending commands)
  anything else                          -> 404, still recorded

Every request is appended to CAPTURE_DIR/requests.jsonl with a timestamp, the
caller, the data types it carried and the full body; every ingest body is also
written to CAPTURE_DIR/ingest/<seq>-<agent>-<collectors>.json. One summary line
per request goes to stdout (kubectl logs).

Auth mirrors the backend: "Authorization: Bearer <key>". The only accepted key
is the harness test key in STUB_API_KEY (generated at deploy time). The key
itself is never logged or stored; captures record only "valid"/"invalid".

Read-only captures API for pulling evidence (port-forward, then GET):
  /_stub/captures            list files
  /_stub/captures/<path>     download one file
  /_stub/policy              the policy currently being served
"""
import gzip
import hmac
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

API_KEY = os.environ.get("STUB_API_KEY", "")
POLICY_FILE = os.environ.get("POLICY_FILE", "/etc/stub-policy/policy.json")
CAPTURE_DIR = os.environ.get("CAPTURE_DIR", "/captures")
PORT = int(os.environ.get("PORT", "8080"))

_lock = threading.Lock()
_seq = 0


def _now():
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime()) + "Z"


def _next_seq():
    global _seq
    with _lock:
        _seq += 1
        return _seq


def _collectors(body):
    if not isinstance(body, dict):
        return []
    out = []
    for b in body.get("batches") or []:
        if isinstance(b, dict):
            out.append(f"{b.get('collector')}({b.get('count')})")
    return out


def _record(entry, ingest_body=None):
    os.makedirs(os.path.join(CAPTURE_DIR, "ingest"), exist_ok=True)
    line = json.dumps(entry, separators=(",", ":"), default=str)
    with _lock:
        with open(os.path.join(CAPTURE_DIR, "requests.jsonl"), "a") as fh:
            fh.write(line + "\n")
        if ingest_body is not None:
            names = "-".join(sorted({c.split("(")[0] for c in entry.get("dataTypes", [])})) or "heartbeat"
            agent = str(ingest_body.get("agentVersion", "unknown")).replace("/", "_")
            # Truncate the collector list, never the ".json" suffix — a push with
            # 8 collectors overflows 200 chars and used to lose its extension.
            stem = f"{entry['seq']:05d}-{agent}-{names}"[:195]
            fname = f"{stem}.json"
            with open(os.path.join(CAPTURE_DIR, "ingest", fname), "w") as fh:
                json.dump({"receivedAt": entry["ts"], "payload": ingest_body}, fh)


class Handler(BaseHTTPRequestHandler):
    server_version = "xamops-harness-stub/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):  # silence default access log; we print our own
        pass

    # ── helpers ──────────────────────────────────────────────────────────
    def _auth(self):
        h = self.headers.get("Authorization", "")
        if not h.startswith("Bearer "):
            return "missing"
        presented = h[7:].strip()
        return "valid" if API_KEY and hmac.compare_digest(presented, API_KEY) else "invalid"

    def _read_body(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n > 0 else b""
        enc = (self.headers.get("Content-Encoding") or "").lower()
        if enc == "gzip" and raw:
            raw = gzip.decompress(raw)
        try:
            return raw, (json.loads(raw) if raw else None), enc
        except ValueError:
            return raw, None, enc

    def _send(self, code, obj=None, raw=None, ctype="application/json"):
        data = raw if raw is not None else (b"" if obj is None else json.dumps(obj).encode())
        self.send_response(code)
        if data:
            self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if data:
            self.wfile.write(data)

    def _finish(self, code, auth, body, enc, raw_len, ingest=False, note=""):
        u = urlparse(self.path)
        entry = {
            "seq": _next_seq(),
            "ts": _now(),
            "method": self.command,
            "path": u.path,
            "query": parse_qs(u.query),
            "status": code,
            "auth": auth,
            "client": self.client_address[0],
            "userAgent": self.headers.get("User-Agent", ""),
            "contentType": self.headers.get("Content-Type", ""),
            "contentEncoding": enc,
            "bytes": raw_len,
            "headers": {k: v for k, v in self.headers.items()
                        if k.lower() in ("x-agent-name", "x-cloud-account-id")},
            "dataTypes": _collectors(body),
            "note": note,
        }
        if body is not None and not ingest:
            entry["body"] = body
        _record(entry, body if ingest and isinstance(body, dict) else None)
        who = ""
        if isinstance(body, dict):
            who = f" agent={body.get('agentVersion')} cluster={body.get('clusterName')} account={body.get('cloudAccountId')}"
        print(f"{entry['ts']} #{entry['seq']} {self.command} {u.path} -> {code} auth={auth}"
              f"{who} bytes={raw_len} types={','.join(entry['dataTypes']) or '-'} {note}".rstrip(),
              flush=True)

    # ── verbs ────────────────────────────────────────────────────────────
    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/healthz":
            return self._send(200, {"status": "ok"})
        if u.path.startswith("/_stub/"):
            return self._stub_api(u)
        auth = self._auth()
        if auth != "valid":
            self._send(401, {"error": "Invalid or missing API key"})
            return self._finish(401, auth, None, "", 0)
        if u.path == "/api/v1/agent/autoscaling-policy":
            try:
                with open(POLICY_FILE) as fh:
                    text = fh.read()
                policy = json.loads(text)
            except FileNotFoundError:
                self._send(404, {"error": "Autoscaling not enabled for this cluster"})
                return self._finish(404, auth, None, "", 0, note="no policy file")
            if isinstance(policy, dict) and policy.get("_stubStatus") == 404:
                self._send(404, {"error": "Autoscaling not enabled for this cluster"})
                return self._finish(404, auth, None, "", 0, note="policy says 404")
            served = {k: v for k, v in policy.items() if not k.startswith("_")}
            self._send(200, served)
            label = policy.get("_label", "")
            return self._finish(200, auth, None, "", 0, note=f"policy={label}")
        if u.path == "/api/v1/agent/config":
            # Minimal valid answer with no credentials: the agent leaves its
            # OpenObserve password empty, so the log watcher stays off.
            self._send(200, {"openobserve": {}})
            return self._finish(200, auth, None, "", 0)
        if u.path == "/api/v1/agent/commands":
            self._send(204)
            return self._finish(204, auth, None, "", 0)
        self._send(404, {"error": "not implemented by harness stub"})
        return self._finish(404, auth, None, "", 0, note="UNKNOWN ENDPOINT")

    def do_POST(self):
        u = urlparse(self.path)
        auth = self._auth()
        raw, body, enc = self._read_body()
        if auth != "valid":
            self._send(401, {"success": False, "error": "Invalid API key"})
            return self._finish(401, auth, body, enc, len(raw), ingest=True)
        if u.path == "/api/v1/agent/ingest":
            results = [{"collector": b.split("(")[0], "success": True} for b in _collectors(body)]
            self._send(200, {"success": True, "results": results})
            return self._finish(200, auth, body, enc, len(raw), ingest=True)
        if u.path == "/api/v1/agent/heartbeat":
            self._send(200, {"success": True, "serverTime": _now()})
            return self._finish(200, auth, body, enc, len(raw))
        self._send(404, {"error": "not implemented by harness stub"})
        return self._finish(404, auth, body, enc, len(raw), note="UNKNOWN ENDPOINT")

    def do_PUT(self):
        return self.do_POST()

    def do_DELETE(self):
        return self.do_POST()

    # ── read-only evidence API ──────────────────────────────────────────
    def _stub_api(self, u):
        if u.path == "/_stub/policy":
            try:
                with open(POLICY_FILE, "rb") as fh:
                    return self._send(200, raw=fh.read())
            except FileNotFoundError:
                return self._send(404, {"error": "no policy file"})
        if u.path == "/_stub/captures":
            files = []
            for root, _, names in os.walk(CAPTURE_DIR):
                for n in sorted(names):
                    p = os.path.join(root, n)
                    files.append({"path": os.path.relpath(p, CAPTURE_DIR), "bytes": os.path.getsize(p)})
            return self._send(200, {"files": files})
        if u.path.startswith("/_stub/captures/"):
            rel = os.path.normpath(u.path[len("/_stub/captures/"):])
            if rel.startswith("..") or os.path.isabs(rel):
                return self._send(400, {"error": "bad path"})
            p = os.path.join(CAPTURE_DIR, rel)
            if not os.path.isfile(p):
                return self._send(404, {"error": "no such capture"})
            with open(p, "rb") as fh:
                return self._send(200, raw=fh.read(), ctype="application/octet-stream")
        return self._send(404, {"error": "unknown stub path"})


def main():
    if not API_KEY:
        print("FATAL: STUB_API_KEY is empty", file=sys.stderr)
        sys.exit(1)
    os.makedirs(os.path.join(CAPTURE_DIR, "ingest"), exist_ok=True)
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"{_now()} stub listening on :{PORT} policy={POLICY_FILE} captures={CAPTURE_DIR}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
