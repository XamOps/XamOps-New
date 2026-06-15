#!/usr/bin/env python3
"""
dns-record-checker.py
Resolves DNS records for a domain and prints the results. Supports A and AAAA
record lookups via the standard library (no external dependencies).

Parameters:
  {{DOMAIN_NAME}} - Domain to resolve, e.g. example.com
  {{RECORD_TYPE}} - Record family to query: A, AAAA, or ANY
"""
import argparse
import socket
import sys

DOMAIN_NAME = "{{DOMAIN_NAME}}"
RECORD_TYPE = "{{RECORD_TYPE}}"

FAMILY_MAP = {
    "A": socket.AF_INET,
    "AAAA": socket.AF_INET6,
    "ANY": socket.AF_UNSPEC,
}


def parse_args():
    parser = argparse.ArgumentParser(description="Resolve DNS records for a domain.")
    parser.add_argument("--domain", default=DOMAIN_NAME, help="Domain to resolve.")
    parser.add_argument("--record-type", default=RECORD_TYPE,
                        choices=["A", "AAAA", "ANY"], help="Record family.")
    return parser.parse_args()


def main():
    args = parse_args()
    family = FAMILY_MAP[args.record_type]
    try:
        results = socket.getaddrinfo(args.domain, None, family, socket.SOCK_STREAM)
    except socket.gaierror as exc:
        print(f"ERROR: DNS resolution failed for {args.domain}: {exc}", file=sys.stderr)
        return 1
    addresses = sorted({info[4][0] for info in results})
    if not addresses:
        print(f"No {args.record_type} records found for {args.domain}.")
        return 1
    print(f"{args.record_type} records for {args.domain}:")
    for address in addresses:
        print(f"  {address}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
