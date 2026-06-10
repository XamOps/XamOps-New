#!/usr/bin/env python3
"""
api-endpoint-health-checker.py
Performs an HTTP health check against an endpoint, asserting the response
status code and measuring latency. Exits non-zero on failure for use in CI
or monitoring pipelines.

Parameters:
  {{API_ENDPOINT}}    - Full URL to check, e.g. https://api.example.com/health
  {{TIMEOUT_SECONDS}} - Request timeout in seconds
  {{EXPECTED_STATUS}} - Expected HTTP status code (e.g. 200)
"""
import argparse
import sys
import time
import urllib.error
import urllib.request

API_ENDPOINT = "{{API_ENDPOINT}}"
TIMEOUT_SECONDS = "{{TIMEOUT_SECONDS}}"
EXPECTED_STATUS = "{{EXPECTED_STATUS}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Check an HTTP endpoint's health.")
    parser.add_argument("--endpoint", default=API_ENDPOINT, help="URL to check.")
    parser.add_argument("--timeout", type=float, default=float(TIMEOUT_SECONDS),
                        help="Request timeout (seconds).")
    parser.add_argument("--expected-status", type=int, default=int(EXPECTED_STATUS),
                        help="Expected HTTP status code.")
    return parser.parse_args()


def main():
    args = parse_args()
    request = urllib.request.Request(args.endpoint, method="GET")
    start = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=args.timeout) as resp:
            elapsed_ms = (time.monotonic() - start) * 1000
            status = resp.getcode()
            if status == args.expected_status:
                print(f"OK: {args.endpoint} -> {status} in {elapsed_ms:.0f} ms")
                return 0
            print(f"FAIL: {args.endpoint} -> {status} "
                  f"(expected {args.expected_status})", file=sys.stderr)
            return 1
    except urllib.error.HTTPError as exc:
        if exc.code == args.expected_status:
            print(f"OK: {args.endpoint} -> {exc.code}")
            return 0
        print(f"FAIL: {args.endpoint} -> HTTP {exc.code}", file=sys.stderr)
        return 1
    except (urllib.error.URLError, TimeoutError) as exc:
        print(f"FAIL: {args.endpoint} unreachable: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
