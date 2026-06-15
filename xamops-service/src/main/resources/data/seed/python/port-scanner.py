#!/usr/bin/env python3
"""
port-scanner.py
Scans a range of TCP ports on a target host and reports which are open. Useful
for verifying firewall rules and service exposure. Intended for hosts you own
or are authorized to test.

Parameters:
  {{TARGET_HOST}} - Host to scan, e.g. 10.0.0.5
  {{PORT_RANGE}}  - Inclusive port range as 'start-end', e.g. 1-1024
"""
import argparse
import socket
import sys

TARGET_HOST = "{{TARGET_HOST}}"
PORT_RANGE = "{{PORT_RANGE}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Scan TCP ports on a host.")
    parser.add_argument("--host", default=TARGET_HOST, help="Target host.")
    parser.add_argument("--port-range", default=PORT_RANGE,
                        help="Inclusive range 'start-end'.")
    parser.add_argument("--timeout", type=float, default=0.5,
                        help="Per-port connect timeout (seconds).")
    return parser.parse_args()


def parse_range(raw):
    parts = raw.split("-")
    if len(parts) != 2:
        raise ValueError("port-range must be in the form 'start-end'")
    start, end = int(parts[0]), int(parts[1])
    if not (1 <= start <= end <= 65535):
        raise ValueError("ports must be within 1-65535 and start <= end")
    return start, end


def main():
    args = parse_args()
    try:
        start, end = parse_range(args.port_range)
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    open_ports = []
    for port in range(start, end + 1):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.settimeout(args.timeout)
            if sock.connect_ex((args.host, port)) == 0:
                open_ports.append(port)
                print(f"OPEN   {args.host}:{port}")
    print(f"\nScanned ports {start}-{end} on {args.host}: "
          f"{len(open_ports)} open.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
