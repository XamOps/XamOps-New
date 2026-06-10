#!/usr/bin/env python3
"""
multi-endpoint-monitor.py
Reads a newline-delimited file of URLs and performs an HTTP health check on
each, printing a status table and exiting non-zero if any endpoint is down.

Parameters:
  {{ENDPOINTS_FILE}}  - Path to a file with one URL per line
  {{TIMEOUT_SECONDS}} - Per-request timeout in seconds
"""
import argparse
import sys
import time
import urllib.error
import urllib.request

ENDPOINTS_FILE = "{{ENDPOINTS_FILE}}"
TIMEOUT_SECONDS = "{{TIMEOUT_SECONDS}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Monitor multiple HTTP endpoints.")
    parser.add_argument("--endpoints-file", default=ENDPOINTS_FILE,
                        help="File with one URL per line.")
    parser.add_argument("--timeout", type=float, default=float(TIMEOUT_SECONDS),
                        help="Per-request timeout (seconds).")
    return parser.parse_args()


def check(url, timeout):
    start = time.monotonic()
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            elapsed = (time.monotonic() - start) * 1000
            return resp.getcode(), elapsed, None
    except urllib.error.HTTPError as exc:
        elapsed = (time.monotonic() - start) * 1000
        return exc.code, elapsed, None
    except (urllib.error.URLError, TimeoutError) as exc:
        return None, 0.0, str(exc)


def main():
    args = parse_args()
    try:
        with open(args.endpoints_file, encoding="utf-8") as fh:
            urls = [line.strip() for line in fh if line.strip()]
    except FileNotFoundError:
        print(f"ERROR: Endpoints file not found: {args.endpoints_file}", file=sys.stderr)
        return 2
    failures = 0
    for url in urls:
        status, elapsed, error = check(url, args.timeout)
        if error is not None:
            failures += 1
            print(f"DOWN   {url}  ({error})")
        elif 200 <= status < 400:
            print(f"UP     {url}  {status}  {elapsed:.0f} ms")
        else:
            failures += 1
            print(f"DOWN   {url}  {status}")
    print(f"\n{len(urls) - failures}/{len(urls)} endpoint(s) healthy.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
