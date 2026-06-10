#!/usr/bin/env python3
"""
ssl-certificate-expiry-checker.py
Connects to a host over TLS, reads the server certificate, and reports the
number of days until expiry. Exits non-zero if expiry is within a warning
threshold, suitable for monitoring/alerting.

Parameters:
  {{TARGET_HOST}} - Hostname to check, e.g. example.com
  {{TARGET_PORT}} - TLS port (e.g. 443)
  {{WARN_DAYS}}   - Warn/fail if the cert expires within this many days
"""
import argparse
import socket
import ssl
import sys
from datetime import datetime, timezone

TARGET_HOST = "{{TARGET_HOST}}"
TARGET_PORT = "{{TARGET_PORT}}"
WARN_DAYS = "{{WARN_DAYS}}"

CERT_TIME_FORMAT = "%b %d %H:%M:%S %Y %Z"


def parse_args():
    parser = argparse.ArgumentParser(description="Check TLS certificate expiry.")
    parser.add_argument("--host", default=TARGET_HOST, help="Target hostname.")
    parser.add_argument("--port", type=int, default=int(TARGET_PORT), help="TLS port.")
    parser.add_argument("--warn-days", type=int, default=int(WARN_DAYS),
                        help="Warn threshold in days.")
    parser.add_argument("--timeout", type=float, default=10.0,
                        help="Connection timeout (seconds).")
    return parser.parse_args()


def get_expiry(host, port, timeout):
    context = ssl.create_default_context()
    with socket.create_connection((host, port), timeout=timeout) as sock:
        with context.wrap_socket(sock, server_hostname=host) as ssock:
            cert = ssock.getpeercert()
    not_after = cert["notAfter"]
    expires = datetime.strptime(not_after, CERT_TIME_FORMAT).replace(tzinfo=timezone.utc)
    return expires


def main():
    args = parse_args()
    try:
        expires = get_expiry(args.host, args.port, args.timeout)
    except (socket.timeout, OSError, ssl.SSLError) as exc:
        print(f"ERROR: TLS check failed for {args.host}:{args.port}: {exc}",
              file=sys.stderr)
        return 2
    days_left = (expires - datetime.now(timezone.utc)).days
    print(f"{args.host}:{args.port} certificate expires {expires.date()} "
          f"({days_left} days left)")
    if days_left < 0:
        print("CRITICAL: certificate has expired.", file=sys.stderr)
        return 1
    if days_left <= args.warn_days:
        print(f"WARNING: expires within {args.warn_days} days.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
