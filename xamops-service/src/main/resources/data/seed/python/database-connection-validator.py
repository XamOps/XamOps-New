#!/usr/bin/env python3
"""
database-connection-validator.py
Validates connectivity to a database server by opening a TCP socket to the
host/port and reporting reachability and latency. Useful as a readiness probe.

Parameters:
  {{DB_HOST}} - Database hostname or IP
  {{DB_PORT}} - Database TCP port (e.g. 5432)
  {{DB_NAME}} - Database name (informational, included in output)
  {{DB_USER}} - Database user (informational, included in output)
"""
import argparse
import socket
import sys
import time

DB_HOST = "{{DB_HOST}}"
DB_PORT = "{{DB_PORT}}"
DB_NAME = "{{DB_NAME}}"
DB_USER = "{{DB_USER}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Validate database TCP connectivity.")
    parser.add_argument("--host", default=DB_HOST, help="Database host.")
    parser.add_argument("--port", type=int, default=int(DB_PORT), help="Database port.")
    parser.add_argument("--name", default=DB_NAME, help="Database name (info).")
    parser.add_argument("--user", default=DB_USER, help="Database user (info).")
    parser.add_argument("--timeout", type=float, default=5.0,
                        help="Connection timeout (seconds).")
    return parser.parse_args()


def main():
    args = parse_args()
    print(f"Checking {args.user}@{args.host}:{args.port}/{args.name} ...")
    start = time.monotonic()
    try:
        with socket.create_connection((args.host, args.port), timeout=args.timeout):
            elapsed_ms = (time.monotonic() - start) * 1000
            print(f"OK: reachable in {elapsed_ms:.0f} ms")
            return 0
    except (socket.timeout, OSError) as exc:
        print(f"FAIL: cannot reach {args.host}:{args.port}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
